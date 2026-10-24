package com.verifone.cat.setup;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;

/**
 * The workstation audit: every {@link SetupItem} detected once, in registration order, and the
 * results kept by name. Ported from the Python CAT's {@code cat.setup.audit.SetupAudit}, minus its
 * repair pass and its live sign-ins (both change the machine or reach the network, and neither is
 * done here).
 *
 * <p>Not thread-safe. The web service serialises calls to it.
 */
public final class SetupAudit {

    private final List<SetupItem> items;
    private final SetupEnv env;
    private final Map<String, Result> results = new LinkedHashMap<>();

    public SetupAudit(List<SetupItem> items, SetupEnv env) {
        this.env = Objects.requireNonNull(env, "env");
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        Set<String> seen = new HashSet<>();
        for (SetupItem item : this.items) {
            if (!seen.add(item.name())) {
                throw new IllegalArgumentException("duplicate setup item name '" + item.name() + "'");
            }
        }
    }

    /** The standard audit: {@link SetupItems#standard()} over {@code env}. */
    public static SetupAudit standard(SetupEnv env) {
        return new SetupAudit(SetupItems.standard(), env);
    }

    /** The items, in the order they were registered. */
    public List<SetupItem> items() {
        return items;
    }

    /** The item called {@code name}, or null. */
    public SetupItem item(String name) {
        for (SetupItem item : items) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        return null;
    }

    /** Detects every item, replacing the stored results. Returns them, in order. */
    public Map<String, Result> run() {
        results.clear();
        for (SetupItem item : items) {
            results.put(item.name(), item.detect(env));
        }
        return results();
    }

    /** The results of the last {@link #run()}, in order. Empty before the first run. */
    public Map<String, Result> results() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }

    /** The results that are not PASS: what a person has to look at. */
    public Map<String, Result> failures() {
        Map<String, Result> out = new LinkedHashMap<>();
        results.forEach((name, result) -> {
            if (result.outcome() != Outcome.PASS) {
                out.put(name, result);
            }
        });
        return out;
    }

    /** The failing results whose items are required. */
    public Map<String, Result> missingRequired() {
        Map<String, Result> out = new LinkedHashMap<>();
        failures().forEach((name, result) -> {
            SetupItem item = item(name);
            if (item != null && item.required()) {
                out.put(name, result);
            }
        });
        return out;
    }

    /** The failing results whose items are sign-ins the operator types. */
    public Map<String, Result> missingCredentials() {
        Map<String, Result> out = new LinkedHashMap<>();
        failures().forEach((name, result) -> {
            SetupItem item = item(name);
            if (item != null && item.credential()) {
                out.put(name, result);
            }
        });
        return out;
    }

    /** True for a sign-in the operator types (the Jira and Verifone logins). */
    public static boolean isCredential(SetupItem item) {
        return item.credential();
    }

    /**
     * Detects only the credential items, now: {@code name -> failing result}, empty when every
     * sign-in is in place. Fresh each call, so a sign-in saved a moment ago counts. Local reads only.
     */
    public static Map<String, Result> credentialGaps(List<SetupItem> items, SetupEnv env) {
        Map<String, Result> gaps = new LinkedHashMap<>();
        for (SetupItem item : items) {
            if (!isCredential(item)) {
                continue;
            }
            Result result = item.detect(env);
            if (result.outcome() != Outcome.PASS) {
                gaps.put(item.name(), result);
            }
        }
        return gaps;
    }

    /** The environment this audit reads. */
    public SetupEnv env() {
        return env;
    }
}
