package com.verifone.cat.sims;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Clicks named buttons in a Java Swing simulator window through the Java Access Bridge: the Python
 * CAT's {@code cat.sims.jab}, which was vendored from PATS's {@code sim_click.py} and is the only
 * route to these controls (they are invisible to UI Automation).
 *
 * <p>The two hard-won behaviours of the Python are kept deliberately:
 * <ol>
 *   <li><b>Connect by HWND, not title.</b> The three LoySim windows share one title.</li>
 *   <li><b>Every Access Bridge call runs on an abandonable daemon thread with a cap.</b> A bridge call
 *       can block forever, and a deadline check outside it never fires while it is inside. Each
 *       connect attempt runs on a thread of its own, capped at {@link #ATTEMPT_CAP}; the whole
 *       connect-settle-click sequence runs on another, capped by the caller's timeout. A thread that
 *       overruns is abandoned, never joined, so a hung bridge is a row and never a wedged Step 3.</li>
 * </ol>
 * Also kept: an exact case-insensitive name match before a substring one, a re-query of the button
 * tree before each click (pressing one can enable or replace others), the available-button list in a
 * not-found detail, and every exception in the sequence as a row rather than a throw.
 *
 * <p>The message loop. The bridge needs one on the thread that calls {@code Windows_run}: the JVMs
 * answer its hello by posting window messages. pyjab calls {@code Windows_run} and pumps once
 * (200 ms) inside each {@code JABDriver()}, on the attempt thread, and the attempt thread then exits.
 * This port does the same, with two differences: an attempt pumps until the window is a Java window
 * or {@link #REGISTER_WAIT} has passed (pyjab checks once, then fails the attempt and retries after
 * two seconds), and the attempt thread that connected keeps pumping until the click sequence ends or
 * its cap expires, rather than exiting at once.
 *
 * <p>Other deviations from the Python, each deliberate:
 * <ul>
 *   <li>A button matches on its localised role or its US-English role being {@code push button}
 *       (pyjab compares the localised one only).</li>
 *   <li>A window with no push buttons at all is a not-found row with an empty available list (pyjab
 *       raises "no JABElement found", which the Python reports as {@code failed}).</li>
 *   <li>The tree walk stops at {@link #MAX_NODES} elements, as a failure, so a pathological tree is
 *       bounded too.</li>
 *   <li>A {@link LinkageError} (the DLL or JNA's native part would not load) ends the connect at once
 *       instead of being retried until the timeout.</li>
 *   <li>pyjab's own {@code ~/.accessibility.properties} write is not done: {@link AccessBridgeClicker}
 *       reports the missing {@code jabswitch -enable} instead, and CAT-Java writes only under
 *       {@code ~/.cat/java/}.</li>
 * </ul>
 *
 * <p>The Python module's {@code main()} CLI (the operator's at-rig diagnostic) is not ported.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python pressed all four simulators' buttons on
 * 2026-09-10; this class has only run against a fake {@link AccessBridge}.
 */
public final class Jab {

    /** Longest a single connect attempt may block before it is abandoned (the Python's {@code _ATTEMPT_CAP}). */
    public static final Duration ATTEMPT_CAP = Duration.ofSeconds(10);

    /** The pause after a failed (not hung) attempt before the next. */
    public static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

    /** How long one attempt pumps messages waiting for the window to be a Java window. */
    public static final Duration REGISTER_WAIT = Duration.ofSeconds(3);

    /** One turn of the message pump (pyjab's 200 ms {@code MsgWaitForMultipleObjects}). */
    public static final Duration PUMP_SLICE = Duration.ofMillis(200);

    /** The pause after connecting and after each click (the Python's {@code settle}). */
    public static final Duration DEFAULT_SETTLE = Duration.ofMillis(1500);

    /** The connect budget when none is given (the Python's {@code connect_timeout}). */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /** The most accessible elements one walk visits. */
    public static final int MAX_NODES = 10_000;

    /** The role a Swing button reports. */
    public static final String PUSH_BUTTON = "push button";

    /**
     * How a click sequence ended.
     *
     * @param clicked true only when every button was pressed
     * @param detail one line for the operator: what was clicked, and what went wrong
     */
    public record Outcome(boolean clicked, String detail) {
    }

    /** One push button found in the tree. */
    record Button(AccessBridge.JavaObject ref, String name, boolean releasable) {
    }

    private final AccessBridge bridge;
    private final Consumer<Duration> pause;

    /** A Jab that pauses with a bounded park. */
    public Jab(AccessBridge bridge) {
        this(bridge, Park::park);
    }

    /** For tests, which record the settle pauses instead of waiting them. */
    public Jab(AccessBridge bridge, Consumer<Duration> pause) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * Clicks each button in order, with the Python's default budget: the connect timeout, one settle
     * per button and one more, and one attempt cap.
     */
    public Outcome clickButtons(long hwnd, List<String> buttons, Duration connectTimeout, Duration settle) {
        Duration timeout = connectTimeout.plus(settle.multipliedBy(buttons.size() + 1L)).plus(ATTEMPT_CAP);
        return clickButtons(hwnd, buttons, connectTimeout, settle, timeout);
    }

    /**
     * Clicks each button in order, bounded end to end by {@code timeout} (at least a second). The
     * sequence runs on an abandonable daemon thread; when the cap expires the answer is a
     * {@code clicking timed out} outcome and the thread is left behind.
     */
    public Outcome clickButtons(long hwnd, List<String> buttons, Duration connectTimeout, Duration settle,
                                Duration timeout) {
        List<String> wanted = List.copyOf(buttons);
        Duration cap = timeout.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : timeout;
        long capEnds = System.nanoTime() + cap.toNanos();
        AtomicReference<Outcome> outcome = new AtomicReference<>(new Outcome(false, ""));
        Thread worker = daemon("access-bridge-click", () -> {
            try {
                outcome.set(sequence(hwnd, wanted, connectTimeout, settle, capEnds));
            } catch (RuntimeException | LinkageError e) {
                // Belt and braces: the caller gets a row either way.
                outcome.set(new Outcome(false, "clicking failed: " + describe(e)));
            }
        });
        worker.start();
        if (!joinFor(worker, cap)) {
            return new Outcome(false, "clicking timed out after " + seconds(cap) + "s");
        }
        return outcome.get();
    }

    /** Connect, settle, then click. Runs on the capped worker thread. */
    private Outcome sequence(long hwnd, List<String> buttons, Duration connectTimeout, Duration settle,
                             long holdUntil) {
        Connection connection;
        try {
            connection = connect(hwnd, connectTimeout, holdUntil);
        } catch (IllegalStateException e) {
            return new Outcome(false, e.getMessage());
        }
        try {
            pause.accept(settle);
            return clickEach(connection.root(), buttons, settle);
        } finally {
            connection.close();
        }
    }

    /**
     * A window's root context, and the attempt thread still pumping messages for it.
     */
    final class Connection implements AutoCloseable {
        private final AccessBridge.JavaObject root;
        private final Attempt attempt;

        Connection(AccessBridge.JavaObject root, Attempt attempt) {
            this.root = root;
            this.attempt = attempt;
        }

        AccessBridge.JavaObject root() {
            return root;
        }

        /** Stops the attempt thread's pumping and releases the root context. Never throws. */
        @Override
        public void close() {
            attempt.finish();
            try {
                bridge.release(root);
            } catch (RuntimeException | LinkageError ignored) {
                // Releasing is housekeeping; a failure here must not turn a click into a failure.
            }
        }
    }

    /**
     * Retries until the window and the bridge are ready (the GUI takes a beat to paint), each attempt on
     * a throwaway daemon thread capped at {@link #ATTEMPT_CAP}.
     *
     * @param holdUntil when the connected attempt stops pumping messages if nobody closes it first
     * @throws IllegalStateException {@code could not connect to the simulator window: ...}
     */
    Connection connect(long hwnd, Duration timeout, long holdUntil) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "the connect budget was spent before an attempt";
        while (System.nanoTime() - deadline < 0) {
            long remaining = deadline - System.nanoTime();
            Duration perAttempt = Duration.ofNanos(Math.min(ATTEMPT_CAP.toNanos(),
                    Math.max(TimeUnit.SECONDS.toNanos(1), remaining)));
            Attempt attempt = new Attempt(hwnd, holdUntil,
                    Duration.ofNanos(Math.min(REGISTER_WAIT.toNanos(), perAttempt.toNanos() / 2)));
            Thread thread = daemon("access-bridge-connect", attempt);
            thread.start();
            boolean answered = attempt.await(perAttempt);
            AccessBridge.JavaObject root = attempt.takeRoot();
            if (root != null) {
                return new Connection(root, attempt);
            }
            Throwable error = attempt.error();
            if (error instanceof LinkageError) {
                last = describe(error);
                break;
            }
            if (!answered) {
                // Hung inside the bridge: abandon it (a daemon, so it cannot hold the JVM open).
                last = "Access Bridge connect timed out after " + seconds(perAttempt) + "s";
            } else {
                last = error == null ? "the Access Bridge gave no context for the window" : describe(error);
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    pause.accept(Duration.ofNanos(Math.min(RETRY_PAUSE.toNanos(), left)));
                }
            }
        }
        throw new IllegalStateException("could not connect to the simulator window: " + last);
    }

    /** One connect attempt: {@code Windows_run}, pump until the window is Java, take its context, hold. */
    final class Attempt implements Runnable {
        private final long hwnd;
        private final long holdUntil;
        private final Duration registerWait;
        private AccessBridge.JavaObject root;
        private Throwable error;
        private boolean taken;
        private boolean abandoned;
        private volatile boolean finished;
        private final CountDownLatch answer = new CountDownLatch(1);

        Attempt(long hwnd, long holdUntil, Duration registerWait) {
            this.hwnd = hwnd;
            this.holdUntil = holdUntil;
            this.registerWait = registerWait;
        }

        @Override
        public void run() {
            AccessBridge.JavaObject found;
            try {
                bridge.windowsRun();
                bridge.pumpMessages(PUMP_SLICE);
                long registerBy = System.nanoTime() + registerWait.toNanos();
                boolean java = bridge.isJavaWindow(hwnd);
                while (!java && !finished && System.nanoTime() - registerBy < 0) {
                    bridge.pumpMessages(PUMP_SLICE);
                    java = bridge.isJavaWindow(hwnd);
                }
                if (!java) {
                    throw new IllegalStateException("HWND " + hwnd + " is not a Java window the Access Bridge "
                            + "knows (was the bridge enabled before the simulator started?)");
                }
                found = bridge.contextFromHwnd(hwnd).orElseThrow(() -> new IllegalStateException(
                        "getAccessibleContextFromHWND failed for HWND " + hwnd));
            } catch (RuntimeException | LinkageError e) {
                fail(e);
                return;
            }
            if (!offer(found)) {
                return;
            }
            // Keep this thread's messages flowing while the sequence uses the bridge, bounded.
            try {
                while (!finished && System.nanoTime() - holdUntil < 0) {
                    bridge.pumpMessages(PUMP_SLICE);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Pumping is a courtesy to the bridge; the sequence reports its own failures.
            }
        }

        /** Hands the context over, unless the caller already gave up; then it is released here. */
        private boolean offer(AccessBridge.JavaObject found) {
            synchronized (this) {
                if (!abandoned) {
                    root = found;
                    answer.countDown();
                    return true;
                }
            }
            answer.countDown();
            try {
                bridge.release(found);
            } catch (RuntimeException | LinkageError ignored) {
                // Abandoned: nobody is waiting for this answer.
            }
            return false;
        }

        /** Waits up to {@code limit} for this attempt to connect or fail; false if it is still inside the bridge. */
        boolean await(Duration limit) {
            try {
                return answer.await(Math.max(1, limit.toNanos()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return answer.getCount() == 0;
            }
        }

        private void fail(Throwable e) {
            synchronized (this) {
                error = e;
            }
            answer.countDown();
        }

        /** The context, once; null when there is none yet, and from then on this attempt is abandoned. */
        synchronized AccessBridge.JavaObject takeRoot() {
            if (root != null && !taken) {
                taken = true;
                return root;
            }
            abandoned = true;
            finished = true;
            return null;
        }

        synchronized Throwable error() {
            return error;
        }

        void finish() {
            finished = true;
        }
    }

    /**
     * The push buttons under {@code root}, in pyjab's order (each element's descendants before the
     * element), and the one named {@code name}: an exact case-insensitive match first, else the first
     * whose name contains it. Re-queried on every call.
     */
    Found findButton(AccessBridge.JavaObject root, String name) {
        List<Button> buttons = pushButtons(root);
        String wanted = name.toLowerCase(Locale.ROOT);
        Button target = null;
        for (Button button : buttons) {
            if (!button.name().isEmpty() && button.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                target = button;
                break;
            }
        }
        if (target == null) {
            for (Button button : buttons) {
                if (!button.name().isEmpty() && button.name().toLowerCase(Locale.ROOT).contains(wanted)) {
                    target = button;
                    break;
                }
            }
        }
        return new Found(target, buttons);
    }

    /** A {@link #findButton} answer: the match or null, and every button seen. */
    record Found(Button target, List<Button> buttons) {
    }

    private List<Button> pushButtons(AccessBridge.JavaObject root) {
        List<Button> found = new ArrayList<>();
        AccessBridge.ContextInfo rootInfo = bridge.info(root);
        if (isPushButton(rootInfo)) {
            found.add(new Button(root, rootInfo.name(), false));
        }
        int[] visited = {0};
        try {
            descend(root, rootInfo.childrenCount(), found, visited);
        } catch (RuntimeException | LinkageError e) {
            release(found);
            throw e;
        }
        return found;
    }

    private void descend(AccessBridge.JavaObject parent, int count, List<Button> found, int[] visited) {
        for (int index = 0; index < count; index++) {
            if (++visited[0] > MAX_NODES) {
                throw new IllegalStateException("more than " + MAX_NODES + " accessible elements in the window");
            }
            AccessBridge.JavaObject child = bridge.child(parent, index);
            if (child.isNull()) {
                continue;
            }
            boolean kept = false;
            try {
                AccessBridge.ContextInfo info = bridge.info(child);
                if (info.childrenCount() > 0) {
                    descend(child, info.childrenCount(), found, visited);
                }
                if (isPushButton(info)) {
                    found.add(new Button(child, info.name(), true));
                    kept = true;
                }
            } finally {
                if (!kept) {
                    bridge.release(child);
                }
            }
        }
    }

    private static boolean isPushButton(AccessBridge.ContextInfo info) {
        return info.role().equalsIgnoreCase(PUSH_BUTTON) || info.roleEnUs().equalsIgnoreCase(PUSH_BUTTON);
    }

    /**
     * Presses each name in order against a connected window. The whole per-button body is guarded,
     * enumeration included, so one bad button is a problem in the detail and the next is still tried.
     */
    Outcome clickEach(AccessBridge.JavaObject root, List<String> buttons, Duration settle) {
        List<String> clicked = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String name : buttons) {
            try {
                Found found = findButton(root, name);
                try {
                    if (found.target() == null) {
                        String available = found.buttons().stream().map(Button::name)
                                .filter(text -> !text.isEmpty()).collect(Collectors.joining(", "));
                        problems.add(quote(name) + " not found (available: " + available + ")");
                        continue;
                    }
                    click(found.target().ref());
                    clicked.add(found.target().name());
                } finally {
                    release(found.buttons());
                }
                pause.accept(settle);
            } catch (RuntimeException | LinkageError e) {
                problems.add(quote(name) + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        String detail = clicked.isEmpty() ? "clicked nothing" : "clicked " + String.join(", ", clicked);
        if (!problems.isEmpty()) {
            return new Outcome(false, detail + "; " + String.join("; ", problems));
        }
        return new Outcome(true, detail);
    }

    /**
     * pyjab's {@code click()}: the element's one accessible action, or its {@code click} action when it
     * has several.
     */
    private void click(AccessBridge.JavaObject button) {
        List<String> actions = bridge.actions(button);
        String action;
        if (actions.isEmpty()) {
            throw new IllegalStateException("the button supports no accessible action");
        } else if (actions.size() == 1) {
            action = actions.get(0);
        } else {
            action = actions.stream().filter(name -> name.equalsIgnoreCase("click")).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "the button does not support action 'click' (it has " + actions + ")"));
        }
        if (!bridge.doAction(button, action)) {
            throw new IllegalStateException("doAccessibleActions(" + action + ") reported failure");
        }
    }

    private void release(List<Button> buttons) {
        for (Button button : buttons) {
            if (button.releasable()) {
                try {
                    bridge.release(button.ref());
                } catch (RuntimeException | LinkageError ignored) {
                    // Housekeeping only.
                }
            }
        }
    }

    // ----- helpers ----------------------------------------------------------------------

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Joins for at most {@code limit}; true if the thread ended. An interrupt ends the wait early. */
    private static boolean joinFor(Thread thread, Duration limit) {
        try {
            thread.join(Math.max(1, limit.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private static String quote(String name) {
        return "'" + name + "'";
    }

    private static long seconds(Duration duration) {
        return Math.round(duration.toMillis() / 1000.0);
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
