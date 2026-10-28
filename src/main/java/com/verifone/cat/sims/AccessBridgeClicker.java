package com.verifone.cat.sims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Presses a simulator's Swing buttons through the Java Access Bridge, and minimises the windows the
 * simulators raise: the Python CAT's {@code _do_clicks}, {@code _main_window_handle},
 * {@code _minimise} and {@code _minimise_when_shown} ({@code cat.sims.supervisor}), over
 * {@link Jab} and {@link WindowOps}.
 *
 * <p>Before a {@code click:} row is spawned, {@link #unavailable} says whether it can be clicked at
 * all, naming the fix: not Windows; no {@code javaw.exe}; no {@code windowsaccessbridge-64.dll}
 * beside it; or the bridge not switched on for this user (no {@code assistive_technologies} naming
 * {@code com.sun.java.accessibility.AccessBridge} in {@code ~/.accessibility.properties}, which is the
 * file {@code jabswitch -enable} writes). The Python spawns first and finds these out after; this
 * port asks first, so a row it cannot click is never left running for the operator to rescue. The
 * check must come before the spawn anyway: a JVM reads that file when it starts, so a simulator
 * started before {@code jabswitch -enable} is invisible to the bridge until it is restarted.
 *
 * <p>The main window is found by PID, through {@code EnumWindows} (the first visible, unowned
 * top-level window of the process), polled until the row's deadline. The Python asks PowerShell for
 * {@code (Get-Process -Id pid).MainWindowHandle}, which is the same rule; this port calls user32
 * directly and stops early, with the exit code, if the process exits before showing a window.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's clicking ran against all four simulators
 * on 2026-09-10; this class has run only against a fake bridge and fake windows.
 */
public final class AccessBridgeClicker implements Clicker {

    /** How long past its start budget a {@code windowed:} row's windows are watched (the Python's {@code _WINDOW_BUDGET}). */
    public static final Duration WINDOW_BUDGET = Duration.ofSeconds(30);

    /** How often a window lookup re-checks (the Python's {@code _POLL}). */
    static final Duration POLL = Duration.ofMillis(250);

    /** What {@code jabswitch -enable} writes as the {@code assistive_technologies} value. */
    static final String ASSISTIVE_TECHNOLOGY = "com.sun.java.accessibility.AccessBridge";

    /** The per-user file {@code jabswitch -enable} writes. */
    public static final String PROPERTIES_FILE = ".accessibility.properties";

    private final WindowOps windows;
    private final Function<Path, AccessBridge> bridges;
    private final Path properties;
    private final boolean onWindows;
    private final Duration settle;
    private final Consumer<Duration> pause;
    private final Map<Path, AccessBridge> loaded = new ConcurrentHashMap<>();

    /**
     * @param windows the user32 seam
     * @param bridges opens the bridge for a DLL path; called once per path
     * @param properties the user's {@code .accessibility.properties}
     * @param onWindows false makes every row unavailable, as off Windows
     * @param settle the pause after connecting and after each click
     * @param pause how a pause is waited (tests record it)
     */
    public AccessBridgeClicker(WindowOps windows, Function<Path, AccessBridge> bridges, Path properties,
                               boolean onWindows, Duration settle, Consumer<Duration> pause) {
        this.windows = Objects.requireNonNull(windows, "windows");
        this.bridges = Objects.requireNonNull(bridges, "bridges");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.onWindows = onWindows;
        this.settle = Objects.requireNonNull(settle, "settle");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * The real one: user32 and the bridge DLL through JNA, this user's
     * {@code ~/.accessibility.properties} (read, never written). Loads no native code until a click.
     */
    public static AccessBridgeClicker forThisController() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path home = Path.of(System.getProperty("user.home", ""));
        return new AccessBridgeClicker(new JnaWindowOps(), JnaAccessBridge::new, home.resolve(PROPERTIES_FILE),
                windows, Jab.DEFAULT_SETTLE, Park::park);
    }

    @Override
    public Optional<String> unavailable(SimContext ctx) {
        if (!onWindows) {
            return Optional.of("the Java Access Bridge needs Windows");
        }
        Optional<Path> javaw = ctx.javawExe();
        if (javaw.isEmpty()) {
            return Optional.of("no javaw.exe under " + ctx.javaHome());
        }
        Optional<Path> dll = ctx.bridgeDll();
        if (dll.isEmpty()) {
            return Optional.of("windowsaccessbridge-64.dll not found beside " + javaw.get());
        }
        if (!accessBridgeEnabled(properties)) {
            Path jabswitch = javaw.get().resolveSibling("jabswitch.exe");
            return Optional.of("the Java Access Bridge is not enabled for this Windows user (no assistive_technologies="
                    + ASSISTIVE_TECHNOLOGY + " in " + properties + "): run \"" + jabswitch
                    + "\" -enable once, then start again");
        }
        return Optional.empty();
    }

    @Override
    public Clicked click(SimEntry entry, Process process, SimContext ctx, long deadline) {
        Optional<String> why = unavailable(ctx);
        if (why.isPresent()) {
            return new Clicked(false, why.get());
        }
        Path dll = ctx.bridgeDll().orElseThrow();
        long hwnd;
        try {
            hwnd = mainWindow(process, deadline);
        } catch (RuntimeException | LinkageError e) {
            return new Clicked(false, "could not look for the window of pid " + process.pid() + ": "
                    + Jab.describe(e));
        }
        if (hwnd == 0) {
            if (!process.isAlive()) {
                return new Clicked(false, "exited with " + process.exitValue() + " before showing a window");
            }
            return new Clicked(false, "no window handle for pid " + process.pid() + " within the start budget");
        }
        Duration remaining = Duration.ofNanos(Math.max(Duration.ofSeconds(1).toNanos(), deadline - System.nanoTime()));
        // The timeout caps the whole enumerate-and-click sequence, not only the connect: every bridge
        // call is synchronous and can stall indefinitely.
        Jab.Outcome outcome;
        try {
            AccessBridge bridge = loaded.computeIfAbsent(dll, bridges);
            outcome = new Jab(bridge, pause).clickButtons(hwnd, entry.click(), remaining, settle, remaining);
        } finally {
            windows.minimise(hwnd);
        }
        return new Clicked(outcome.clicked(), outcome.detail());
    }

    @Override
    public void minimiseWhenShown(Process process, long deadline) {
        Thread thread = new Thread(() -> watchAndMinimise(process, deadline), "sims-minimise-" + process.pid());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The Python's {@code _minimise_when_shown}: each new, not-minimised top-level window is minimised
     * once, so one the operator restores is left alone, until the deadline or the process exits.
     */
    void watchAndMinimise(Process process, long deadline) {
        Set<Long> seen = new HashSet<>();
        try {
            while (true) {
                for (WindowOps.TopWindow window : windows.topLevelWindows(process.pid())) {
                    if (!window.minimised() && seen.add(window.hwnd())) {
                        windows.minimise(window.hwnd());
                    }
                }
                if (System.nanoTime() - deadline >= 0 || !process.isAlive()) {
                    return;
                }
                pause.accept(POLL);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Best effort by design: a window never minimised is cosmetic, never a row.
        }
    }

    /** The process's main window, polled until {@code deadline}; 0 when none showed, or it exited. */
    private long mainWindow(Process process, long deadline) {
        while (true) {
            List<WindowOps.TopWindow> found = windows.topLevelWindows(process.pid());
            if (!found.isEmpty()) {
                return found.get(0).hwnd();
            }
            if (System.nanoTime() - deadline >= 0 || !process.isAlive()) {
                return 0;
            }
            pause.accept(POLL);
        }
    }

    /**
     * True when {@code jabswitch -enable} has been run for this user: the Python CAT's
     * {@code access_bridge_enabled}. A missing or unreadable file is false, the honest answer.
     */
    public static boolean accessBridgeEnabled(Path properties) {
        String text;
        try {
            text = new String(Files.readAllBytes(properties), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (line.substring(0, eq).strip().equals("assistive_technologies")) {
                return line.substring(eq + 1).contains(ASSISTIVE_TECHNOLOGY);
            }
        }
        return false;
    }
}
