package com.verifone.cat.pats.runner.handlers;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.report.CaseRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Drives a handler family through the real {@link CaseRun}, with the actions built here. The runner's own test
 * helpers are package-private to {@code pats.runner}, so this is the handler tests' copy of them.
 *
 * <p>{@link #registerTestHandlers} adds the handlers a test uses to set up a case's scratch (what an earlier action
 * would have left): {@code SET_FUEL_PRESET}, {@code SET_PREPAY}, and {@code CARRY} (a SLEEP, carrying {@code SECONDS}).
 */
public final class HandlerHarness {

    /** The key every harness case has. */
    public static final String KEY = "PETROCORE-1";

    private HandlerHarness() {
    }

    /** An action at {@code index} with {@code name} and {@code name, value, name, value, ...} fields. */
    public static RunnableAction act(int index, String name, String... nameValuePairs) {
        List<RunnableAction.ActionField> fields = new ArrayList<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            fields.add(new RunnableAction.ActionField(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return new Act(name, index, List.copyOf(fields));
    }

    /** A case run of {@code actions} in order against {@code registry}, with {@code declared} fixtures. */
    public static CaseRun caseRun(ActionRegistry registry, Path folder, Set<FixtureName> declared,
            RunnableAction... actions) {
        CaseRecord record = new CaseRecord("test::" + KEY, KEY, folder.resolve(KEY), Instant.now());
        return new CaseRun(new Kase(List.of(actions)), record, registry,
                name -> {
                    throw new IllegalStateException("no fixture " + name.key() + " in the harness");
                },
                declared, new RunState());
    }

    /** The test-only set-up handlers, documented on the class. */
    public static void registerTestHandlers(ActionRegistry registry) {
        registry.register("SET_FUEL_PRESET", (run, action) -> run.setScratch(ScratchKey.FUEL_PRESET, true));
        registry.register("SET_PREPAY", (run, action) -> run.setScratch(ScratchKey.PREPAY, true));
        registry.register("CARRY", (run, action) -> run.addCarry(Double.parseDouble(action.get("SECONDS"))));
    }

    private record Act(String name, int index, List<RunnableAction.ActionField> fields) implements RunnableAction {
    }

    private record Kase(List<RunnableAction> actions) implements RunnableCase {

        @Override
        public String key() {
            return KEY;
        }

        @Override
        public String title() {
            return "a handler test case";
        }

        @Override
        public String source() {
            return "memory";
        }
    }
}
