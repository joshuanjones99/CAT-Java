package com.verifone.cat.runservice;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.verifone.cat.rig.Durations;

/**
 * Parses what the operator typed for a scheduled run into wall-clock instants. The Python CAT's
 * {@code cat.schedule.parse_run_time} and {@code parse_schedule_times}.
 *
 * <p>Two grammars: absolute ({@code 23:00}, {@code 23:00:30}, {@code 2026-09-06 23:00},
 * {@code 2026-09-06T23:00:30}) and relative ({@code +90s}, {@code +30m}, {@code +1h}, the rest read by
 * {@link Durations#parse}, the one duration grammar). A bare {@code HH:MM} already past today means
 * tomorrow. Several times are comma- or space-separated; one bad entry rejects the whole list.
 *
 * <p>Pure: {@code now} is passed in, so tests never read the wall clock.
 */
public final class ScheduleTimes {

    private static final Pattern SPLIT = Pattern.compile("[,\\s]+");
    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** The forms with a date, then the bare clock times, which take today's date. */
    private static final List<DateTimeFormatter> DATED = List.of(
            formatter("uuuu-MM-dd HH:mm:ss"),
            formatter("uuuu-MM-dd HH:mm"),
            formatter("uuuu-MM-dd'T'HH:mm:ss"),
            formatter("uuuu-MM-dd'T'HH:mm"));
    private static final List<DateTimeFormatter> CLOCK = List.of(
            formatter("HH:mm:ss"),
            formatter("HH:mm"));

    /** What an operator is told when a time does not read. */
    static final String EXPECTED = "a time like '23:00', '23:00:30', '2026-09-06 23:00', "
            + "or a relative delay like '+30m' / '+1h'";

    private ScheduleTimes() {}

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    /**
     * One time, as an absolute instant.
     *
     * @throws IllegalArgumentException naming what was expected and what was found
     */
    public static LocalDateTime parseRunTime(String text, LocalDateTime now) {
        String candidate = text == null ? "" : text.strip();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("expected " + EXPECTED + ", got an empty value");
        }
        if (candidate.startsWith("+")) {
            return now.plus(Durations.parse(candidate.substring(1).strip()));
        }
        for (DateTimeFormatter format : DATED) {
            try {
                return LocalDateTime.parse(candidate, format);
            } catch (DateTimeParseException ignored) {
                // try the next form
            }
        }
        for (DateTimeFormatter format : CLOCK) {
            try {
                LocalTime time = LocalTime.parse(candidate, format);
                LocalDateTime today = LocalDateTime.of(now.toLocalDate(), time);
                return today.isAfter(now) ? today : today.plusDays(1);
            } catch (DateTimeParseException ignored) {
                // try the next form
            }
        }
        throw new IllegalArgumentException("expected " + EXPECTED + ", got '" + text + "'");
    }

    /**
     * A comma- or space-separated list, in the order given. Empty input gives an empty list; any bad
     * entry throws rather than scheduling the good ones.
     */
    public static List<LocalDateTime> parseScheduleTimes(String text, LocalDateTime now) {
        List<String> parts = new ArrayList<>();
        for (String part : SPLIT.split(text == null ? "" : text.strip())) {
            if (part.isEmpty()) {
                continue;
            }
            // "2026-09-06 23:00" is one time: a bare date rejoins the time after it.
            if (!parts.isEmpty() && DATE_ONLY.matcher(parts.get(parts.size() - 1)).matches()) {
                parts.set(parts.size() - 1, parts.get(parts.size() - 1) + " " + part);
            } else {
                parts.add(part);
            }
        }
        List<LocalDateTime> times = new ArrayList<>(parts.size());
        for (String part : parts) {
            times.add(parseRunTime(part, now));
        }
        return times;
    }
}
