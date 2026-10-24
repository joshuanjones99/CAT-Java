package com.verifone.cat.pos.pinpad;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The {@code --fake-pinpad} pinpad: an in-process {@link PinpadDevice}, beside the fake POS.
 * Ported from the Python {@code petrotest.pos.pinpad_double}.
 *
 * <p><b>A double. It proves nothing about a pinpad.</b> Every display it shows is prefixed
 * {@code LOOPBACK/}, so that no one reads a synthetic prompt as a claim about what a real ViperPay
 * or Viper terminal shows (CLAUDE.md, evidence discipline). The prompts it raises without being
 * asked ("donate", "SWIPE CARD", "CASHBACK") are the corpus's own display keys, not observed text.
 *
 * <p>No server and no socket: the fake POS calls {@link #setDisplay} from its own thread and a
 * test waits from another. The waits are on a {@link Condition}, bounded by the prompt-wait budget,
 * never a sleep. Every display change is kept in order, and a wait looks only at what came after
 * the moment it began, so a prompt that is shown and replaced while a test acts is still seen.
 *
 * <p>The announcement the Python run prints at its end is {@link #BANNER_TITLE} and
 * {@link #BANNER_WARNING}; {@link #toString()} says the same in one line.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). This class is a test double, not hardware evidence.
 */
public final class PinpadDouble implements PinpadDevice {

    /** The idle display: the state a reset or a removed card returns to. */
    public static final String IDLE_PROMPT = "LOOPBACK/IDLE";

    /** The run-end banner title, as the Python plugin writes it. */
    public static final String BANNER_TITLE = "FAKE PINPAD";

    /** The run-end banner's warning, as the Python plugin writes it. */
    public static final String BANNER_WARNING =
            "WARNING: --fake-pinpad was used — every pinpad step ran against an in-process "
                    + "double (LOOPBACK/ text). This run is NOT evidence about a pinpad.";

    /** The Python {@code Timeouts.prompt_wait} default. */
    public static final Duration DEFAULT_PROMPT_WAIT = Duration.ofSeconds(30);

    private final String id;
    private final Duration promptWait;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    private final List<Shown> history = new ArrayList<>();
    private List<String> lines = new ArrayList<>(List.of(IDLE_PROMPT));
    private String prompt = IDLE_PROMPT;
    private String card;
    private boolean cardPresent;
    private String lastKey;
    private BigDecimal lastEntry;
    private Consumer<String> afterCard;
    private BiConsumer<String, String> afterKey;

    /** A double named "1" with the default prompt wait. */
    public PinpadDouble() {
        this("1", DEFAULT_PROMPT_WAIT);
    }

    public PinpadDouble(String id, Duration promptWait) {
        this.id = id;
        this.promptWait = promptWait == null ? DEFAULT_PROMPT_WAIT : promptWait;
        reset();
    }

    /**
     * Whether this session's pinpad is the double: {@code --fake-pinpad}, or {@code --fake-pos},
     * which implies it (the fake POS's MOP screens raise their prompts on this double).
     */
    public static boolean fakePinpadSelected(boolean fakePinpad, boolean fakePos) {
        return fakePinpad || fakePos;
    }

    @Override
    public String toString() {
        return "<PinpadDouble " + id + " (LOOPBACK, not a pinpad)>";
    }

    @Override
    public String id() {
        return id;
    }

    // -- state ----------------------------------------------------------------

    /** Back to idle, with no history and no chained callbacks. */
    public void reset() {
        lock.lock();
        try {
            lines = new ArrayList<>(List.of(IDLE_PROMPT));
            prompt = IDLE_PROMPT;
            card = null;
            cardPresent = false;
            lastKey = null;
            lastEntry = null;
            history.clear();
            afterCard = null;
            afterKey = null;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Shows {@code newLines} (and {@code newPrompt}), recording each change for the waits. The seam
     * the fake POS's MOP callbacks push through, standing in for the POS telling the pinpad what to
     * show.
     */
    public PinpadDisplay setDisplay(List<String> newLines, String newPrompt) {
        lock.lock();
        try {
            if (!newLines.equals(lines)) {
                lines = new ArrayList<>(newLines);
                history.add(Shown.ofLines(newLines));
            }
            if (newPrompt != null && !newPrompt.equals(prompt)) {
                prompt = newPrompt;
                history.add(Shown.ofPrompt(newPrompt));
            } else if (newPrompt != null) {
                prompt = newPrompt;
            }
            changed.signalAll();
            return display();
        } finally {
            lock.unlock();
        }
    }

    /** Called after every card action, with the action ("insert", "swipe" or "tap"). */
    public void setAfterCard(Consumer<String> callback) {
        lock.lock();
        try {
            afterCard = callback;
        } finally {
            lock.unlock();
        }
    }

    /** Called after every key, with the key and the prompt it answered. */
    public void setAfterKey(BiConsumer<String, String> callback) {
        lock.lock();
        try {
            afterKey = callback;
        } finally {
            lock.unlock();
        }
    }

    // -- reading --------------------------------------------------------------

    @Override
    public PinpadDisplay display() {
        lock.lock();
        try {
            return new PinpadDisplay(
                    id,
                    lines,
                    prompt,
                    card,
                    cardPresent,
                    lastKey,
                    lastEntry,
                    history.size());
        } finally {
            lock.unlock();
        }
    }

    // -- waiting --------------------------------------------------------------

    @Override
    public String atPrompt(String wanted, Duration timeout) {
        String needle = needle(wanted);
        lock.lock();
        try {
            if (shows(needle)) {
                return orElse(prompt, wanted);
            }
            return waitFor(history.size(), s -> s.holds(needle), wanted, timeout);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String expectPromptCleared(String wanted, Duration timeout) {
        String needle = needle(wanted);
        lock.lock();
        try {
            if (!shows(needle)) {
                return orElse(prompt, String.join(" ", lines));
            }
            return waitFor(
                    history.size(), s -> !s.holds(needle), wanted + " cleared", timeout);
        } finally {
            lock.unlock();
        }
    }

    // -- acting ---------------------------------------------------------------

    @Override
    public String insertCard(String label, String waitForPrompt, Duration timeout) {
        return act(() -> cardAction("insert", label), waitForPrompt, timeout);
    }

    @Override
    public String swipeCard(String label, String waitForPrompt, Duration timeout) {
        return act(() -> cardAction("swipe", label), waitForPrompt, timeout);
    }

    @Override
    public String tapCard(String label, String waitForPrompt, Duration timeout) {
        return act(() -> cardAction("tap", label), waitForPrompt, timeout);
    }

    @Override
    public void removeCard() {
        lock.lock();
        try {
            card = null;
            cardPresent = false;
            setDisplay(List.of(IDLE_PROMPT), IDLE_PROMPT);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String key(String pressed, String waitForPrompt, Duration timeout) {
        return act(() -> {
            String answered = prompt;
            lastKey = pressed;
            setDisplay(List.of("LOOPBACK/KEY " + pressed), "LOOPBACK/KEY_" + pressed);
            if (afterKey != null) {
                afterKey.accept(pressed, answered);
            }
        }, waitForPrompt, timeout);
    }

    @Override
    public String decimal(BigDecimal value, String waitForPrompt, Duration timeout) {
        return act(() -> {
            lastEntry = value;
            setDisplay(List.of("LOOPBACK/ENTRY " + value), "LOOPBACK/ENTRY");
        }, waitForPrompt, timeout);
    }

    // -- internals --------------------------------------------------------------

    private void cardAction(String action, String label) {
        card = label;
        cardPresent = "insert".equals(action);
        String shown = label == null || label.isEmpty() ? "UNLABELLED" : label;
        String upper = action.toUpperCase(Locale.ROOT);
        setDisplay(List.of("LOOPBACK/CARD " + upper, "LOOPBACK/" + shown), "LOOPBACK/CARD_" + upper);
        if (afterCard != null) {
            afterCard.accept(action);
        }
    }

    /** Acts, then waits for {@code waitForPrompt} among the changes the action made or later. */
    private String act(Runnable action, String waitForPrompt, Duration timeout) {
        lock.lock();
        try {
            int start = history.size();
            action.run();
            if (waitForPrompt == null) {
                return null;
            }
            String needle = needle(waitForPrompt);
            return waitFor(start, s -> s.holds(needle), waitForPrompt, timeout);
        } finally {
            lock.unlock();
        }
    }

    /** The first change at or after {@code start} that {@code match}es. The caller holds the lock. */
    private String waitFor(int start, Predicate<Shown> match, String what, Duration timeout) {
        Duration budget = timeout != null ? timeout : promptWait;
        long deadline = System.nanoTime() + budget.toNanos();
        while (true) {
            for (int i = start; i < history.size(); i++) {
                Shown shown = history.get(i);
                if (match.test(shown)) {
                    String text = shown.text();
                    return text.isEmpty() ? what : text;
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new WaitTimeout("pinpad double " + id + " prompt '" + what + "'");
            }
            try {
                changed.awaitNanos(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new WaitTimeout("pinpad double " + id + " prompt '" + what + "' (interrupted)");
            }
        }
    }

    /** Whether the current prompt or any display line contains {@code needle}. Lock held. */
    private boolean shows(String needle) {
        if (prompt != null && !prompt.isEmpty() && lower(prompt).contains(needle)) {
            return true;
        }
        return lower(String.join(" ", lines)).contains(needle);
    }

    private static String needle(String wanted) {
        return wanted.strip().toLowerCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** One change to the display: new lines, or a new prompt. */
    private record Shown(List<String> lines, String prompt) {

        static Shown ofLines(List<String> lines) {
            return new Shown(List.copyOf(lines), null);
        }

        static Shown ofPrompt(String prompt) {
            return new Shown(null, prompt);
        }

        String text() {
            return prompt != null ? prompt : String.join(" ", lines);
        }

        boolean holds(String needle) {
            return lower(text()).contains(needle);
        }
    }

    /** A wait ended before the wanted prompt showed (the Python's {@code TimeoutExpired}). */
    public static final class WaitTimeout extends RuntimeException {

        public WaitTimeout(String message) {
            super(message);
        }
    }
}
