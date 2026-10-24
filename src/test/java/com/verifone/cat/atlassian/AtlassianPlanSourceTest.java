package com.verifone.cat.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.atlassian.AtlassianCredentials.Jira;
import com.verifone.cat.atlassian.AtlassianCredentials.Xray;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanSourceUnavailable;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.plans.TestPlan;
import com.verifone.cat.plans.Snapshot;

/**
 * {@link AtlassianPlanSource} over the loopback fake, with the saved pages as the replies.
 * Port of the Python CAT's {@code TestFetching} in {@code test_cat_plans.py}: the pager, the
 * truncation guard and the error branches. Offline by construction; never the live site.
 */
class AtlassianPlanSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Jira JIRA = new Jira("tester@verifone.com", "not-a-real-token");
    private static final Xray XRAY = new Xray("an-id", "not-a-real-secret");
    private static final String CHARITY = "PETROCORE-70960";

    @TempDir
    Path dir;

    private FakeAtlassian fake;

    @BeforeEach
    void start() {
        fake = new FakeAtlassian();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private AtlassianPlanSource source(int pageSize) {
        AtlassianClient client = new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"),
                () -> JIRA, () -> XRAY, Duration.ofSeconds(5));
        return new AtlassianPlanSource(client, pageSize);
    }

    /** Answers the Xray authenticate, and a graphql call with the saved page for its {@code start}. */
    private void xrayWithSavedPages() {
        fake.on("POST", "/api/v2/authenticate", 200, "\"a-token\"");
        fake.onRequest("POST", "/api/v2/graphql", request -> {
            int start = startOf(request);
            String page = start == 0 ? "xray_testplan_PETROCORE-70960_page1.json"
                    : "xray_testplan_PETROCORE-70960_page2.json";
            return new FakeAtlassian.Reply(200, FakeAtlassian.fixture(page));
        });
    }

    private static int startOf(FakeAtlassian.Seen request) {
        try {
            return JSON.readTree(request.body()).get("variables").get("start").asInt();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String storageReply(String storage) {
        try {
            return JSON.writeValueAsString(Map.of("body", Map.of("storage", Map.of("value", storage))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theCatalogueIsTheAutomatedFeaturesPageParsed() {
        fake.on("GET", "/wiki/api/v2/pages/183272339", 200,
                storageReply(FakeAtlassian.fixture("automated_features_storage.xhtml")));
        assertEquals(68, source(100).catalogue().size());
        assertEquals("body-format=storage", fake.seen.get(0).query());
    }

    @Test
    void theRequirementsAreTheSupportedPageParsed() {
        fake.on("GET", "/wiki/api/v2/pages/1512931345", 200,
                storageReply(FakeAtlassian.fixture("requirements_page.xhtml")));
        assertEquals(4, source(100).requirements().size());
    }

    @Test
    void aReplyWithNoStorageBodyIsNoPlansNotACrash() {
        fake.on("GET", "/wiki/api/v2/pages/183272339", 200, "{\"id\":\"1\"}");
        assertEquals(List.of(), source(100).catalogue());
    }

    @Test
    void aSiteErrorIsPlanSourceUnavailable() {
        fake.on("GET", "/wiki/api/v2/pages/183272339", 500, "boom");
        assertThrows(PlanSourceUnavailable.class, () -> source(100).catalogue());
    }

    @Test
    void thePagerReadsBothSavedPagesInThePlansOwnOrderWithNodesLeftForTheCatalogue() throws IOException {
        xrayWithSavedPages();
        List<PlanCase> cases = source(20).cases(CHARITY);

        assertEquals(39, cases.size());
        assertEquals("PETROCORE-61396", cases.get(0).key());
        assertEquals("PETROCORE-124034", cases.get(1).key());
        assertTrue(cases.stream().allMatch(c -> c.node() == null));
        assertEquals("POS-Login", cases.get(3).summary());
        // Each Xray page authenticates and then queries (one bearer token per call), and there are
        // exactly two pages: no re-fetch, no short read.
        assertEquals(4, fake.seen.size());
        assertEquals("/api/v2/authenticate", fake.seen.get(0).path());
        assertEquals("/api/v2/graphql", fake.seen.get(1).path());
        assertEquals("/api/v2/authenticate", fake.seen.get(2).path());
        assertEquals("/api/v2/graphql", fake.seen.get(3).path());
        assertEquals("key = \"" + CHARITY + "\"",
                JSON.readTree(fake.seen.get(1).body()).get("variables").get("jql").asText());
        assertEquals(20, JSON.readTree(fake.seen.get(3).body()).get("variables").get("start").asInt());
    }

    @Test
    void aPageThatAddsNothingShortOfTheTotalIsTruncationNotThePlan() {
        fake.on("POST", "/api/v2/authenticate", 200, "\"a-token\"");
        fake.onRequest("POST", "/api/v2/graphql", request -> startOf(request) == 0
                ? new FakeAtlassian.Reply(200, FakeAtlassian.fixture("xray_testplan_PETROCORE-70960_page1.json"))
                : new FakeAtlassian.Reply(200,
                        "{\"data\":{\"getTestPlans\":{\"results\":[{\"tests\":{\"total\":39,\"results\":[]}}]}}}"));
        PlanSourceUnavailable thrown = assertThrows(PlanSourceUnavailable.class,
                () -> source(20).cases(CHARITY));
        assertTrue(thrown.getMessage().contains("truncated"));
    }

    @Test
    void graphqlErrorsAreUnavailableNotAnEmptyPlan() {
        fake.on("POST", "/api/v2/authenticate", 200, "\"a-token\"");
        fake.on("POST", "/api/v2/graphql", 200, "{\"errors\":[{\"message\":\"bad query\"}]}");
        PlanSourceUnavailable thrown = assertThrows(PlanSourceUnavailable.class,
                () -> source(20).cases(CHARITY));
        assertTrue(thrown.getMessage().contains("bad query"));
    }

    @Test
    void aPlanKeyThatIsNotPlanShapedIsRefusedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> source(20).cases("PETROCORE-1\" OR key = \"x"));
        assertThrows(IllegalArgumentException.class, () -> source(20).cases(null));
        assertEquals(0, fake.seen.size());
    }

    @Test
    void anUnavailableCatalogueFallsBackToTheShippedSeedThroughThePlanCatalogue() {
        fake.on("GET", "/wiki/api/v2/pages/183272339", 500, "boom");
        PlanCatalogue catalogue = new PlanCatalogue(dir.resolve("cache"), source(100),
                new SeedPlanSource(), dir);
        Snapshot<TestPlan> snapshot = catalogue.catalogue(PlanCatalogue.Mode.REFRESH);

        assertEquals(Snapshot.Source.SEED, snapshot.source());
        assertEquals(68, snapshot.rows().size());
        assertFalse(snapshot.reason().isEmpty());
    }

    @Test
    void theCatalogueResolvesEachCaseNodeAgainstTheCheckout() throws IOException {
        // Only case 61334 has a test file in this checkout; the rest must come back Not Run.
        Path suite = Files.createDirectories(dir.resolve("tests/suites/charity"));
        Files.writeString(suite.resolve("PETROCORE-61334-TestCase.py"), "def test_x():\n    pass\n");
        xrayWithSavedPages();

        PlanCatalogue catalogue = new PlanCatalogue(dir.resolve("cache"), source(20),
                new SeedPlanSource(), dir);
        List<PlanCase> cases = catalogue.cases(CHARITY, PlanCatalogue.Mode.CACHE_FIRST).rows();

        PlanCase found = cases.stream().filter(c -> c.key().equals("PETROCORE-61334")).findFirst().orElseThrow();
        assertNotNull(found.node());
        assertTrue(found.node().endsWith("PETROCORE-61334-TestCase.py"));
        assertEquals(1, cases.stream().filter(c -> c.node() != null).count());
        assertNull(cases.stream().filter(c -> c.key().equals("PETROCORE-61396")).findFirst().orElseThrow().node());
    }
}
