package com.verifone.cat.shell;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * What holds the rig now: the Python CAT's {@code CatService.busy}, one flag across a run, a preflight, an install,
 * a simulator launch and a simulator stop. One action at a time: {@link #begin} refuses a second with the Python's
 * words ("A run is already in progress.").
 *
 * <p>The run and the preflight are held by the run service, which keeps its own flag; {@link #attachRun} hands this
 * class a reader of it, so an install or a simulator launch is refused while a run is going. The run service must in
 * turn refuse to start while {@link #own()} is set (the web shell's before-filter on Start run does; the run service
 * itself should call {@link #refusal()} too: a wave-16 hook).
 */
public final class RigBusy {

    public static final String RUN = "run";
    public static final String PREFLIGHT = "preflight";
    public static final String INSTALL = "install";
    public static final String SIMS_LAUNCH = "sims-launch";
    public static final String SIMS_STOP = "sims-stop";

    /** What each value is called in a refusal or the header: the Python's {@code BUSY_WORDS}. */
    public static final Map<String, String> WORDS = Map.of(
            RUN, "a run",
            PREFLIGHT, "a preflight",
            INSTALL, "an install",
            SIMS_LAUNCH, "a simulator launch",
            SIMS_STOP, "a simulator stop");

    private Supplier<String> run = () -> "";
    private String own = "";

    /** Reads the run service's flag: {@link #RUN}, {@link #PREFLIGHT} or "". */
    public synchronized void attachRun(Supplier<String> reader) {
        run = Objects.requireNonNull(reader, "reader");
    }

    /** What holds the rig, or "" when nothing does. */
    public synchronized String current() {
        String held = run.get();
        return held == null || held.isEmpty() ? own : held;
    }

    /** Only what this flag itself holds (an install or a simulator action), not the run's. */
    public synchronized String own() {
        return own;
    }

    /** Why nothing may start now, or "": the Python's {@code _refusal}. */
    public synchronized String refusal() {
        String held = current();
        return held.isEmpty() ? "" : capitalise(words(held)) + " is already in progress.";
    }

    /** Takes the flag for {@code kind}: "" when taken, else the refusal. */
    public synchronized String begin(String kind) {
        String why = refusal();
        if (!why.isEmpty()) {
            return why;
        }
        own = kind;
        return "";
    }

    /** Gives the flag back, if {@code kind} holds it. */
    public synchronized void end(String kind) {
        if (own.equals(kind)) {
            own = "";
        }
    }

    /** {@code a run}, {@code an install}...; "something" for a value with no words. */
    public static String words(String kind) {
        return WORDS.getOrDefault(kind, "something");
    }

    static String capitalise(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
