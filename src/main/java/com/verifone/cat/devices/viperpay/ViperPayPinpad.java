package com.verifone.cat.devices.viperpay;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.pinpad.PinpadDisplay;

/**
 * {@link PinpadDevice} over Petro Simulator Suite's ViperPay sockets, driven the way PATS drives it.
 * Ported from the Python CAT's {@code petrotest.devices.viperpay.ViperPayPinpad}.
 *
 * <p>PATS's {@code PaymentTerminalSimManagerImpl} over {@code ViperPayCommunicator}:
 * <ul>
 *   <li><b>Displays come to us.</b> For every display change the simulator connects to the PATS
 *       host's port 9002, writes one {@code DisplayRequest} and closes. {@link #start()} listens there.</li>
 *   <li><b>Keys go to it.</b> One {@code ViperpayUserEvent} per key press, written to the simulator's
 *       port 9001 ({@code simulatorHost}:{@code simulatorPort}).</li>
 * </ul>
 * Each action waits until the current display's command accepts it, then sends one event. A display
 * is answered once: only a display that arrived after CAT's last answer may be answered next
 * (PETROCORE-36940 keyed one prompt and swiped into it 15 ms later). Waits are on the simulator's own
 * pushes, bounded by the prompt-wait budget, never by a fixed pause.
 *
 * <p>Known gaps, stated rather than hidden: {@link #removeCard()} and {@link #tapCard} raise
 * {@link DeviceUnavailable} (no ViperPay counterpart); {@link #swipeCard} is the insert, since PATS's
 * {@code slideCard} is the same {@code READ_CARD} action.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python port's live evidence (a key reaching the
 * simulator, PETROCORE-61334 on Reg101) is not repeated here; the card and entry bytes match JDK-written
 * fixtures, and no live simulator has received them.
 */
public final class ViperPayPinpad implements PinpadDevice, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ViperPayPinpad.class);

    /** PATS's {@code viperpay.sereverPort}: where the simulator sends displays. */
    public static final int DEFAULT_LISTEN_PORT = 9002;

    /** PATS's {@code viperpay.simulatorServerPort}: where key presses go. */
    public static final int DEFAULT_SIMULATOR_PORT = 9001;

    /** The Python {@code Timeouts.prompt_wait} default. */
    public static final Duration DEFAULT_PROMPT_WAIT = Duration.ofSeconds(30);

    /** The Python {@code Timeouts.http} default. */
    public static final Duration DEFAULT_HTTP = Duration.ofSeconds(15);

    /** The largest display stream the listener reads. A real DisplayRequest is a few hundred bytes. */
    static final int MAX_DISPLAY_BYTES = 1 << 20;

    private static final Duration ACCEPT_POLL = Duration.ofMillis(500);

    private static final Set<String> KEY_COMMANDS = Set.of("GET_ANY_KEY", "GET_ANY_KEY_OR_CARD", "GET_CONFIRMATION");
    private static final Set<String> CARD_COMMANDS = Set.of("READ_CARD", "GET_ANY_KEY_OR_CARD");
    private static final Set<String> ENTRY_COMMANDS = Set.of("GET_DECIMALS", "PROCESS_PIN", "GET_AMOUNT", "GET_CHAR");

    /** A display, and the sequence number it arrived at. */
    private record Arrived(int seq, Display display) {}

    private final String simulatorHost;
    private final String id;
    private final int requestedListenPort;
    private final int simulatorPort;
    private final Duration promptWait;
    private final Duration http;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    /** Each POP's current display. Per POP, so another POP's display does not make an answered one look new. */
    private final Map<String, Arrived> displays = new HashMap<>();
    /** Counts displays as they arrive. */
    private int seq;
    /** The {@code seq} when CAT last answered a display; only a later one may be answered next. */
    private int answered = -1;

    private volatile boolean closed;
    private ServerSocket server;

    /** A pinpad on {@code simulatorHost} with PATS's ports and the default budgets. */
    public ViperPayPinpad(String simulatorHost) {
        this(simulatorHost, "1", DEFAULT_LISTEN_PORT, DEFAULT_SIMULATOR_PORT, null, null);
    }

    /**
     * @param simulatorHost the host the simulator runs on, by name (not an address literal in this repo)
     * @param pinpadId      the pinpad's id; a POP is matched by its number, so "1" and "001" are one POP
     * @param listenPort    where displays arrive; 0 binds an ephemeral port, read back by {@link #listenPort()}
     * @param simulatorPort where key events go
     * @param promptWait    the default wait for a prompt, or null for {@link #DEFAULT_PROMPT_WAIT}
     * @param http          the socket timeout, or null for {@link #DEFAULT_HTTP}
     */
    public ViperPayPinpad(
            String simulatorHost,
            String pinpadId,
            int listenPort,
            int simulatorPort,
            Duration promptWait,
            Duration http) {
        if (simulatorHost == null || simulatorHost.isBlank()) {
            throw new IllegalArgumentException("the ViperPay simulator's host is required");
        }
        this.simulatorHost = simulatorHost;
        this.id = pinpadId == null ? "1" : pinpadId;
        this.requestedListenPort = listenPort;
        this.simulatorPort = simulatorPort;
        this.promptWait = positive("promptWait", promptWait, DEFAULT_PROMPT_WAIT);
        this.http = positive("http", http, DEFAULT_HTTP);
    }

    /**
     * Binds the display port and starts listening. Fails with {@link IOException} if something else
     * holds it (PATS, usually).
     */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        ServerSocket listener = new ServerSocket(requestedListenPort);
        listener.setSoTimeout((int) ACCEPT_POLL.toMillis()); // lets the accept loop notice close()
        server = listener;
        Thread thread = new Thread(() -> acceptLoop(listener), "viperpay-displays");
        thread.setDaemon(true);
        thread.start();
    }

    /** The port displays arrive on, once {@link #start()} has bound it. */
    public synchronized int listenPort() {
        return server != null ? server.getLocalPort() : requestedListenPort;
    }

    /** The host the simulator runs on, as this pinpad was given it. */
    public String simulatorHost() {
        return simulatorHost;
    }

    /** The simulator's port that key events go to (9001 on a rig). */
    public int simulatorPort() {
        return simulatorPort;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                LOG.debug("closing the ViperPay display port: {}", e.getMessage());
            }
        }
    }

    @Override
    public String id() {
        return id;
    }

    // -- listener ---------------------------------------------------------------

    private void acceptLoop(ServerSocket listener) {
        while (!closed) {
            Socket conn;
            try {
                conn = listener.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return; // closed
            }
            Display display = readDisplay(conn);
            if (display != null) {
                publish(display);
            }
        }
    }

    /** One display from one connection, or null for a probe or a stream this reader cannot parse. */
    private Display readDisplay(Socket conn) {
        try (conn) {
            conn.setSoTimeout((int) http.toMillis());
            byte[] data = conn.getInputStream().readNBytes(MAX_DISPLAY_BYTES + 1);
            if (data.length > MAX_DISPLAY_BYTES) {
                LOG.warn("pinpad {}: dropped a display stream longer than {} bytes", id, MAX_DISPLAY_BYTES);
                return null;
            }
            return Display.decode(data);
        } catch (IOException | IllegalArgumentException e) {
            return null; // a probe that connected and closed, or a stream this reader cannot parse
        } catch (RuntimeException e) {
            // Any other malformed stream drops this connection, never the listener: a dead thread
            // would leave the port bound and every later display unheard.
            LOG.error("pinpad {}: dropped a display this reader cannot parse", id, e);
            return null;
        }
    }

    private void publish(Display display) {
        String pop = popKey(display.popId());
        locked(() -> {
            seq++;
            displays.put(pop, new Arrived(seq, display));
            changed.signalAll();
            return null;
        });
    }

    // -- PinpadDevice: reading ----------------------------------------------------

    @Override
    public PinpadDisplay display() {
        return locked(() -> {
            Display current = currentNow();
            List<String> lines = current == null ? List.of()
                    : current.lines().stream().map(line -> line == null ? "" : line).toList();
            String prompt = current == null ? null : current.text();
            return new PinpadDisplay(id, lines, prompt, null, false, null, null, seq);
        });
    }

    @Override
    public String atPrompt(String prompt, Duration timeout) {
        String what = "pinpad " + id + " prompt '" + prompt + "'";
        Display shown = locked(() -> waitFor(
                () -> currentIf(d -> PromptText.matchesPrompt(d.lines(), prompt), answered),
                budget(timeout),
                () -> what + " (showing '" + showing(PromptText::bracketedText) + "')"));
        return shown.text();
    }

    /**
     * {@code WAIT_FOR_PROMPT} with {@code DISPLAY_KEY_EXACT_MATCH=YES}: waits until the whole display,
     * as {@link PromptText#exactText} renders it, equals {@code prompt} ignoring case. Returns that text.
     *
     * <p>UNVERIFIED AGAINST HARDWARE: no live ViperPay display has been matched exactly yet.
     */
    public String atExactPrompt(String prompt, Duration timeout) {
        String what = "pinpad " + id + " exact prompt '" + prompt + "'";
        Display shown = locked(() -> waitFor(
                () -> currentIf(d -> PromptText.matchesExactly(d.lines(), prompt), answered),
                budget(timeout),
                () -> what + " (showing '" + showing(PromptText::exactText) + "'"
                        + currentLines() + ")"));
        return PromptText.exactText(shown.lines());
    }

    @Override
    public String expectPromptCleared(String prompt, Duration timeout) {
        String needle = prompt.strip().toLowerCase(Locale.ROOT);
        String what = "pinpad " + id + " clearing prompt '" + prompt + "'";
        Display shown = locked(() -> waitFor(
                () -> currentIf(d -> !lower(d.text()).contains(needle), -1),
                budget(timeout),
                () -> what));
        return shown.text();
    }

    // -- PinpadDevice: acting -----------------------------------------------------

    @Override
    public String key(String key, String waitForPrompt, Duration timeout) {
        String wanted = key.strip().toLowerCase(Locale.ROOT);
        Predicate<Display> onOffer = d -> inSet(KEY_COMMANDS, d.commandType())
                && ("cancel".equals(wanted) || offered(d).contains(wanted));
        return act(onOffer, "offering key '" + key + "'",
                pop -> UserEvent.key(pop, key), waitForPrompt, timeout);
    }

    @Override
    public String insertCard(String label, String waitForPrompt, Duration timeout) {
        return act(d -> inSet(CARD_COMMANDS, d.commandType()), "reading a card",
                pop -> UserEvent.card(pop, label), waitForPrompt, timeout);
    }

    /** PATS's {@code slideCard} is the same {@code READ_CARD} action as the insert. */
    @Override
    public String swipeCard(String label, String waitForPrompt, Duration timeout) {
        return insertCard(label, waitForPrompt, timeout);
    }

    @Override
    public String tapCard(String label, String waitForPrompt, Duration timeout) {
        throw new DeviceUnavailable(
                "ViperPayPinpad.tapCard()",
                "the ViperPay client has no tap action (the Python client has none either)");
    }

    @Override
    public void removeCard() {
        throw new DeviceUnavailable(
                "ViperPayPinpad.removeCard()",
                "PATS's REMOVE_CARD goes to VATS only; the ViperPay simulator has no card to remove");
    }

    @Override
    public String decimal(BigDecimal value, String waitForPrompt, Duration timeout) {
        // Fixed-point, never an exponent: PATS sends "123456", or a 19-digit loyalty number.
        return decimalText(value.toPlainString(), waitForPrompt, timeout);
    }

    /**
     * Enters {@code text} exactly as written, which keeps a leading zero that a {@link BigDecimal}
     * would lose.
     *
     * @throws IllegalArgumentException if {@code text} is not an entry (see {@link PinpadDevice#ENTRY_TEXT})
     */
    @Override
    public String decimalText(String text, String waitForPrompt, Duration timeout) {
        if (text == null || !ENTRY_TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException("not an entry: " + text);
        }
        return act(d -> inSet(ENTRY_COMMANDS, d.commandType()), "asking for an entry",
                pop -> UserEvent.entry(pop, text), waitForPrompt, timeout);
    }

    // -- internals ----------------------------------------------------------------

    /**
     * Waits for a display that {@code accepts} the action, sends the event, then optionally waits for
     * {@code waitForPrompt} among the displays that arrive after the send.
     */
    private String act(
            Predicate<Display> accepts,
            String what,
            Function<String, UserEvent> event,
            String waitForPrompt,
            Duration timeout) {
        Duration budget = budget(timeout);
        String label = "pinpad " + id + " " + what;
        Display current = locked(() -> waitFor(() -> currentIf(accepts, answered), budget, () -> label));
        int sentAt = locked(() -> {
            answered = seq;
            return answered;
        });
        send(event.apply(current.popId()).encode());
        if (waitForPrompt == null) {
            return null;
        }
        String needle = waitForPrompt.strip().toLowerCase(Locale.ROOT);
        Display shown = locked(() -> waitFor(
                () -> currentIf(d -> lower(d.text()).contains(needle), sentAt),
                budget,
                () -> "pinpad " + id + " prompt '" + waitForPrompt + "'"));
        return shown.text();
    }

    private void send(byte[] payload) {
        try (Socket conn = new Socket()) {
            conn.connect(new InetSocketAddress(simulatorHost, simulatorPort), (int) http.toMillis());
            conn.setSoTimeout((int) http.toMillis());
            conn.getOutputStream().write(payload);
        } catch (IOException e) {
            throw new DeviceUnreachable(
                    "ViperPay simulator key port " + simulatorHost + ":" + simulatorPort, e.getMessage(), e);
        }
    }

    /**
     * The POP's current display if it arrived after {@code after} and {@code test} accepts it, else
     * null. Caller holds the lock.
     */
    private Display currentIf(Predicate<Display> test, int after) {
        Arrived arrived = displays.get(popKey(id));
        if (arrived != null && arrived.seq() > after && test.test(arrived.display())) {
            return arrived.display();
        }
        return null;
    }

    /** The POP's current display, whenever it arrived. Caller holds the lock. */
    private Display currentNow() {
        Arrived arrived = displays.get(popKey(id));
        return arrived == null ? null : arrived.display();
    }

    private String showing(Function<List<String>, String> render) {
        Display current = currentNow();
        return current == null ? "nothing" : render.apply(current.lines());
    }

    private String currentLines() {
        Display current = currentNow();
        if (current == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(", lines [");
        for (int i = 0; i < current.lines().size(); i++) {
            String line = current.lines().get(i);
            text.append(i > 0 ? ", " : "").append(line == null ? "None" : "'" + line + "'");
        }
        return text.append(']').toString();
    }

    /**
     * Waits on the changed condition until {@code find} yields a display, or the budget runs out. The
     * lock is held on entry and on return. {@code find} is re-checked after every wake, and once more
     * after the deadline, so a display that is already there returns at once.
     */
    private <T> T waitFor(Supplier<T> find, Duration budget, Supplier<String> what) {
        long deadline = System.nanoTime() + budget.toNanos();
        T found;
        while ((found = find.get()) == null) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new PromptTimeout(what.get(), budget);
            }
            try {
                changed.awaitNanos(left);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new PromptTimeout(what.get() + " (interrupted)", budget);
            }
        }
        return found;
    }

    private Duration budget(Duration timeout) {
        return timeout != null ? timeout : promptWait;
    }

    private <T> T locked(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** The display's offered keys, trimmed and lower-cased; null keys are skipped. */
    private static Set<String> offered(Display display) {
        Set<String> keys = new HashSet<>();
        for (String key : display.keys()) {
            if (key != null) {
                keys.add(key.strip().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    private static boolean inSet(Set<String> set, String value) {
        return value != null && set.contains(value);
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /** The EPS says POP "001" where PATS and the rig say "1". */
    static String popKey(String popId) {
        String key = popId.strip().replaceFirst("^0+", "");
        return key.isEmpty() ? "0" : key;
    }

    private static Duration positive(String name, Duration value, Duration fallback) {
        Duration chosen = value == null ? fallback : value;
        if (chosen.isZero() || chosen.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: a zero or negative wait never ends");
        }
        return chosen;
    }

    /** The number of displays that have arrived, for tests that wait on the listener. */
    int sequence() {
        return locked(() -> seq);
    }

    /** Waits, bounded, until {@code count} displays have arrived. Returns whether they did. */
    boolean awaitSequence(int count, Duration timeout) {
        Duration budget = budget(timeout);
        try {
            locked(() -> waitFor(() -> seq >= count ? Boolean.TRUE : null, budget, () -> "displays " + count));
            return true;
        } catch (PromptTimeout timedOut) {
            return false;
        }
    }
}
