package com.verifone.cat.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.verifone.cat.plans.TestPlan;

/**
 * The Automated-Features page parser, driven by a saved copy of the real page (version 82,
 * 2026-09-09). Port of the Python CAT's {@code TestCataloguePage} in {@code test_cat_plans.py}.
 * Evidence about this parser on that copy, never about the live site.
 */
class CataloguePageTest {

    private static List<TestPlan> plans;

    @BeforeAll
    static void parseThePage() {
        plans = CataloguePage.parse(FakeAtlassian.fixture("automated_features_storage.xhtml"));
    }

    @Test
    void theRealPageYieldsEveryPlanOnIt() {
        assertEquals(68, plans.size());
    }

    @Test
    void eachTableIsAttributedToTheHeadingAboveIt() {
        // Three of the five section headings are <p><strong>, not <h2>; one <h2> lives inside a
        // Feature cell and must not become a section.
        Map<String, Long> sections = plans.stream()
                .collect(Collectors.groupingBy(TestPlan::section, Collectors.counting()));
        assertEquals(Map.of(
                "Viper Features", 33L,
                "Non Viper Features", 28L,
                "Deprecated Test plans", 4L,
                "Chevron Specific Test plans", 3L), sections);
    }

    @Test
    void aRowspannedFeatureKeepsAllThreeOfItsPlans() {
        // The Cadence row spans Sl NO. and Feature over two rows; without grid expansion this
        // yields one plan, not three.
        assertEquals(List.of("PETROCORE-79667", "PETROCORE-118517", "PETROCORE-135127"),
                plans.stream().filter(plan -> plan.feature().equals("Cadence"))
                        .map(TestPlan::key).toList());
    }

    @Test
    void featuresAreEntityDecodedAndWhitespaceNormalised() {
        // The cell is literally "Cadence&nbsp;"; a trailing U+00A0 would split the feature in two.
        assertTrue(plans.stream().noneMatch(plan -> plan.feature().contains(" ")));
        assertTrue(plans.stream().anyMatch(plan -> plan.feature().equals("Cadence")));
    }

    @Test
    void aBlankFeatureCellCarriesTheOneAboveItForward() {
        assertTrue(plans.stream().allMatch(plan -> !plan.feature().isEmpty()));
        Map<String, Long> byFeature = plans.stream()
                .collect(Collectors.groupingBy(TestPlan::feature, Collectors.counting()));
        assertEquals(Map.of(
                "Cadence", 3L,
                "TAX", 2L,
                "LicenseTestPlanNewKillFunctionality", 2L),
                byFeature.entrySet().stream()
                        .filter(entry -> entry.getValue() > 1)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        assertEquals(List.of("PETROCORE-71132", "PETROCORE-135480"),
                plans.stream().filter(plan -> plan.feature().equals("TAX")).map(TestPlan::key).toList());
    }

    @Test
    void theFirstKeyIsTakenOutOfARunOnPlanCell() {
        // "PETROCORE-135127includes all back-office reports)" and "PETROCORE-118517Base057.00 onwards".
        Set<String> keys = plans.stream().map(TestPlan::key).collect(Collectors.toSet());
        assertTrue(keys.containsAll(Set.of("PETROCORE-135127", "PETROCORE-118517", "PETROCORE-134968")));
        assertTrue(plans.stream().allMatch(plan -> plan.key().startsWith("PETROCORE-")));
    }

    @Test
    void aHeaderlessTableIsReadPositionally() {
        // The Deprecated table has no header row: its first row is data. A header-only parser
        // silently returns nothing for it.
        assertEquals(List.of("Cadence-FCC", "SCO-Old", "GloryAutomation", "LicenseTestPlanNewKillFunctionality"),
                plans.stream().filter(plan -> plan.section().equals("Deprecated Test plans"))
                        .map(TestPlan::feature).toList());
    }

    @Test
    void rowsWithNoPlanKeyAreDropped() {
        Set<String> features = plans.stream().map(TestPlan::feature).collect(Collectors.toSet());
        assertFalse(features.contains("BP UAT"));
        assertFalse(features.contains("BP Loyalty"));
    }

    @Test
    void columnsAreIndexedByNameNotPosition() {
        // The Viper table inserts a "Host Simulator/Test Host" column whose header is dirty
        // ("...Hosttalk-87167"). A fixed index reads XML/BDD out of it.
        List<TestPlan> viper = plans.stream().filter(plan -> plan.section().equals("Viper Features")).toList();
        assertTrue(Set.of("XML", "BDD", "").containsAll(viper.stream().map(TestPlan::kind).toList()));
        assertTrue(viper.stream().anyMatch(plan -> plan.kind().equals("BDD")));
    }

    @Test
    void theCharityPlanIsReadInFull() {
        List<TestPlan> charity = plans.stream().filter(plan -> plan.key().equals("PETROCORE-70960")).toList();
        assertEquals(1, charity.size());
        // The page says 38; Jira returns 39. The page's count is kept verbatim: Xray decides the
        // case list, and neither number is fixed from the other.
        assertEquals(new TestPlan("PETROCORE-70960", "Charity-Donations-Indoor", "Non Viper Features",
                "Working", "38", "PETROCORE-70909", "XML"), charity.get(0));
    }

    @Test
    void anEmptyPageIsNoPlansNotACrash() {
        assertEquals(List.of(), CataloguePage.parse(""));
        assertEquals(List.of(), CataloguePage.parse("<p>nothing here</p>"));
    }
}
