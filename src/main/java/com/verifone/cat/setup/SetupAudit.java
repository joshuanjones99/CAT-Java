package com.verifone.cat.setup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;

/**
 * The workstation audit and its repair: the Python CAT's {@code cat.setup.audit.SetupAudit}.
 *
 * <p>{@link #runAndRepair()} detects every {@link SetupItem} in registration order, then immediately applies the
 * repair of every automatable item ({@code manual == false}) whose probe FAILed, with no confirmation and no
 * per-item button, as the Python does on every start. What is left in {@link #stillNeedsAttention()} is what a person
 * has to act on. {@link #run()} is the detect-only pass System Checks makes.
 *
 * <p>A saved sign-in passes {@code detect()} (a local read), so a wrong password looked signed in until
 * {@link #recordLive} kept a failed {@code check_live()}: {@link #rejected()} and {@link #signedIn()} hold those.
 *
 * <p>Not thread-safe. The web service serialises calls to it.
 */
public final class SetupAudit {

    private final List<SetupItem> items;
    private final SetupEnv env;
    private final Map<String, Result> results = new LinkedHashMap<>();
    private final Set<String> rejected = new LinkedHashSet<>();
    private final Set<String> signedIn = new LinkedHashSet<>();

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

    private SetupItem require(String name) {
        SetupItem item = item(name);
        if (item == null) {
            List<String> known = items.stream().map(SetupItem::name).toList();
            throw new IllegalArgumentException("no setup item named '" + name + "'; known: " + String.join(", ", known));
        }
        return item;
    }

    /** Detects every item, replacing the stored results. Returns them, in order. The Python's {@code audit_all}. */
    public Map<String, Result> run() {
        results.clear();
        for (SetupItem item : items) {
            long start = System.nanoTime();
            Result result = item.detect(env);
            results.put(item.name(), result.withElapsed(Duration.ofNanos(System.nanoTime() - start)));
        }
        return results();
    }

    /**
     * Detects everything, then fixes whatever an automatable item's repair can fix: the single entry point of the
     * Setup page's check and {@code cat-java setup}. Returns the repairs made, by name.
     */
    public Map<String, Result> runAndRepair() {
        run();
        return applyAllAutomatable();
    }

    /** Applies every automatable item whose last result is FAIL. Returns them, by name. */
    public Map<String, Result> applyAllAutomatable() {
        Map<String, Result> applied = new LinkedHashMap<>();
        for (SetupItem item : items) {
            if (item.manual()) {
                continue;
            }
            Result current = results.get(item.name());
            if (current != null && current.outcome() != Outcome.FAIL) {
                continue;
            }
            applied.put(item.name(), applyItem(item.name()));
        }
        return applied;
    }

    /** Runs one item's repair and keeps its result, marked as a repair. */
    public Result applyItem(String name) {
        SetupItem item = require(name);
        long start = System.nanoTime();
        Result result = item.apply(env).withElapsed(Duration.ofNanos(System.nanoTime() - start)).asRepair();
        results.put(name, result);
        return result;
    }

    /**
     * Saves what a person typed into one item's sign-in card and keeps the result, marked as a repair. An item with no
     * card is a caller's bug, not a row: it throws.
     */
    public Result applyManualItem(String name, Map<String, String> values) {
        SetupItem item = require(name);
        if (item.saver() == null) {
            throw new IllegalArgumentException("setup item '" + name + "' takes no typed values");
        }
        long start = System.nanoTime();
        Result result;
        try {
            result = item.saver().save(env, values == null ? Map.of() : values);
            if (result == null) {
                result = Result.error(name, "the save returned no result");
            }
        } catch (Exception e) {
            result = Result.error(name, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result = result.withElapsed(Duration.ofNanos(System.nanoTime() - start)).asRepair();
        results.put(name, result);
        return result;
    }

    /**
     * Keeps a credential's live sign-in as its result: a failed one replaces the PASS the saved file earned, so the
     * item is back in {@link #stillNeedsAttention()} and {@link #missingCredentials()}; a good one clears it.
     */
    public void recordLive(String name, Result result) {
        results.put(name, result);
        if (result.outcome() == Outcome.PASS) {
            rejected.remove(name);
            signedIn.add(name);
        } else {
            rejected.add(name);
            signedIn.remove(name);
        }
    }

    /**
     * {@code check_live()} each saved credential (or only {@code names}), kept with {@link #recordLive}: the Python's
     * {@code check_saved_logins}. A login that is not saved is left to its detect; a check that throws counts as
     * failed. Network calls: run off the request thread.
     */
    public void checkSavedLogins(Set<String> names) {
        for (String name : credentialNames()) {
            if (names != null && !names.contains(name)) {
                continue;
            }
            Result result = results.get(name);
            SetupItem item = item(name);
            if (result == null || !result.ok() || item == null || item.liveCheck() == null) {
                continue;
            }
            recordLive(name, liveCheck(item));
        }
    }

    /** One item's live sign-in, never throwing: a throw is a failed sign-in. */
    public Result liveCheck(SetupItem item) {
        try {
            Result live = item.liveCheck().check(env);
            return live == null ? Result.failed(item.name(), "sign-in check returned nothing") : live;
        } catch (Exception e) {
            return Result.failed(item.name(), "sign-in check failed: " + e.getMessage());
        }
    }

    /** Saved credentials whose live sign-in failed, in the order they were recorded. */
    public Set<String> rejected() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(rejected));
    }

    /** Credentials whose live sign-in passed this session. */
    public Set<String> signedIn() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(signedIn));
    }

    /** The results of the last pass, in order. Empty before the first. */
    public Map<String, Result> results() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }

    /** Replaces one item's result with a fresh detect (after a download staged what it looks for). */
    public Result redetect(String name) {
        SetupItem item = require(name);
        Result result = item.detect(env);
        results.put(name, result);
        return result;
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

    /** What is left after the repair pass: the Python's {@code still_needs_attention} (its {@code failures}). */
    public Map<String, Result> stillNeedsAttention() {
        return failures();
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

    /** Every credential item, passing or not: what the Setup page lets you re-enter. */
    public List<String> credentialNames() {
        List<String> out = new ArrayList<>();
        for (SetupItem item : items) {
            if (item.credential()) {
                out.add(item.name());
            }
        }
        return out;
    }

    /** True for a sign-in the operator types (the Atlassian and Verifone logins). */
    public static boolean isCredential(SetupItem item) {
        return item.credential();
    }

    /**
     * Detects only the credential items, now: {@code name -> failing result}, empty when every sign-in is in place.
     * Fresh each call, so a sign-in saved a moment ago counts. Local reads only.
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
