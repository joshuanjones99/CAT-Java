package com.verifone.cat.pats.runner;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Objects;

/**
 * A typed key into a case's scratch state ({@link CaseRun#scratch}): what one action leaves for a later action
 * of the same case. Keys compare by identity, so a family's key cannot collide with another's even when the name
 * matches. The Python keeps these as {@code CaseRun} attributes; the four below are the ones the Python's core
 * carries, and a family declares any other it needs with {@link #of}.
 *
 * @param <T> the value's type
 */
public final class ScratchKey<T> {

    /** Whether the case's prepay (a {@code PREPAY} or {@code PREPAY_FUEL_*} action) is in progress. */
    public static final ScratchKey<Boolean> PREPAY = of("prepay", Boolean.class);

    /** Whether the case pressed the POS's Fuel Preset and has not lifted a pump since. */
    public static final ScratchKey<Boolean> FUEL_PRESET = of("fuel-preset", Boolean.class);

    /** The key of the transaction-number map a {@code STORE_TRANS_MAP} writes and a refund reads. */
    public static final ScratchKey<String> TRANS_MAP_KEY = of("trans-map-key", String.class);

    /**
     * The case's scenario context: what {@code captureFields} captured and the {@code {name}} tokens the HTTP and
     * configuration steps fill from. Every family reads this one map.
     */
    @SuppressWarnings("unchecked")
    public static final ScratchKey<Map<String, String>> SCENARIO_CONTEXT =
            new ScratchKey<>("scenario-context", (Class<Map<String, String>>) (Class<?>) Map.class);

    /** The transaction-number map itself: the stored key to the (first, second) values it held. */
    @SuppressWarnings("unchecked")
    public static final ScratchKey<Map<String, AbstractMap.SimpleImmutableEntry<String, String>>> TRANS_MAP =
            new ScratchKey<>("trans-map", (Class<Map<String, AbstractMap.SimpleImmutableEntry<String, String>>>)
                    (Class<?>) Map.class);

    private final String name;
    private final Class<T> type;

    private ScratchKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /** A key named {@code name} for values of {@code type}. Two keys with the same name are still two keys. */
    public static <T> ScratchKey<T> of(String name, Class<T> type) {
        return new ScratchKey<>(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"));
    }

    /** The key's name, for a log line or an error. */
    public String name() {
        return name;
    }

    /** The type a value under this key must have. */
    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "ScratchKey(" + name + ")";
    }
}
