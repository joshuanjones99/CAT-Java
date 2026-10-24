package com.verifone.cat.atlassian;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.plans.TestPlan;

/**
 * Parses the Automated-Features Confluence page (storage XHTML) into the plan catalogue, in
 * page order. Ported from the Python CAT's {@code cat.plans.parse_catalogue_page}.
 *
 * <p>Every table is read by the heading above it. A table with a "Jira test Plan" header is
 * read by column name, because the Viper table inserts a "Host Simulator/Test Host" column and
 * a fixed index would read the wrong field. A table with no header row is read positionally
 * (feature, plan). A blank Feature cell continues the one above it. A row with no plan key is a
 * note, not a plan, and is dropped.
 *
 * <p>UNVERIFIED AGAINST HARDWARE. This is evidence about the parser's behaviour on a saved copy of
 * the page (the test fixture), not about the live site.
 */
public final class CataloguePage {

    /** The plan key, {@code PETROCORE-<digits>}, as the page writes it. */
    static final Pattern PLAN_KEY = Pattern.compile("PETROCORE-[0-9]+");

    /** Field -> the header substring that names its column, matched on {@link StorageHtml#headerKey}. */
    private static final Map<String, String> COLUMNS = Map.of(
            "feature", "feature",
            "plan", "jiratestplan",
            "case_count", "testcases",
            "kind", "xmlbdd",
            "status", "status",
            "test_set", "jiratestset");

    private CataloguePage() {}

    /** Every plan the page's tables list, in page order. An empty page is no plans, not an error. */
    public static List<TestPlan> parse(String storageXhtml) {
        List<TestPlan> plans = new ArrayList<>();
        String section = "";
        for (StorageHtml.Block block : StorageHtml.blocks(storageXhtml)) {
            if (block instanceof StorageHtml.Heading heading) {
                section = stripTrailingColons(StorageHtml.clean(heading.text())).strip();
                continue;
            }
            List<List<String>> grid = StorageHtml.rectangular(((StorageHtml.Table) block).rows());
            if (grid.isEmpty()) {
                continue;
            }
            // No table uses <th> for its real header row, so detect the header by content.
            List<String> header = new ArrayList<>();
            for (String cell : grid.get(0)) {
                header.add(StorageHtml.headerKey(cell));
            }
            Map<String, Integer> columns;
            List<List<String>> body;
            if (header.stream().anyMatch(cell -> cell.contains("jiratestplan"))) {
                columns = new HashMap<>();
                for (Map.Entry<String, String> field : COLUMNS.entrySet()) {
                    Integer index = indexContaining(header, field.getValue());
                    if (index != null) {
                        columns.put(field.getKey(), index);
                    }
                }
                body = grid.subList(1, grid.size());
            } else {
                // A headerless table (Deprecated Test plans) is positional: its plans are only
                // reachable this way.
                columns = Map.of("feature", 0, "plan", 1);
                body = grid;
            }
            String feature = "";
            for (List<String> row : body) {
                String current = cell(row, columns, "feature");
                // A blank Feature cell continues the one above it.
                feature = current.isEmpty() ? feature : current;
                Matcher key = PLAN_KEY.matcher(cell(row, columns, "plan"));
                if (!key.find()) {
                    continue; // a row with no plan key is a note, not a plan
                }
                Matcher testSet = PLAN_KEY.matcher(cell(row, columns, "test_set"));
                plans.add(new TestPlan(
                        key.group(),
                        feature,
                        section,
                        cell(row, columns, "status"),
                        cell(row, columns, "case_count"),
                        testSet.find() ? testSet.group() : "",
                        cell(row, columns, "kind")));
            }
        }
        return plans;
    }

    /** The first header that contains {@code needle}, or null. Shared with {@link RequirementsPage}. */
    static Integer indexContaining(List<String> header, String needle) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).contains(needle)) {
                return i;
            }
        }
        return null;
    }

    private static String cell(List<String> row, Map<String, Integer> columns, String field) {
        Integer index = columns.get(field);
        return index != null && index < row.size() ? StorageHtml.clean(row.get(index)) : "";
    }

    /** {@code rstrip(":")}: removes every trailing colon, as the Python does. */
    static String stripTrailingColons(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ':') {
            end--;
        }
        return text.substring(0, end);
    }
}
