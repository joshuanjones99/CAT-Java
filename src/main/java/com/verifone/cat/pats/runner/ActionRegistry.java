package com.verifone.cat.pats.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The action-name to handler registry, and the hooks a run takes around every case: each PATS action name,
 * upper-cased, maps to the one handler that does it. Ported from the Python {@code registry._HANDLERS}, but an
 * instance rather than a module global, so a run and its tests each hold their own.
 *
 * <p>The hooks are the marks PATS's runner takes around its actions, which the handler families need and which
 * a handler cannot take for itself: a {@link BeforeActionHook} runs before every action's handler, a
 * {@link BeginHook} once as a case begins, a {@link FinishHook} once as it ends. They run in registration order.
 *
 * <p>No production handler is registered here. The handler families (POS UI, sale, HTTP, configuration,
 * validation, inits, devices, cloud settle) register themselves in later waves; a name registered twice is
 * a wiring error and is refused.
 */
public final class ActionRegistry {

    /**
     * Runs before an action's handler, inside the action's own failure handling: a hook that throws is a step
     * failure, as the handler's own throw is. The Python's reply pause, overlay sweep and print marks are these.
     */
    @FunctionalInterface
    public interface BeforeActionHook {
        void before(CaseRun run, RunnableAction action) throws Exception;
    }

    /** Runs once as a case begins, after its log line: the Python's TLog and DCR marks. */
    @FunctionalInterface
    public interface BeginHook {
        void begin(CaseRun run);
    }

    /**
     * Runs at the start of a case's verdict, before any failure is raised, whether or not the case failed: the
     * Python's dead-end OK press checks {@link CaseRun#softFailures()} itself, as it only acts after a failure.
     */
    @FunctionalInterface
    public interface FinishHook {
        void finish(CaseRun run);
    }

    private final Map<String, ActionHandler> handlers = new TreeMap<>();
    private final List<BeforeActionHook> beforeActions = new ArrayList<>();
    private final List<BeginHook> begins = new ArrayList<>();
    private final List<FinishHook> finishes = new ArrayList<>();

    /**
     * Registers {@code handler} for the action {@code name}.
     *
     * @throws IllegalStateException when the name already has a handler
     */
    public void register(String name, ActionHandler handler) {
        String key = normalise(name);
        if (handlers.putIfAbsent(key, Objects.requireNonNull(handler, "handler")) != null) {
            throw new IllegalStateException("two handlers for PATS action " + key);
        }
    }

    /** Adds a hook that runs before every action's handler, after the earlier-registered ones. */
    public void beforeAction(BeforeActionHook hook) {
        beforeActions.add(Objects.requireNonNull(hook, "hook"));
    }

    /** Adds a hook that runs as every case begins. */
    public void onBegin(BeginHook hook) {
        begins.add(Objects.requireNonNull(hook, "hook"));
    }

    /** Adds a hook that runs as every case's verdict is taken. */
    public void onFinish(FinishHook hook) {
        finishes.add(Objects.requireNonNull(hook, "hook"));
    }

    /** The handler for an action name (matched upper-cased), or empty when none is registered. */
    public Optional<ActionHandler> handlerFor(String name) {
        return Optional.ofNullable(handlers.get(normalise(name)));
    }

    /** Every action name with a handler, upper-cased. */
    public Set<String> supportedActions() {
        return Collections.unmodifiableSet(new TreeSet<>(handlers.keySet()));
    }

    /** The before-action hooks, in registration order. */
    List<BeforeActionHook> beforeActionHooks() {
        return List.copyOf(beforeActions);
    }

    /** The begin hooks, in registration order. */
    List<BeginHook> beginHooks() {
        return List.copyOf(begins);
    }

    /** The finish hooks, in registration order. */
    List<FinishHook> finishHooks() {
        return List.copyOf(finishes);
    }

    private static String normalise(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
