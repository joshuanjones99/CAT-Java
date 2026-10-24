package com.verifone.cat.runconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The Run scope's rules on in-memory facts: what each scope selects, and what it adds. */
class ScopeResolverTest {

    private static final Path ROOT = Path.of(".");
    private static final String IMPORTER = "PETROCORE-1";
    private static final String PLAIN = "PETROCORE-2";
    private static final String FUEL = "PETROCORE-3";

    private static final List<ScopeCase> CASES = List.of(
            new ScopeCase(IMPORTER, "node/1.xml", "Import"),
            new ScopeCase(PLAIN, "node/2.xml", "Plain"),
            new ScopeCase(FUEL, "node/3.feature", "Fuel"));

    private static final Map<String, CaseFacts> FACTS = Map.of(
            "node/1.xml", new CaseFacts(List.of(Set.of("@PETROCORE-1")),
                    Set.of("IMPORT_CONFIGURATION", "LOGIN"), false),
            "node/2.xml", new CaseFacts(List.of(Set.of("@PETROCORE-2")), Set.of("SALE"), false),
            "node/3.feature", new CaseFacts(List.of(Set.of("@fuel", "@PETROCORE-3")), null, true));

    private static RunScope scope(RunScope.Mode mode, String tags, List<String> cases, List<String> tagCases,
            String from, boolean prerequisites) {
        return new RunScope(mode, tags, cases, tagCases, from, prerequisites);
    }

    @Test
    void wholePlanRunsEverythingAndNamesNoProblem() {
        ScopeResolver.Result result = ScopeResolver.resolve(RunScope.whole(), CASES, FACTS, ROOT);
        assertEquals(List.of(IMPORTER, PLAIN, FUEL), result.runKeys());
        assertEquals("", result.problem());
    }

    @Test
    void casesRunOnlyTheTickedCasesInPlanOrder() {
        RunScope picked = scope(RunScope.Mode.CASES, "", List.of(FUEL, PLAIN), null, "", false);
        ScopeResolver.Result result = ScopeResolver.resolve(picked, CASES, FACTS, ROOT);
        assertEquals(List.of(PLAIN, FUEL), result.chosen());
        assertEquals(List.of(PLAIN, FUEL), result.runKeys());
        assertEquals("", result.problem());
    }

    @Test
    void casesWithNothingTickedIsRefused() {
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.CASES, "", List.of(), null, "", true), CASES, FACTS, ROOT);
        assertEquals("a run scope that selects a case (Cases: no case is ticked)", result.problem());
        assertTrue(result.runKeys().isEmpty());
    }

    @Test
    void fromACaseRunsItAndEverythingAfterIt() {
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.FROM, "", List.of(), null, "2", true), CASES, FACTS, ROOT);
        assertEquals(List.of(PLAIN, FUEL), result.chosen());
        assertEquals(List.of(IMPORTER, PLAIN, FUEL), result.runKeys(), "the importer is added back");
        assertEquals(1, result.added().size());
        assertEquals(IMPORTER, result.added().get(0).key());
        assertEquals(PLAIN, result.added().get(0).neededBy());
    }

    @Test
    void fromAnUnknownCaseIsRefused() {
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.FROM, "", List.of(), null, "", true), CASES, FACTS, ROOT);
        assertEquals("a case to start from (From a case: no case is picked)", result.problem());
    }

    @Test
    void tagsSelectTheMatchingCases() {
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.TAGS, " @fuel ", List.of(), null, "", false), CASES, FACTS, ROOT);
        assertEquals("@fuel", result.expression());
        assertEquals(List.of(FUEL), result.matched());
        assertEquals(List.of(FUEL), result.chosen());
        assertEquals(List.of(FUEL), result.planEnd(), "the @plan_end case is judged, and skipped, in a partial run");
    }

    @Test
    void aTagExpressionThatDoesNotParseIsNamed() {
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.TAGS, "@fuel and (", List.of(), null, "", true), CASES, FACTS, ROOT);
        assertFalse(result.tagError().isEmpty());
        assertTrue(result.problem().startsWith("a tag expression Cucumber can parse ("), result.problem());
    }

    @Test
    void anEmptyExpressionIsRefused() {
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.TAGS, "  ", List.of(), null, "", true), CASES, FACTS, ROOT);
        assertEquals("a tag expression for the run scope (Tags is chosen; it is empty)", result.problem());
    }

    @Test
    void aPrerequisiteLeftOutIsCountedAsUnreadableWhenItsActionsAreUnknown() {
        Map<String, CaseFacts> unknown = Map.of(
                "node/1.xml", CaseFacts.unknown(),
                "node/2.xml", FACTS.get("node/2.xml"),
                "node/3.feature", FACTS.get("node/3.feature"));
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.CASES, "", List.of(PLAIN), null, "", true), CASES, unknown, ROOT);
        assertTrue(result.added().isEmpty());
        assertEquals(1, result.unreadable());
    }

    @Test
    void withPrerequisitesOffTheLeftOutCaseIsReportedNotRun() {
        ScopeResolver.Result result = ScopeResolver.resolve(
                scope(RunScope.Mode.CASES, "", List.of(PLAIN), null, "", false), CASES, FACTS, ROOT);
        assertEquals(List.of(IMPORTER), result.leftOut().stream().map(p -> p.key()).toList());
        assertTrue(result.added().isEmpty());
        assertEquals(List.of(PLAIN), result.runKeys());
    }
}
