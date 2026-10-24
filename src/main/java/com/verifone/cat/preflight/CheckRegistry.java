package com.verifone.cat.preflight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered, name-unique collection of checks. Ported from
 * {@code petrotest.preflight.registry.CheckRegistry}.
 *
 * <p>Not a process-wide singleton: the Python registry is a module-level object populated at
 * import, and a Java port gets the same shape by passing the set in. {@link DefaultChecks}
 * builds the default one.
 */
public final class CheckRegistry {

    private final Map<String, Check> checks = new LinkedHashMap<>();

    public CheckRegistry(Collection<? extends Check> checks) {
        for (Check check : checks) {
            register(check);
        }
    }

    public Check register(Check check) {
        if (check.name() == null || check.name().isEmpty()) {
            throw new IllegalArgumentException("a check must have a name (DESIGN.md 6.2)");
        }
        if (checks.containsKey(check.name())) {
            throw new IllegalArgumentException("duplicate check name '" + check.name() + "'");
        }
        checks.put(check.name(), check);
        return check;
    }

    public Check get(String name) {
        Check check = checks.get(name);
        if (check == null) {
            throw new IllegalArgumentException("no preflight check named '" + name + "'; known: " + names());
        }
        return check;
    }

    /** The named checks, in the order asked for. */
    public List<Check> select(Collection<String> names) {
        List<Check> selected = new ArrayList<>();
        for (String name : names) {
            selected.add(get(name));
        }
        return selected;
    }

    public List<String> names() {
        return List.copyOf(checks.keySet());
    }

    public List<Check> checks() {
        return List.copyOf(checks.values());
    }

    public boolean contains(String name) {
        return checks.containsKey(name);
    }

    public int size() {
        return checks.size();
    }
}
