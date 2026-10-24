package com.verifone.cat.runconfig;

import java.util.List;

/**
 * Everything the Config tab draws, as data: built by {@link ConfigForm#view()}, rendered by the
 * web layer. Immutable, so a page is drawn from one consistent read of the form.
 *
 * @param filter the plan filter as typed (not saved)
 * @param sections the offered plans, grouped by page section (Deprecated last), filtered
 * @param planCount how many plans the catalogue offers, filtered or not
 * @param catalogueSource where the picker's rows came from, as the Python CAT words it
 * @param catalogueNote why the picker is short or stale; empty when it is not
 * @param catalogueAge how old the picker's rows are, in words
 * @param caseLines one line per ticked plan: its case counts, or why it has none
 * @param needs the "Needs:" line for the ticked plans; empty with none ticked
 * @param scope the Run scope as saved
 * @param partial whether the scope selects less than the whole plan
 * @param scopeSummary the Run scope's line; empty when it is whole or cannot start a run
 * @param scopeWarnings the warnings under the Run scope
 * @param tagCount for Tags: how many cases the expression matched; empty otherwise
 * @param caseRows the Run scope's case list, in run order
 * @param missing what stops a run from starting; empty when it can start
 * @param summary the "What Start run will do" card's lines
 */
public record ConfigView(
        String filter,
        List<Section> sections,
        int planCount,
        String catalogueSource,
        String catalogueNote,
        String catalogueAge,
        List<String> caseLines,
        String needs,
        RunScope scope,
        boolean partial,
        String scopeSummary,
        List<String> scopeWarnings,
        String tagCount,
        List<CaseRow> caseRows,
        List<String> missing,
        List<String> summary) {

    /** One plan on the picker. */
    public record PlanRow(String key, String label, boolean ticked) {}

    /** A page section of the picker and its plans. */
    public record Section(String name, List<PlanRow> rows) {}

    /**
     * One case on the Run scope's list.
     *
     * @param added a prerequisite the scope adds (the warning says so)
     * @param neededBy for an added case, the case it comes before
     */
    public record CaseRow(String key, String title, String tags, boolean ticked, boolean added,
            String neededBy) {}

    public ConfigView {
        filter = filter == null ? "" : filter;
        sections = List.copyOf(sections);
        catalogueSource = catalogueSource == null ? "" : catalogueSource;
        catalogueNote = catalogueNote == null ? "" : catalogueNote;
        catalogueAge = catalogueAge == null ? "" : catalogueAge;
        caseLines = List.copyOf(caseLines);
        needs = needs == null ? "" : needs;
        scopeSummary = scopeSummary == null ? "" : scopeSummary;
        scopeWarnings = List.copyOf(scopeWarnings);
        tagCount = tagCount == null ? "" : tagCount;
        caseRows = List.copyOf(caseRows);
        missing = List.copyOf(missing);
        summary = List.copyOf(summary);
    }
}
