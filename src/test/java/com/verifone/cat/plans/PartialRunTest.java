package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartialRunTest {

    @TempDir
    Path root;

    /** Five cases in plan order, each with a node file that does not need to exist for selection by key. */
    private static List<CaseRef> plan() {
        return List.of(
                new CaseRef("PETROCORE-1", "tests/a/PETROCORE-1-TestCase.py"),
                new CaseRef("PETROCORE-2", "tests/a/PETROCORE-2-TestCase.py"),
                new CaseRef("PETROCORE-3", "tests/a/PETROCORE-3-TestCase.py"),
                new CaseRef("PETROCORE-4", "tests/a/PETROCORE-4-TestCase.py"),
                new CaseRef("PETROCORE-5", "tests/a/PETROCORE-5-TestCase.py"));
    }

    private static Set<String> tags(String... names) {
        return Set.of(names);
    }

    @Test
    void aBareKeyNumberNormalisesToThePlanKey() {
        assertEquals("PETROCORE-36847", PartialRun.normaliseKey("36847"));
        assertEquals("PETROCORE-36847", PartialRun.normaliseKey(" petrocore-36847 "));
    }

    @Test
    void notBindsTighterThanAndAndAndTighterThanOr() {
        // `not @a or @b`: (not @a) or @b. With only @b it is true; with only @a it is false.
        assertTrue(PartialRun.parseTags("not @a or @b").test(tags("@b")));
        assertFalse(PartialRun.parseTags("not @a or @b").test(tags("@a")));
        // `@a or @b and @c`: @a or (@b and @c).
        assertTrue(PartialRun.parseTags("@a or @b and @c").test(tags("@a")));
        assertFalse(PartialRun.parseTags("@a or @b and @c").test(tags("@b")));
    }

    @Test
    void commaSeparatedPartsAreAndedAndAGroupCanBeNested() {
        assertFalse(PartialRun.parseTags("@a, @b").test(tags("@a")));
        assertTrue(PartialRun.parseTags("@a, @b").test(tags("@a", "@b")));
        assertTrue(PartialRun.parseTags("@a and not (@b or @c)").test(tags("@a")));
        assertFalse(PartialRun.parseTags("@a and not (@b or @c)").test(tags("@a", "@c")));
        assertTrue(PartialRun.parseTags("").test(tags()), "an empty expression matches everything");
    }

    @Test
    void aBadTagExpressionIsRefusedWithAReason() {
        assertThrows(TagExpressionError.class, () -> PartialRun.parseTags("@a and"));
        assertThrows(TagExpressionError.class, () -> PartialRun.parseTags("(@a"));
        assertThrows(TagExpressionError.class, () -> PartialRun.parseTags("@a @b"));
        assertThrows(TagExpressionError.class, () -> PartialRun.parseTags("and"));
    }

    @Test
    void aFeatureFileCarriesItsFeatureTagsIntoEveryScenario() throws IOException {
        Path file = root.resolve("PETROCORE-9-TestCase.feature");
        Files.writeString(file, """
                @smoke
                Feature: Tills
                  @fast
                  Scenario: one
                    Given x
                  Scenario: two
                    Given y
                """, StandardCharsets.UTF_8);

        List<Set<String>> scenarios = PartialRun.fileTags(file);
        assertEquals(2, scenarios.size());
        assertTrue(scenarios.get(0).containsAll(Set.of("@smoke", "@fast", "@PETROCORE-9")));
        assertTrue(scenarios.get(1).containsAll(Set.of("@smoke", "@PETROCORE-9")));
        assertFalse(scenarios.get(1).contains("@fast"));
    }

    @Test
    void aPythonLikeCaseFileCarriesOnlyItsKey() {
        assertEquals(List.of(Set.of("@PETROCORE-36847")),
                PartialRun.fileTags(root.resolve("PETROCORE-36847-TestCase.py")));
    }

    @Test
    void fromKeepsThatCaseAndEveryOneAfterItInPlanOrder() {
        Selection selection = PartialRun.select(plan(), "", List.of(), "PETROCORE-3", root);
        assertEquals(List.of("PETROCORE-3", "PETROCORE-4", "PETROCORE-5"), selection.keys());
        assertEquals(List.of("PETROCORE-1", "PETROCORE-2"),
                selection.skippedBefore().stream().map(CaseRef::key).toList());
    }

    @Test
    void onlyKeepsTheNamedCasesInPlanOrderNotInTheOrderNamed() {
        Selection selection = PartialRun.select(plan(), "", List.of("4", "petrocore-2"), "", root);
        assertEquals(List.of("PETROCORE-2", "PETROCORE-4"), selection.keys());
        assertEquals(List.of("PETROCORE-1", "PETROCORE-3"),
                selection.skippedBefore().stream().map(CaseRef::key).toList(),
                "only the cases before the last kept one are reported as skipped");
    }

    @Test
    void aKeyNotInThePlanIsNamedInTheError() {
        SelectionError error = assertThrows(SelectionError.class,
                () -> PartialRun.select(plan(), "", List.of("99"), "", root));
        assertEquals("--only PETROCORE-99: not migrated case(s) of the plan", error.getMessage());
        assertThrows(SelectionError.class, () -> PartialRun.select(plan(), "", List.of(), "77", root));
    }

    @Test
    void aSelectionThatKeepsNothingIsAnError() {
        SelectionError error = assertThrows(SelectionError.class,
                () -> PartialRun.select(plan(), "@nope", List.of("PETROCORE-1"), "", root));
        assertTrue(error.getMessage().endsWith("no case of the plan is selected"), error.getMessage());
    }

    @Test
    void aCaseWithNoNodeIsNotRunAndNotSelected() {
        List<CaseRef> withNotRun = List.of(
                new CaseRef("PETROCORE-1", "tests/a/PETROCORE-1-TestCase.py"),
                new CaseRef("PETROCORE-2", null));
        Selection selection = PartialRun.select(withNotRun, "", List.of(), "", root);
        assertEquals(List.of("PETROCORE-1"), selection.keys());
    }

    @Test
    void tagsFilterTheKeptCasesByTheirOwnFiles() throws IOException {
        Files.createDirectories(root.resolve("tests/a"));
        Files.writeString(root.resolve("tests/a/PETROCORE-1-TestCase.feature"),
                "@smoke\nFeature: One\n  Scenario: x\n    Given y\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tests/a/PETROCORE-2-TestCase.feature"),
                "Feature: Two\n  Scenario: x\n    Given y\n", StandardCharsets.UTF_8);
        List<CaseRef> plan = List.of(
                new CaseRef("PETROCORE-1", "tests/a/PETROCORE-1-TestCase.feature"),
                new CaseRef("PETROCORE-2", "tests/a/PETROCORE-2-TestCase.feature"));

        Selection selection = PartialRun.select(plan, "@smoke", List.of(), "", root);
        assertEquals(List.of("PETROCORE-1"), selection.keys());
    }

    @Test
    void aStatefulPrerequisiteOmittedAheadOfTheSelectionIsNamed() {
        // K1 logs a cashier on, K2 stores a transaction, K3 refunds one, K4 does nothing, K5 refunds.
        // Selecting only K5 leaves K1..K4 out ahead of it.
        List<CaseRef> plan = List.of(
                new CaseRef("K1", "n1"), new CaseRef("K2", "n2"), new CaseRef("K3", "n3"),
                new CaseRef("K4", "n4"), new CaseRef("K5", "n5"));
        Map<String, Set<String>> actions = Map.of(
                "n1", Set.of("LOGIN"),
                "n2", Set.of("STORE_TRANS_MAP"),
                "n3", Set.of("REFUND_WITH_TRANS_MAP"),
                "n4", Set.of("REPORT_THING"),
                "n5", Set.of("REFUND_WITH_TRANS_MAP"));
        Selection selection = PartialRun.select(plan, "", List.of("K5"), "", root);

        PartialRun.Prerequisites found = PartialRun.prerequisiteCases(
                selection, node -> Optional.ofNullable(actions.get(node)));
        assertEquals(List.of("K1", "K2"), found.cases().stream().map(PartialRun.Prerequisite::key).toList());
        assertEquals(List.of("K2"), found.stores());
        assertEquals(List.of("K5"), found.refunds());
        assertEquals("K5", found.cases().get(0).neededBy(), "the next selected case after K1");
        assertEquals("K5", found.cases().get(1).neededBy(), "the refund that needs the stored transaction");
        assertEquals(0, found.unreadable());
    }

    @Test
    void theWarningsNameTheOmittedCaseTheSelectionCountAndAnUnreadableOne() {
        List<CaseRef> plan = List.of(
                new CaseRef("PETROCORE-1", "n1"),
                new CaseRef("PETROCORE-2", "n2"),
                new CaseRef("PETROCORE-3", "n3"));
        Map<String, Set<String>> actions = Map.of("n1", Set.of("LOGIN"), "n3", Set.of("NOTHING"));
        Selection selection = PartialRun.select(plan, "", List.of("PETROCORE-3"), "", root);

        List<String> lines = PartialRun.prerequisiteWarnings(
                selection, node -> Optional.ofNullable(actions.get(node)), node -> node.equals("n3"));
        assertTrue(lines.get(0).startsWith("partial run: 1 case(s) selected;"), lines.get(0));
        assertEquals("prerequisite not run: PETROCORE-1 (n1) signs a cashier on", lines.get(1));
        assertEquals("1 case(s) left out ahead of the selection have no PATS actions CAT can read; "
                + "what they change is not known", lines.get(2));
        assertTrue(lines.get(3).startsWith("PETROCORE-3 is a @plan_end case"), lines.get(3));
    }
}
