package com.verifone.cat.pats.runner;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The action-name to handler registry: each PATS action name, upper-cased, maps to the one handler
 * that does it. Ported from the Python {@code registry._HANDLERS}, but an instance rather than a module
 * global, so a run and its tests each hold their own.
 *
 * <p>No production handler is registered here. The handler families (POS UI, sale, HTTP, configuration,
 * validation, inits, devices, cloud settle) register themselves in later waves; a name registered twice is
 * a wiring error and is refused.
 */
public final class ActionRegistry {

    private final Map<String, ActionHandler> handlers = new TreeMap<>();

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

    /** The handler for an action name (matched upper-cased), or empty when none is registered. */
    public Optional<ActionHandler> handlerFor(String name) {
        return Optional.ofNullable(handlers.get(normalise(name)));
    }

    /** Every action name with a handler, upper-cased. */
    public Set<String> supportedActions() {
        return Collections.unmodifiableSet(new TreeSet<>(handlers.keySet()));
    }

    private static String normalise(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
