package com.verifone.cat.atlassian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.PlanSource;
import com.verifone.cat.plans.PlanSourceUnavailable;
import com.verifone.cat.plans.TestPlan;

/**
 * The network {@link PlanSource}: the Automated-Features page for the catalogue, the Supported
 * CAT Test Plans page for the requirements, and Xray for a plan's cases in the plan's own order.
 * Ported from the Python CAT's {@code cat.plans.fetch_catalogue}, {@code fetch_cases} and
 * {@code cat.requirements.fetch_requirements}.
 *
 * <p>Every {@link AtlassianUnavailable} becomes a {@link PlanSourceUnavailable}, which is what
 * {@link com.verifone.cat.plans.PlanCatalogue} catches to fall back to its cache and the seed.
 *
 * <p>Case nodes are left {@code null} here. The Python CAT resolves each case's test file while
 * it parses, but this port's {@code PlanCatalogue} resolves every null node against the checkout
 * on the way out, so the answer is the same and the resolution lives in one place.
 *
 * <p>UNVERIFIED AGAINST HARDWARE. Tests drive this against a loopback fake and a saved copy of
 * the pages; the live site has not been called from this port.
 */
public final class AtlassianPlanSource implements PlanSource {

    /** The Automated-Features page; the Python CAT's {@code PAGE_ID}. */
    public static final String AUTOMATED_FEATURES_PAGE = "183272339";

    /** The Supported CAT Test Plans page; the Python CAT's {@code requirements.PAGE_ID}. */
    public static final String SUPPORTED_REQUIREMENTS_PAGE = "1512931345";

    /** The default number of test cases asked for per Xray page. */
    static final int DEFAULT_PAGE_SIZE = 100;

    /** A bound on Xray pages: a plan larger than this is an error, not a loop. */
    static final int MAX_PAGES = 50;

    /** The only shape a plan key may take before it is written into a JQL string. */
    static final Pattern PLAN_KEY = Pattern.compile("PETROCORE-[0-9]+");

    /** A plan's tests, one page at a time, in the plan's own order. */
    static final String PLAN_TESTS_QUERY = """
            query($jql: String, $start: Int, $limit: Int!) {
              getTestPlans(jql: $jql, limit: 1) { results {
                tests(start: $start, limit: $limit) { total results { jira(fields: ["key", "summary"]) } }
              } }
            }""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtlassianClient client;
    private final int pageSize;

    public AtlassianPlanSource(AtlassianClient client) {
        this(client, DEFAULT_PAGE_SIZE);
    }

    AtlassianPlanSource(AtlassianClient client, int pageSize) {
        this.client = client;
        this.pageSize = pageSize;
    }

    @Override
    public List<TestPlan> catalogue() {
        return unavailable(() -> CataloguePage.parse(storage(AUTOMATED_FEATURES_PAGE)));
    }

    @Override
    public List<PlanRequirements> requirements() {
        return unavailable(() -> RequirementsPage.parse(storage(SUPPORTED_REQUIREMENTS_PAGE)));
    }

    /**
     * A plan's cases, read from Xray page by page in the plan's order.
     *
     * @throws IllegalArgumentException when {@code planKey} is not {@code PETROCORE-<digits>}; the
     *     key is written into a JQL string, so nothing else is accepted
     * @throws PlanSourceUnavailable when Xray cannot be read, or stops short of the plan's total
     */
    @Override
    public List<PlanCase> cases(String planKey) {
        if (planKey == null || !PLAN_KEY.matcher(planKey).matches()) {
            throw new IllegalArgumentException("not a test-plan key: " + planKey);
        }
        return unavailable(() -> fetchCases(planKey));
    }

    private static <T> T unavailable(Supplier<T> read) {
        try {
            return read.get();
        } catch (AtlassianUnavailable e) {
            throw new PlanSourceUnavailable(e.getMessage());
        }
    }

    /** The page's storage XHTML, or empty when the reply carries none (as the Python). */
    private String storage(String pageId) {
        JsonNode payload = client.getJson("/wiki/api/v2/pages/" + pageId, Map.of("body-format", "storage"));
        JsonNode value = payload.at("/body/storage/value");
        return value.isTextual() ? value.asText() : "";
    }

    private List<PlanCase> fetchCases(String planKey) {
        List<JsonNode> payloads = new ArrayList<>();
        int seen = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("jql", "key = \"" + planKey + "\"");
            variables.put("start", seen);
            variables.put("limit", pageSize);
            JsonNode data = client.xrayGraphql(PLAN_TESTS_QUERY, variables);
            payloads.add(data);
            JsonNode tests = planTests(data);
            int count = elements(tests.get("results")).size();
            seen += count;
            // `total` is the plan's size. Stopping at an empty page would report a prefix as the
            // whole plan, so a page that adds nothing short of the total is an error below.
            if (seen >= tests.path("total").asInt(0)) {
                return parseCases(payloads);
            }
            if (count == 0) {
                break;
            }
        }
        throw new AtlassianUnavailable("Xray's test list for " + planKey + " stopped at " + seen
                + " tests — the case list would be truncated");
    }

    private static List<PlanCase> parseCases(List<JsonNode> payloads) {
        List<PlanCase> cases = new ArrayList<>();
        for (JsonNode data : payloads) {
            for (JsonNode test : elements(planTests(data).get("results"))) {
                JsonNode fields = test.path("jira");
                String key = text(fields, "key");
                if (key.isEmpty()) {
                    continue;
                }
                cases.add(new PlanCase(key, text(fields, "summary"), null));
            }
        }
        return cases;
    }

    /** The {@code tests} block of the one plan a plan-tests query names, or an empty object. */
    private static JsonNode planTests(JsonNode data) {
        JsonNode plans = data.path("getTestPlans").path("results");
        if (plans.isArray() && plans.size() > 0) {
            return plans.get(0).path("tests");
        }
        return JSON.createObjectNode();
    }

    /** A JSON array's elements; anything else (absent, null, an object) has none. */
    private static List<JsonNode> elements(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(out::add);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
