package com.verifone.cat.pats.runner.handlers.inits;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.report.CaseRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Test-only PATS actions and cases, built in code: no parser, no petroapps. Shared by the handler families' tests.
 *
 * <p>Test support only.
 */
public final class TestActions {

    private TestActions() {
    }

    /** An action: its name, its place in the case, and its fields in document order. */
    public record Act(String name, int index, List<RunnableAction.ActionField> fields) implements RunnableAction {
    }

    /** A case: its key, its title and its actions. */
    public record Kase(String key, List<RunnableAction> actions) implements RunnableCase {

        @Override
        public String title() {
            return "a test case";
        }

        @Override
        public String source() {
            return "memory";
        }
    }

    /** An action at {@code index} named {@code name}, with {@code name, value, name, value, ...} fields. */
    public static Act act(int index, String name, String... nameValuePairs) {
        List<RunnableAction.ActionField> fields = new ArrayList<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            fields.add(new RunnableAction.ActionField(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return new Act(name, index, List.copyOf(fields));
    }

    /** A case of {@code actions}, numbered from 0 in the order given. */
    public static Kase kase(String key, RunnableAction... actions) {
        return new Kase(key, List.of(actions));
    }

    /**
     * A run of {@code kase} with {@code registry}, the fixtures {@code fixtures} supplies, and the fixtures named
     * {@code declared}. The record is in memory: nothing is written.
     */
    public static CaseRun run(RunnableCase kase, ActionRegistry registry, FixtureSource fixtures,
            Set<FixtureName> declared) {
        CaseRecord record = new CaseRecord("test::" + kase.key(), kase.key(), Path.of(kase.key()), Instant.EPOCH);
        return new CaseRun(kase, record, registry, fixtures, declared, new RunState());
    }
}
