package com.verifone.cat.runservice;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Log tab's sources, filters and line format: the Python CAT's {@code cat.panels.log} ({@code source_options},
 * {@code filter_by_source}, {@code format_line}) and the web Log panel's level and search filters.
 *
 * <p>A source is All, one step, or one simulator. A step's lines are the ones carrying that step
 * ({@link RunState#logLinesFor}), a simulator's the ones its {@code sim.<name>} logger wrote
 * ({@link RunState#logLinesFrom}); each is read from its own bounded buffer, so a quiet step's lines are not evicted
 * by the session's. An unknown source is All, never an empty view.
 */
public final class LogSources {

    /** The "no filter" value. */
    public static final String SOURCE_ALL = "";

    /** A step's source value prefix. */
    public static final String SOURCE_STEP = "step:";

    /** A simulator's source value prefix. */
    public static final String SOURCE_SIM = "sim:";

    /** The levels, in order, each a filter. */
    public static final List<String> LEVELS = List.of("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL");

    /** How many of the newest lines are drawn at once; Copy takes all of them (the web panel's {@code SHOWN}). */
    public static final int SHOWN = 2000;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private LogSources() {}

    /** One option of the source selector. */
    public record Option(String label, String value) {}

    /** All, then every step (even one not yet run), then the simulators the run knows. */
    public static List<Option> options(List<String> simNames) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("All", SOURCE_ALL));
        for (String step : RunState.STEP_NAMES) {
            options.add(new Option(step, SOURCE_STEP + step));
        }
        for (String name : simNames) {
            options.add(new Option(name, SOURCE_SIM + name));
        }
        return options;
    }

    /** The run's lines narrowed to one source. */
    public static List<RunState.LogLine> bySource(RunState state, String source) {
        String value = source == null ? "" : source;
        if (value.startsWith(SOURCE_STEP)) {
            return state.logLinesFor(value.substring(SOURCE_STEP.length()));
        }
        if (value.startsWith(SOURCE_SIM)) {
            return state.logLinesFrom(RunState.SIM_LOGGER_PREFIX + value.substring(SOURCE_SIM.length()));
        }
        return state.logLines();
    }

    /** The source's lines whose level is in {@code levels} and whose message holds {@code search} (any case). */
    public static List<RunState.LogLine> filter(RunState state, String source, Set<String> levels, String search) {
        String needle = search == null ? "" : search.toLowerCase(Locale.ROOT);
        List<RunState.LogLine> out = new ArrayList<>();
        for (RunState.LogLine line : bySource(state, source)) {
            if (levels.contains(line.level())
                    && (needle.isEmpty() || line.message().toLowerCase(Locale.ROOT).contains(needle))) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * {@code HH:MM:SS L source: message}: the level one letter, the logger its last dotted segment. The time is the
     * event's own (UTC), as the Python formats the envelope's {@code at}.
     */
    public static String format(RunState.LogLine line) {
        String logger = line.logger();
        String source = logger.substring(logger.lastIndexOf('.') + 1);
        String clock = line.at() == null ? "--:--:--" : CLOCK.format(line.at());
        String level = line.level().isEmpty() ? "" : line.level().substring(0, 1);
        return clock + " " + level + " " + source + ": " + line.message();
    }
}
