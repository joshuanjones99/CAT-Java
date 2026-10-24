package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.Map;

import com.verifone.cat.rig.Durations;
import com.verifone.cat.rig.Rig;

/**
 * The bounded-wait defaults the recovery ladder and the device clients take their budgets from (DESIGN.md 8, 6.3).
 * Ported from the Python {@code petrotest.recovery.timeouts.Timeouts}.
 *
 * <p>Every value goes through {@link Durations#parse}, the one duration grammar. {@code ssh} and {@code http} are not
 * in the rig file's {@code timeouts:} block, so they default as the Python does: {@code ssh} is
 * {@code max(default_wait, 30s)} and {@code http} is 15 s.
 *
 * <ul>
 *   <li>{@code defaultWait}: the ladder's and the generic waits' budget;</li>
 *   <li>{@code ssh}: one SSH operation;</li>
 *   <li>{@code promptWait}: a DCR or ViperPay prompt, or a receipt;</li>
 *   <li>{@code fuelComplete}: a pump reaching its state;</li>
 *   <li>{@code http}: one request, login or socket read on a simulator.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record Timeouts(Duration defaultWait, Duration ssh, Duration promptWait, Duration fuelComplete,
        Duration http) {

    static final Duration FALLBACK_DEFAULT_WAIT = Duration.ofSeconds(15);
    static final Duration FALLBACK_SSH = Duration.ofSeconds(30);
    static final Duration FALLBACK_PROMPT_WAIT = Duration.ofSeconds(30);
    static final Duration FALLBACK_FUEL_COMPLETE = Duration.ofSeconds(90);
    static final Duration FALLBACK_HTTP = Duration.ofSeconds(15);

    public Timeouts {
        defaultWait = defaultWait == null ? FALLBACK_DEFAULT_WAIT : defaultWait;
        ssh = ssh == null ? max(defaultWait, FALLBACK_SSH) : ssh;
        promptWait = promptWait == null ? FALLBACK_PROMPT_WAIT : promptWait;
        fuelComplete = fuelComplete == null ? FALLBACK_FUEL_COMPLETE : fuelComplete;
        http = http == null ? FALLBACK_HTTP : http;
        requirePositive("default_wait", defaultWait);
        requirePositive("ssh", ssh);
        requirePositive("prompt_wait", promptWait);
        requirePositive("fuel_complete", fuelComplete);
        requirePositive("http", http);
    }

    /** The two-field form the recovery ladder was written against: the rest take their defaults. */
    public Timeouts(Duration defaultWait, Duration ssh) {
        this(defaultWait, ssh, null, null, null);
    }

    /** The defaults, for code that has no rig loaded yet. */
    public static Timeouts defaults() {
        return new Timeouts(null, null, null, null, null);
    }

    /** The rig file's {@code timeouts:} block, with the defaults for what it does not name. */
    public static Timeouts of(Rig.Timeouts block) {
        return block == null ? defaults()
                : new Timeouts(block.defaultWait(), null, block.promptWait(), block.fuelComplete(), null);
    }

    /**
     * From a raw {@code timeouts:} mapping such as the YAML loader produces. Missing keys take their defaults; an
     * unparseable value is refused by {@link Durations#parse}.
     */
    public static Timeouts parse(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return defaults();
        }
        return new Timeouts(
                duration(raw, "default_wait"),
                duration(raw, "ssh"),
                duration(raw, "prompt_wait"),
                duration(raw, "fuel_complete"),
                duration(raw, "http"));
    }

    private static Duration duration(Map<String, ?> raw, String key) {
        return raw.containsKey(key) ? Durations.parse(raw.get(key)) : null;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static void requirePositive(String key, Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeouts." + key + " must be positive: a zero or negative "
                    + "timeout means 'wait forever', which DESIGN.md 6.3 forbids");
        }
    }
}
