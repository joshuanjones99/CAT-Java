package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.Map;

import com.verifone.cat.rig.Durations;
import com.verifone.cat.rig.Rig;

/**
 * The bounded-wait defaults the recovery ladder takes its budgets from (DESIGN.md 8, 6.3). Ported
 * from the Python {@code petrotest.recovery.timeouts.Timeouts}, which keeps the same two fields
 * the ladder reads.
 *
 * <p>Every value goes through {@link Durations#parse}, the one duration grammar. {@code ssh} is
 * not in the rig file's {@code timeouts:} block, so it defaults as the Python does:
 * {@code max(default_wait, 30s)}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record Timeouts(Duration defaultWait, Duration ssh) {

    static final Duration FALLBACK_DEFAULT_WAIT = Duration.ofSeconds(15);
    static final Duration FALLBACK_SSH = Duration.ofSeconds(30);

    public Timeouts {
        defaultWait = defaultWait == null ? FALLBACK_DEFAULT_WAIT : defaultWait;
        ssh = ssh == null ? max(defaultWait, FALLBACK_SSH) : ssh;
        requirePositive("default_wait", defaultWait);
        requirePositive("ssh", ssh);
    }

    /** The defaults, for code that has no rig loaded yet. */
    public static Timeouts defaults() {
        return new Timeouts(null, null);
    }

    /** The rig file's {@code timeouts:} block, with the ssh default applied. */
    public static Timeouts of(Rig.Timeouts block) {
        return block == null ? defaults() : new Timeouts(block.defaultWait(), null);
    }

    /**
     * From a raw {@code timeouts:} mapping such as the YAML loader produces. Missing keys take
     * their defaults; an unparseable value is refused by {@link Durations#parse}.
     */
    public static Timeouts parse(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return defaults();
        }
        Duration defaultWait = raw.containsKey("default_wait") ? Durations.parse(raw.get("default_wait")) : null;
        Duration ssh = raw.containsKey("ssh") ? Durations.parse(raw.get("ssh")) : null;
        return new Timeouts(defaultWait, ssh);
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
