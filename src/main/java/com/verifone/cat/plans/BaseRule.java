package com.verifone.cat.plans;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plan's Required Base: {@code 57≥} is base 57.00 and up; {@code 56≤} is 56 or earlier.
 *
 * <p>Bases compare as (major, minor) pairs. An {@code atMost} rule whose minor is zero
 * compares majors only, as Python's {@code cat.requirements.BaseRule} does: {@code 56≤}
 * allows {@code 56.02}.
 */
public record BaseRule(int major, int minor, boolean atMost) {

    /** Base numbers as the page writes them: {@code 57}, {@code 057.00}, {@code 57.01}. */
    private static final Pattern BASE_NUMBER = Pattern.compile("(?<!\\d)0*(\\d{2})(?:\\.(\\d{1,2}))?");

    /** Marks that make a rule an upper bound. */
    private static final String[] AT_MOST_MARKS = {"≤", "<=", "or earlier", "or below", "up to"};

    public boolean allows(int baseMajor, int baseMinor) {
        if (atMost) {
            if (minor == 0) {
                return baseMajor <= major;
            }
            return baseMajor < major || (baseMajor == major && baseMinor <= minor);
        }
        return baseMajor > major || (baseMajor == major && baseMinor >= minor);
    }

    /**
     * Reads a Required Base cell: {@code 57≥}, {@code on Base 57.01 onwards}, {@code 56≤},
     * {@code Base057.00 onwards}. Empty when the cell names no base.
     */
    public static Optional<BaseRule> parse(String text) {
        String cleaned = Text.clean(Text.orEmpty(text));
        Matcher match = BASE_NUMBER.matcher(cleaned);
        if (!match.find()) {
            return Optional.empty();
        }
        int major = Integer.parseInt(match.group(1));
        int minor = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
        String lowered = cleaned.toLowerCase(Locale.ROOT);
        boolean atMost = false;
        for (String mark : AT_MOST_MARKS) {
            atMost |= lowered.contains(mark);
        }
        return Optional.of(new BaseRule(major, minor, atMost));
    }

    @Override
    public String toString() {
        return String.format("base %d.%02d %s", major, minor, atMost ? "or earlier" : "or later");
    }
}
