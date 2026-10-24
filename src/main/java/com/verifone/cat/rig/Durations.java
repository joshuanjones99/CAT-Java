package com.verifone.cat.rig;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one duration grammar for rig files: {@code 15s}, {@code 30 s}, {@code 500ms}, {@code 2m},
 * {@code 1h}, {@code 1.5s}, or a bare number of seconds. Ported from {@code petrotest/rig/duration.py}.
 *
 * <p>The rig's timeouts are the defaults for every bounded wait the harness makes, so they are
 * parsed once, at load time, into a {@link Duration}. A malformed value must fail loading with
 * the field named, never surface later as a mysterious timeout. There must never be a second
 * parser: the lease timeout reads its environment variable through this class too.
 *
 * <p>Rejected: zero and negative durations, unknown units, compound forms ({@code 1m30s}),
 * booleans, and anything that is not a duration. The grammar is deliberately small: a timeout
 * measured in days is a configuration error, not a value.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Pure arithmetic; no device is involved.
 */
public final class Durations {

    /** Nanoseconds per unit. Exact integers, so {@code 500ms} is exactly half a second. */
    private static final Map<String, Long> UNIT_NANOS = Map.of(
            "ms", 1_000_000L,
            "s", 1_000_000_000L,
            "m", 60_000_000_000L,
            "h", 3_600_000_000_000L);

    private static final Pattern PATTERN = Pattern.compile(
            "^(?<value>\\d+(?:\\.\\d+)?)\\s*(?<unit>ms|s|m|h)?$",
            Pattern.CASE_INSENSITIVE);

    /** Quoted in every error, so a rig author sees exactly what is accepted. */
    static final String EXPECTED = "a duration like '15s', '90s', '500ms', '2m' or '1h' (bare number = seconds)";

    private Durations() {}

    /**
     * Parses a rig duration. Accepts a {@link Duration} (returned as-is when positive), a number
     * of seconds, or a string in the grammar above.
     *
     * @throws IllegalArgumentException naming what was expected and what was found
     */
    public static Duration parse(Object value) {
        Duration parsed;
        if (value instanceof Duration d) {
            parsed = d;
        } else if (value instanceof Boolean) {
            // A boolean is not a number of seconds, even though Java would let it through a Number test.
            throw new IllegalArgumentException("expected " + EXPECTED + ", got boolean " + value);
        } else if (value instanceof Number n) {
            parsed = fromSeconds(toDecimal(n, value));
        } else if (value instanceof String s) {
            Matcher m = PATTERN.matcher(s.strip());
            if (!m.matches()) {
                throw new IllegalArgumentException("expected " + EXPECTED + ", got " + repr(value));
            }
            String unit = m.group("unit") == null ? "s" : m.group("unit").toLowerCase(java.util.Locale.ROOT);
            BigDecimal nanos = new BigDecimal(m.group("value")).multiply(BigDecimal.valueOf(UNIT_NANOS.get(unit)));
            parsed = fromNanos(nanos, value);
        } else {
            throw new IllegalArgumentException("expected " + EXPECTED + ", got "
                    + (value == null ? "null" : value.getClass().getSimpleName() + " " + repr(value)));
        }

        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalArgumentException("expected " + EXPECTED + " greater than zero, got " + repr(value));
        }
        return parsed;
    }

    /**
     * Renders a duration back into rig form, for logs and round trips. Sub-second values are
     * shown in milliseconds, as Python's {@code format_duration} does.
     */
    public static String format(Duration value) {
        BigDecimal seconds = BigDecimal.valueOf(value.toNanos(), 9);
        if (value.compareTo(Duration.ofSeconds(1)) < 0) {
            return pyG(seconds.movePointRight(3)) + "ms";
        }
        return pyG(seconds) + "s";
    }

    /** Milliseconds, the unit Playwright and CDP want. */
    public static double milliseconds(Duration value) {
        return value.toNanos() / 1_000_000.0;
    }

    private static BigDecimal toDecimal(Number n, Object original) {
        if ((n instanceof Double || n instanceof Float) && (Double.isNaN(n.doubleValue())
                || Double.isInfinite(n.doubleValue()))) {
            throw new IllegalArgumentException("expected " + EXPECTED + ", got " + repr(original));
        }
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected " + EXPECTED + ", got " + repr(original), e);
        }
    }

    private static Duration fromSeconds(BigDecimal seconds) {
        return fromNanos(seconds.multiply(BigDecimal.valueOf(UNIT_NANOS.get("s"))), seconds);
    }

    private static Duration fromNanos(BigDecimal nanos, Object original) {
        try {
            return Duration.ofNanos(nanos.setScale(0, RoundingMode.HALF_UP).longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("expected " + EXPECTED + ", got " + repr(original), e);
        }
    }

    /**
     * Python's {@code format(x, "g")} for the magnitudes rig timeouts reach: six significant
     * digits, trailing zeros dropped. Scientific notation is not reproduced; no rig timeout
     * is large enough to need it.
     */
    private static String pyG(BigDecimal x) {
        BigDecimal rounded = x.round(new MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0).toPlainString() : rounded.toPlainString();
    }

    /** A value as Python's {@code repr} would show it for the error messages. */
    private static String repr(Object value) {
        if (value instanceof String s) {
            return "'" + s + "'";
        }
        return String.valueOf(value);
    }
}
