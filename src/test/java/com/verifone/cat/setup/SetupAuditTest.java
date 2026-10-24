package com.verifone.cat.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;

/** The audit over synthetic items, so no test depends on what this workstation happens to have. */
class SetupAuditTest {

    @TempDir
    Path root;

    private static SetupItem item(String name, boolean required, boolean credential, Outcome outcome) {
        return new SetupItem(name, null, false, required, credential, "fix " + name, env -> switch (outcome) {
            case PASS -> Result.passed(name, "ok " + name);
            default -> new Result(name, outcome, "bad " + name);
        });
    }

    @Test
    void aProbeThatThrowsCostsOnlyItsOwnRow() throws Exception {
        SetupFixture f = new SetupFixture(root);
        SetupItem boom = new SetupItem("boom", "Boom", false, true, false, "fix", env -> {
            throw new IllegalStateException("probe broke");
        });
        SetupAudit audit = new SetupAudit(List.of(boom, item("fine", true, false, Outcome.PASS)), f.env);

        Map<String, Result> results = audit.run();

        assertEquals(Outcome.ERROR, results.get("boom").outcome());
        assertTrue(results.get("boom").detail().contains("probe broke"), results.get("boom").detail());
        assertEquals(Outcome.PASS, results.get("fine").outcome());
    }

    @Test
    void aNullResultIsAnErrorRow() throws Exception {
        SetupFixture f = new SetupFixture(root);
        SetupItem nothing = new SetupItem("nothing", null, false, false, false, "fix", env -> null);

        assertEquals(Outcome.ERROR, nothing.detect(f.env).outcome());
    }

    @Test
    void failuresAndTheRequiredAndCredentialSubsetsAreSplitOut() throws Exception {
        SetupFixture f = new SetupFixture(root);
        SetupAudit audit = new SetupAudit(List.of(
                item("req", true, false, Outcome.FAIL),
                item("opt", false, false, Outcome.FAIL),
                item("login", false, true, Outcome.FAIL),
                item("good", true, false, Outcome.PASS)), f.env);

        audit.run();

        assertEquals(List.of("req", "opt", "login"), List.copyOf(audit.failures().keySet()));
        assertEquals(List.of("req"), List.copyOf(audit.missingRequired().keySet()));
        assertEquals(List.of("login"), List.copyOf(audit.missingCredentials().keySet()));
    }

    @Test
    void credentialGapsCountsOnlyFailingSignIns() throws Exception {
        SetupFixture f = new SetupFixture(root);
        List<SetupItem> items = List.of(
                item("login", false, true, Outcome.FAIL),
                item("other", true, false, Outcome.FAIL),
                item("signed", false, true, Outcome.PASS));

        Map<String, Result> gaps = SetupAudit.credentialGaps(items, f.env);

        assertEquals(List.of("login"), List.copyOf(gaps.keySet()));
        assertFalse(gaps.containsKey("other"));
    }

    @Test
    void resultsAreEmptyBeforeTheFirstRunAndReplacedByEachRun() throws Exception {
        SetupFixture f = new SetupFixture(root);
        SetupAudit audit = new SetupAudit(List.of(item("one", true, false, Outcome.FAIL)), f.env);

        assertTrue(audit.results().isEmpty());
        audit.run();
        assertEquals(Outcome.FAIL, audit.results().get("one").outcome());
    }

    @Test
    void duplicateNamesAreRefused() throws Exception {
        SetupFixture f = new SetupFixture(root);
        assertThrows(IllegalArgumentException.class, () -> new SetupAudit(List.of(
                item("same", true, false, Outcome.PASS),
                item("same", false, false, Outcome.PASS)), f.env));
    }

    @Test
    void theStandardAuditIsBuiltFromTheStandardList() throws Exception {
        SetupFixture f = new SetupFixture(root);
        SetupAudit audit = SetupAudit.standard(f.env);

        assertEquals(SetupItems.standard().stream().map(SetupItem::name).toList(),
                audit.items().stream().map(SetupItem::name).toList());
        assertEquals(SetupItems.JAVA_RUNTIME, audit.items().get(0).name());
    }
}
