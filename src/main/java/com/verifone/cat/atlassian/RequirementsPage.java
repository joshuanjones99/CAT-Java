package com.verifone.cat.atlassian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

import com.verifone.cat.plans.PlanRequirements;

/**
 * Parses the Supported CAT Test Plans Confluence page (storage XHTML) into one row per plan.
 * Ported from the Python CAT's {@code cat.requirements.parse_requirements_page}.
 *
 * <p>Only tables with a "Jira test Plan" header are read. The Chevron table names its Commander
 * column "Commander Onboarding Required", which is why the commander field matches either name.
 * A plan listed twice keeps its first row. Simulator and device cells split on commas,
 * semicolons and line breaks; a lone {@code -} means none.
 *
 * <p>UNVERIFIED AGAINST HARDWARE. The test fixture is a cut-down copy of the page's shapes, not
 * the live page.
 */
public final class RequirementsPage {

    /** Field -> header substrings that name its column, matched on {@link StorageHtml#headerKey}. */
    private static final Map<String, List<String>> COLUMNS = Map.of(
            "plan", List.of("jiratestplan"),
            "base", List.of("requiredbase"),
            "commander_connect", List.of("commanderconnectrequired", "commanderonboardingrequired"),
            "simulators", List.of("simulators"),
            "devices", List.of("devices"));

    private static final List<String> YES = List.of("yes", "y", "true", "required");

    private RequirementsPage() {}

    /** Every plan row of the page's tables that have a Jira test Plan header, in page order. */
    public static List<PlanRequirements> parse(String storageXhtml) {
        Map<String, PlanRequirements> found = new LinkedHashMap<>();
        for (StorageHtml.Block block : StorageHtml.blocks(storageXhtml)) {
            if (!(block instanceof StorageHtml.Table table)) {
                continue;
            }
            List<List<String>> grid = StorageHtml.rectangular(table.rows());
            if (grid.isEmpty()) {
                continue;
            }
            List<String> header = new ArrayList<>();
            for (String cell : grid.get(0)) {
                header.add(StorageHtml.headerKey(cell));
            }
            if (header.stream().noneMatch(cell -> cell.contains("jiratestplan"))) {
                continue;
            }
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> field : COLUMNS.entrySet()) {
                Integer index = firstMatching(header, field.getValue());
                if (index != null) {
                    columns.put(field.getKey(), index);
                }
            }
            for (List<String> row : grid.subList(1, grid.size())) {
                Matcher key = CataloguePage.PLAN_KEY.matcher(cleaned(row, columns, "plan"));
                if (!key.find() || found.containsKey(key.group())) {
                    continue;
                }
                // The names cells are read raw: a cell's line breaks are its separators.
                found.put(key.group(), new PlanRequirements(
                        key.group(),
                        cleaned(row, columns, "base"),
                        YES.contains(cleaned(row, columns, "commander_connect").toLowerCase(Locale.ROOT)),
                        names(raw(row, columns, "simulators")),
                        names(raw(row, columns, "devices"))));
            }
        }
        return List.copyOf(found.values());
    }

    /** The first header, by position, that contains any of {@code needles}; null when none does. */
    private static Integer firstMatching(List<String> header, List<String> needles) {
        for (int i = 0; i < header.size(); i++) {
            for (String needle : needles) {
                if (header.get(i).contains(needle)) {
                    return i;
                }
            }
        }
        return null;
    }

    private static String cleaned(List<String> row, Map<String, Integer> columns, String field) {
        return StorageHtml.clean(raw(row, columns, field));
    }

    private static String raw(List<String> row, Map<String, Integer> columns, String field) {
        Integer index = columns.get(field);
        return index != null && index < row.size() ? row.get(index) : "";
    }

    /** A Simulators or Devices cell: names split on commas, semicolons and line breaks. */
    static List<String> names(String text) {
        List<String> names = new ArrayList<>();
        for (String part : text.split("[,;\n]")) {
            String name = StorageHtml.clean(part);
            if (!name.isEmpty() && !name.equals("-")) {
                names.add(name);
            }
        }
        return names;
    }
}
