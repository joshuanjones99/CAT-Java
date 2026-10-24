package com.verifone.cat.plans;

/**
 * One row of the Automated-Features catalogue: a test plan and what the page says of it.
 *
 * <p>The cells stay strings, as the page states them. They are dirty ({@code "38"},
 * {@code ""}, {@code "6+2"}), and Xray is authoritative for the real case count anyway.
 * Ported from {@code cat.plans.TestPlan}.
 */
public record TestPlan(
        String key,
        String feature,
        String section,
        String status,
        String caseCount,
        String testSet,
        String kind) {

    public TestPlan {
        key = Text.orEmpty(key);
        feature = Text.orEmpty(feature);
        section = Text.orEmpty(section);
        status = Text.orEmpty(status);
        caseCount = Text.orEmpty(caseCount);
        testSet = Text.orEmpty(testSet);
        kind = Text.orEmpty(kind);
    }

    /**
     * The picker row: feature, key, cases, status. Padded rather than separated, so the
     * columns line up down a list. A blank case count reads {@code ?}, not {@code 0}:
     * the page does not know the count, and {@code 0} would say it did.
     */
    public String label() {
        String cases = caseCount.isEmpty() ? "?" : caseCount;
        return String.format("%-34s %-18s %4s cases  %s", feature, key, cases, status).stripTrailing();
    }
}
