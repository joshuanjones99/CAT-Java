package com.verifone.cat.devices.vats;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pos.pinpad.PinpadDisplay;
import com.verifone.cat.rig.Rig;

/**
 * A physical Viper pinpad as a {@link com.verifone.cat.pos.pinpad.PinpadDevice}: CAT's own terminal connector
 * (DESIGN.md section 7.4). A port of the Python CAT's {@code petrotest.devices.vats.pinpad.VatsPinpad}.
 *
 * <p>The chain, all started by CAT ({@link VatsSession}):
 * <pre>
 *     VatsPinpad --JSON lines--&gt; agent.py (SDK's Python 3.9 + Verifone's VATS SDK)
 *                                   +-KM6-&gt; KM4 relay (vats_connector.py) -VATS-&gt; terminal:22001
 *     VatsPinpad --CSSP--&gt; BTT card server :9050 --&gt; SmartLink/SmartWave probe in the terminal
 * </pre>
 *
 * <p>PATS's semantics, from {@code patslib} and PATS's Java ({@code VatsRequestBuilder},
 * {@code PaymentTerminalResponseValidator}), with the Python CAT's deliberate differences:
 * <ul>
 *   <li><b>Confirmed, not fire-and-forget.</b> A soft key that never appears, a card the probe refuses, or a swipe the
 *       terminal rejects fails the step that caused it.</li>
 *   <li><b>No fixed sleeps.</b> Every wait is on a terminal event or a reply.</li>
 *   <li><b>Prompts match as a case-insensitive substring of any display region</b>, like every other pinpad.</li>
 *   <li><b>A chip card with no image fails up front</b> ({@link CardImages}), rather than inserting whatever the probe
 *       last held.</li>
 *   <li><b>Digits are keyed in one sequence</b>, {@code user.enter_keys("4315<CONFIRM>")}.</li>
 *   <li><b>A chip the terminal could not read is taken out and inserted again</b>, up to {@link #CHIP_RETRIES}
 *       times, as a cashier would; and a DECLINED ends a wait for anything else at once.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's relay, agent {@code hello}/{@code screen}/{@code wait}, BTT's
 * card server start and a CSSP load/stop ran live on 2026-10-06, and Charity-era sales later; nothing here has run on a
 * rig, only against an in-memory agent and a stand-in SDK.
 */
public final class VatsPinpad implements PhysicalPinpad, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VatsPinpad.class);

    /** How long a tapped card is left on the probe for the terminal to read it. */
    static final int TAP_READ_MS = 5000;

    /**
     * How long the terminal gets to leave the prompt a CONFIRM answered ({@link #enterDigits}). In 29 sales of run
     * 3f6a7b3223a2 (2026-10-13) the chip transaction ended 0.5-2 s after it; in the 3 it never did, the M400 was still
     * on it 12 and 20 s later, when the card came out.
     */
    public static final Duration CONFIRM_TAKEN_WAIT = Duration.ofSeconds(4);

    /** What the M400 shows when it could not read the chip (run 76fc392eb597, apps 6.9.1.18-HF1). */
    public static final String CHIP_NOT_READ = "(?i)chip not read";

    /** How many times an unread card is taken out and inserted again before the wait fails. The EPS allows three. */
    public static final int CHIP_RETRIES = 2;

    /** What the terminal shows while it still works on a card: {@link #settle} waits these out. */
    public static final String BUSY = "(?i)please wait|processing|authori[sz]ing|one moment";

    /** The terminal turning the payment down. */
    public static final String DECLINED = "(?i)declined";

    /** How long the screen must stay unchanged, with nothing busy on it, to count as at rest. */
    public static final int SETTLE_QUIET_MS = 1000;

    /** How long {@link #abortPayment} waits on BTT's reply to its STOP (a live one answers in 0.12 s). */
    public static final Duration ABORT_CARD_STOP_WAIT = Duration.ofSeconds(3);

    private static final Pattern DECLINED_JAVA = Pattern.compile("declined", Pattern.CASE_INSENSITIVE);
    private static final Duration LEFT_POLL = Duration.ofMillis(250);

    /** The contact card in the reader, for a reinsert after CHIP NOT READ. */
    private record Inserted(String label, Path path) {}

    private final VatsAgent agent;
    private final Rig.Vats config;
    private final CardImages images;
    private final Path msrFile;
    private final Duration promptWait;
    private final String cardServerProblem;
    private final Pauser pauser;
    private final String id = "1";
    private Duration confirmTakenWait = CONFIRM_TAKEN_WAIT;
    private CardServer cardServer;
    private Inserted inserted;
    private volatile Long declinedAt;

    /**
     * @param promptWait        the rig's {@code timeouts.prompt_wait}
     * @param cardServerProblem why BTT's card server is not there (the step's detail), or null when it is: a card load
     *                          then fails with it
     */
    public VatsPinpad(VatsAgent agent, Rig.Vats config, CardImages images, Path msrFile, Duration promptWait,
            String cardServerProblem) {
        this(agent, config, images, msrFile, promptWait, cardServerProblem, Pauser.REAL);
    }

    VatsPinpad(VatsAgent agent, Rig.Vats config, CardImages images, Path msrFile, Duration promptWait,
            String cardServerProblem, Pauser pauser) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.config = Objects.requireNonNull(config, "config");
        this.images = images;
        this.msrFile = msrFile;
        this.promptWait = promptWait == null ? Duration.ofSeconds(30) : promptWait;
        this.cardServerProblem = cardServerProblem;
        this.pauser = pauser;
    }

    /** For tests: the Python's tests set {@code CONFIRM_TAKEN_WAIT} to 1 s. */
    void confirmTakenWait(Duration wait) {
        this.confirmTakenWait = wait;
    }

    @Override
    public String toString() {
        return "<VatsPinpad " + config.host() + ":" + config.port() + ">";
    }

    @Override
    public String id() {
        return id;
    }

    // -- reading ----------------------------------------------------------------------------

    @Override
    public PinpadDisplay display() {
        List<String> lines = VatsAgent.lines(agent.call("screen"));
        String prompt = lines.isEmpty() ? null : String.join(" ", lines);
        return new PinpadDisplay(id, lines, prompt, null, false, null, null, 0);
    }

    /**
     * Whether the terminal's reader holds a card ({@code agent.cardin}), or null when it does not say: no answer, an
     * SDK without the call, or a terminal that does not report a Python VATS PC. Never throws.
     */
    public Boolean cardPresent() {
        int present;
        try {
            Object value = agent.call("cardin").get("present");
            present = value instanceof Number n ? n.intValue() : -1;
        } catch (RuntimeException e) {
            return null;
        }
        return present < 0 ? null : present > 0;
    }

    /**
     * Takes out a card the terminal holds and this PC never inserted; what was done, or "" when the reader is empty or
     * does not say. Never throws.
     *
     * <p>The M400 keeps a simulated card in after the session that inserted it has gone, and a fresh agent's KM4
     * believes none is in, so a bare {@code remove} is dropped: on the lab M400 on 2026-10-13 {@code cardin} stayed 1
     * after one, and went 0 after an insert then a remove. Meanwhile every register login's {@code RemoveICCCard} goes
     * unanswered and the EPS turns each payment away ("Eps Busy", run 3f6a7b3223a2).
     */
    public String clearStuckCard() {
        if (!Boolean.TRUE.equals(cardPresent())) {
            return "";
        }
        try {
            if (!VatsAgent.truthy(agent.call("card_state"), "state")) {
                agent.call("insert");
            }
            agent.call("remove");
        } catch (RuntimeException e) {
            return "a card was in the terminal's reader; clearing it failed (" + e.getMessage() + ")";
        }
        if (Boolean.TRUE.equals(cardPresent())) {
            return "a card was in the terminal's reader and is still in after insert + remove";
        }
        return "a card was in the terminal's reader, left by an earlier session; removed";
    }

    /** Keys sent and not yet taken by the terminal ({@code agent.keybuf}), or null when unknown. Never throws. */
    public String pendingKeys() {
        try {
            Object keys = agent.call("keybuf").get("keys");
            return keys == null ? "" : String.valueOf(keys);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private double budget(Duration timeout) {
        return seconds(timeout == null ? promptWait : timeout);
    }

    /**
     * Waits for {@code prompt}. <b>Not PATS:</b> a chip the terminal could not read is taken out and inserted again, up
     * to {@link #CHIP_RETRIES} times, and the wait goes on in what is left of its budget. A decline ends a wait for
     * anything else at once.
     */
    @Override
    public String atPrompt(String prompt, Duration timeout) {
        double budget = budget(timeout);
        long deadline = System.nanoTime() + (long) (budget * 1e9);
        int retries = inserted != null ? CHIP_RETRIES : 0;
        String target = pattern(prompt);
        boolean watchDecline = inserted != null && !DECLINED_JAVA.matcher(prompt).find();
        while (true) {
            double left = Math.max((deadline - System.nanoTime()) / 1e9, 0.1);
            List<String> others = new ArrayList<>();
            if (retries > 0) {
                others.add(CHIP_NOT_READ);
            }
            if (watchDecline) {
                others.add(DECLINED);
            }
            List<String> all = new ArrayList<>();
            all.add(target);
            all.addAll(others);
            Map<String, Object> result = agent.call("wait", left,
                    args("pattern", anyOf(all), "timeout_ms", (int) (left * 1000)));
            String shown = String.join(" | ", VatsAgent.lines(result));
            if (!VatsAgent.truthy(result, "status")) {
                throw new VatsTimeout("pinpad prompt " + PyText.repr(prompt) + " (showing: "
                        + (shown.isEmpty() ? "nothing" : shown) + ")", budget);
            }
            if (others.isEmpty()) {
                return shown;
            }
            Object shownList = agent.call("shows", args("patterns", all)).get("shown");
            List<?> shows = shownList instanceof List<?> l ? l : List.of();
            Map<String, Boolean> hit = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(all.size(), shows.size()); i++) {
                hit.putIfAbsent(all.get(i), VatsAgent.truthy(shows.get(i)));
            }
            if (hit.getOrDefault(target, shows.isEmpty())) {
                return shown;
            }
            if (hit.getOrDefault(DECLINED, false)) {
                declinedAt = System.nanoTime();
                throw new DeviceError("the terminal declined the payment while CAT waited for " + PyText.repr(prompt));
            }
            if (hit.getOrDefault(CHIP_NOT_READ, false)) {
                retries -= 1;
                reinsert(prompt);
            }
            // else the screen moved on between the match and the look: wait again
        }
    }

    /**
     * Takes out the card the terminal could not read and inserts it again, once the EPS asks for it ("Remove Card")
     * and is ready to read again.
     */
    private void reinsert(String waitingFor) {
        Inserted card = inserted != null ? inserted : new Inserted("", Path.of(""));
        LOG.warn("pinpad: the terminal could not read {} (CHIP NOT READ) while waiting for {}; taking it out and "
                + "inserting it again", PyText.repr(card.label()), PyText.repr(waitingFor));
        double budget = seconds(promptWait);
        agent.call("wait", budget, args("pattern", "(?i)remove card", "timeout_ms", (int) (budget * 1000)));
        agent.call("remove");
        agent.call("wait", budget, args("pattern", "(?i)insert|tap or swipe", "timeout_ms", (int) (budget * 1000)));
        server().load(card.path());
        if (!VatsAgent.truthy(agent.call("insert"), "status")) {
            throw new DeviceError("the terminal did not register the reinserted card " + PyText.repr(card.label()));
        }
    }

    /**
     * <b>Not PATS: what replaces its pause after a pinpad action.</b> Waits, at most {@code budget}, until the terminal
     * shows nothing busy ({@link #BUSY}) and its screen has not changed for {@link #SETTLE_QUIET_MS}; false if the
     * budget ran out first.
     */
    @Override
    public boolean settle(Duration budget) {
        double seconds = budget == null ? 0.0 : seconds(budget);
        if (seconds <= 0) {
            return false;
        }
        Map<String, Object> result = agent.call("settle", seconds, args("busy", BUSY, "quiet_ms", SETTLE_QUIET_MS,
                "timeout_ms", (int) (seconds * 1000), "watch", DECLINED));
        if (VatsAgent.truthy(result, "seen")) {
            declinedAt = System.nanoTime();
            LOG.warn("pinpad: the terminal showed DECLINED");
        }
        return VatsAgent.truthy(result, "status");
    }

    @Override
    public OptionalLong declinedAtNanos() {
        Long at = declinedAt;
        return at == null ? OptionalLong.empty() : OptionalLong.of(at);
    }

    /** For tests: the Python's tests set {@code declined_at} directly. */
    void declinedAt(Long nanos) {
        declinedAt = nanos;
    }

    @Override
    public String expectPromptCleared(String prompt, Duration timeout) {
        double budget = budget(timeout);
        Map<String, Object> result = agent.call("wait", budget,
                args("pattern", pattern(prompt), "timeout_ms", (int) (budget * 1000), "gone", true));
        String shown = String.join(" | ", VatsAgent.lines(result));
        if (!VatsAgent.truthy(result, "status")) {
            throw new VatsTimeout("pinpad still showing " + PyText.repr(prompt), budget);
        }
        return shown;
    }

    private String then(String waitForPrompt, Duration timeout) {
        return waitForPrompt == null || waitForPrompt.isEmpty() ? null : atPrompt(waitForPrompt, timeout);
    }

    // -- cards --------------------------------------------------------------------------------

    private CardServer server() {
        if (cardServerProblem != null && !cardServerProblem.isEmpty()) {
            throw new DeviceUnreachable("BTT's card server", cardServerProblem);
        }
        if (cardServer == null) {
            cardServer = new CardServer(Btt.LOOPBACK, config.cardServerPort());
        }
        return cardServer;
    }

    private Path image(String label, String image) {
        String name = image == null ? "" : image.strip();
        if (name.isEmpty()) {
            name = config.cardImages().getOrDefault(label, "");
        }
        if (name.isEmpty()) {
            throw VatsSdk.unavailable("card " + PyText.repr(label), "the case names no CARD_SIMULATION_IMAGE_FILE "
                    + "and the rig's vats.card_images has no entry for this label");
        }
        Path path = images.resolve(name);
        if (path == null) {
            throw VatsSdk.unavailable("card image " + PyText.repr(name), "not found in " + images.describe());
        }
        return path;
    }

    @Override
    public String insertCard(String label, String waitForPrompt, Duration timeout) {
        return insertCard(label, null, waitForPrompt, timeout);
    }

    /** PATS's {@code INSERT_OR_SLIDE_CARD}: dip, tap or swipe, by what {@code label} names. */
    @Override
    public String insertCard(String label, String image, String waitForPrompt, Duration timeout) {
        declinedAt = null; // a new card, a new payment: dipped, tapped or swiped
        Cards.Kind kind = Cards.kindOf(label);
        if (kind == Cards.Kind.MAGSTRIPE) {
            return swipeCard(label, waitForPrompt, timeout);
        }
        if (!kind.value().equals(config.interfaceKind())) {
            throw VatsSdk.unavailable("a " + kind.value() + " card (" + PyText.repr(label) + ")", "BTT's card server "
                    + "simulates one interface at a time and this rig's vats.interface is "
                    + PyText.repr(config.interfaceKind()));
        }
        Path path = image(label, image);
        CardServer server = server();
        server.load(path);
        if (kind == Cards.Kind.CONTACTLESS) {
            // cleanAndLoadCOLLISImageSimulation: present, let it be read, take it away.
            agent.call("update", TAP_READ_MS / 1000.0, args("timeout_ms", TAP_READ_MS));
            server.stop();
        } else if (!VatsAgent.truthy(agent.call("insert"), "status")) {
            throw new DeviceError("the terminal did not register the inserted card " + PyText.repr(label));
        } else {
            // No cardPresent() read here: cardin cost ~1.2 s per insert (run 76fc392eb597), and a card the terminal
            // cannot read shows CHIP NOT READ, which atPrompt answers by inserting it again.
            inserted = new Inserted(label, path);
        }
        return then(waitForPrompt, timeout);
    }

    @Override
    public String tapCard(String label, String waitForPrompt, Duration timeout) {
        return insertCard(label, null, waitForPrompt, timeout);
    }

    @Override
    public String swipeCard(String label, String waitForPrompt, Duration timeout) {
        Cards.Tracks tracks = Cards.msrTracks(label, msrFile);
        if (tracks == null) {
            throw VatsSdk.unavailable("magstripe card " + PyText.repr(label), "no <Card> labelled so in " + msrFile);
        }
        Map<String, Object> result = agent.call("swipe", 10.0,
                args("track1", tracks.track1(), "track2", tracks.track2(), "track3", tracks.track3()));
        if (!VatsAgent.truthy(result, "status")) {
            throw new DeviceError("the terminal rejected the swipe of " + PyText.repr(label));
        }
        return then(waitForPrompt, timeout);
    }

    /** {@code vatsapi.removeCard}: wait (bounded) for "Remove Card", remove, confirm. */
    @Override
    public void removeCard() {
        inserted = null;
        double budget = seconds(promptWait);
        agent.call("wait", budget, args("pattern", "(?i)remove card", "timeout_ms", (int) (budget * 1000)));
        agent.call("remove");
        if (VatsAgent.truthy(agent.call("card_state"), "state")) {
            agent.call("remove"); // PATS always removes twice; once more only if needed
        }
        if (Boolean.TRUE.equals(cardPresent())) {
            LOG.warn("pinpad: the terminal still reports a card in its reader after removal");
            String done = clearStuckCard();
            if (!done.isEmpty()) {
                LOG.warn("pinpad: {}", done);
            }
        }
        if (cardServer != null) {
            cardServer.stop();
        }
    }

    /**
     * <b>Not a PATS step: recovery.</b> Ends whatever the terminal is doing; what was done. CANCEL on the terminal, then
     * the card out if one is in, then BTT's simulated card stopped. A sale whose payment the terminal still holds
     * cannot be voided: on Reg101 the M400 sat at "Remove Card" with an Amex inserted, Void Ticket left "Error Correct"
     * up, and the run drained (run 79d2c092eb4d, 2026-10-12). Best effort: each part is tried whatever the one before
     * did. On an idle terminal CANCEL is a key nothing waits for.
     */
    @Override
    public List<String> abortPayment() {
        inserted = null;
        List<String> done = new ArrayList<>();
        try {
            if (VatsAgent.truthy(agent.call("keys", args("keys", "<CANCEL>")).getOrDefault("status", true))) {
                done.add("CANCEL");
            }
        } catch (RuntimeException e) {
            done.add("CANCEL failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        boolean told;
        try {
            told = VatsAgent.truthy(agent.call("card_state"), "state");
        } catch (RuntimeException e) {
            told = true; // unknown: try the removal anyway
        }
        if (!told) {
            // This PC inserted nothing, so a bare remove is dropped; a card the reader still holds needs an insert.
            String cleared = clearStuckCard();
            if (!cleared.isEmpty()) {
                done.add(cleared);
            }
        } else {
            try {
                if (VatsAgent.truthy(agent.call("remove").getOrDefault("status", true))) {
                    done.add("card removed");
                }
            } catch (RuntimeException e) {
                done.add("card removed failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
        }
        if (cardServer != null) {
            try {
                cardServer.stop(ABORT_CARD_STOP_WAIT);
                done.add("simulated card stopped");
            } catch (RuntimeException e) {
                done.add("simulated card stop failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
        }
        return done;
    }

    // -- keys ---------------------------------------------------------------------------------

    /** PATS's {@code GET_ANY_KEY}: press the soft key whose label is {@code key}. */
    @Override
    public String key(String key, String waitForPrompt, Duration timeout) {
        double budget = budget(timeout);
        Map<String, Object> result = agent.call("press", budget,
                args("label", key, "timeout_ms", (int) (budget * 1000)));
        if (!VatsAgent.truthy(result, "status")) {
            String shown = String.join(" | ", VatsAgent.lines(result));
            throw new VatsTimeout("pinpad soft key " + PyText.repr(key) + " (showing: "
                    + (shown.isEmpty() ? "nothing" : shown) + ")", budget);
        }
        return then(waitForPrompt, timeout);
    }

    /**
     * PATS's {@code GET_DECIMAL}: key {@code digits}, then CONFIRM. Leading zeros kept (a PIN). Then the screen must
     * leave the prompt the keys answered within {@link #CONFIRM_TAKEN_WAIT}; if it is still there, CONFIRM goes once
     * more. In run 3f6a7b3223a2 three sales stayed in their chip transaction after the keys until the card was pulled;
     * a lost CONFIRM is the reading that fits all three. UNVERIFIED AGAINST HARDWARE: that reading, and the second
     * CONFIRM, have not been seen on a rig. Either way the screen is logged.
     */
    @Override
    public String enterDigits(String digits, String waitForPrompt, Duration timeout) {
        StringBuilder only = new StringBuilder();
        digits.chars().filter(Character::isDigit).forEach(c -> only.append((char) c));
        String keys = only.toString();
        List<String> before = promptLines(display().lines());
        if (!VatsAgent.truthy(agent.call("keys", args("keys", keys + "<CONFIRM>")), "status")) {
            throw new DeviceError("the terminal did not take the keys " + PyText.repr(keys));
        }
        if (!before.isEmpty() && !left(before)) {
            // The keys the terminal has not pulled yet tell a CONFIRM still queued on the PC side from one it took
            // and ignored.
            String shown = String.join(" | ", display().lines());
            LOG.warn("pinpad still on {} {}s after keys + CONFIRM (showing: {}; keys not yet taken by the terminal: "
                    + "{}); CONFIRM again", PyText.repr(String.join(" | ", before)),
                    String.format(Locale.ROOT, "%.0f", seconds(confirmTakenWait)), shown.isEmpty() ? "nothing" : shown,
                    pending(pendingKeys()));
            agent.call("keys", args("keys", "<CONFIRM>"));
            if (left(before)) {
                LOG.info("pinpad left {} after the second CONFIRM", PyText.repr(String.join(" | ", before)));
            } else {
                LOG.warn("pinpad still on {} after a second CONFIRM (keys not yet taken: {}); left as it is",
                        PyText.repr(String.join(" | ", before)), pending(pendingKeys()));
            }
        }
        return then(waitForPrompt, timeout);
    }

    /** Whether the screen moves off {@code prompt} ({@link #promptLines}) within {@link #CONFIRM_TAKEN_WAIT}. */
    private boolean left(List<String> prompt) {
        return pauser.until(() -> !promptLines(display().lines()).equals(prompt), confirmTakenWait, LEFT_POLL);
    }

    @Override
    public String decimal(BigDecimal value, String waitForPrompt, Duration timeout) {
        return enterDigits(value.toPlainString(), waitForPrompt, timeout);
    }

    /** The text as written: its digits, then CONFIRM ({@link #enterDigits}). */
    @Override
    public String decimalText(String text, String waitForPrompt, Duration timeout) {
        if (text == null || !ENTRY_TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException("not a decimal entry: " + PyText.repr(String.valueOf(text)));
        }
        return enterDigits(text, waitForPrompt, timeout);
    }

    /** PATS's {@code VALIDATE_PINPAD_COMM_CONFIG} read: the terminal's network settings. */
    public Map<String, String> commConfig(String interfaceName) {
        Object config = agent.call("comm", args("interface", interfaceName)).get("config");
        Map<String, String> out = new LinkedHashMap<>();
        if (config instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }

    @Override
    public void close() {
        if (cardServer != null) {
            cardServer.close();
        }
    }

    // -- helpers --------------------------------------------------------------------------------

    static String pending(String keys) {
        if (keys == null) {
            return "unknown";
        }
        return keys.isEmpty() ? "none" : PyText.repr(keys);
    }

    /** The prompt a screen shows, without what a PIN entry adds to it: the mask's {@code *}s. */
    static List<String> promptLines(List<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String text = String.join(" ", line.replace("*", " ").trim().split("\\s+")).trim();
            if (!text.isEmpty()) {
                kept.add(text);
            }
        }
        return kept;
    }

    /** {@code "(?i)" + re.escape(prompt)}: a case-insensitive literal for the agent's Python {@code re}. */
    static String pattern(String prompt) {
        return "(?i)" + PyText.escape(prompt);
    }

    /** One case-insensitive pattern matching any of {@code patterns} (each {@code (?i)...}). */
    static String anyOf(List<String> patterns) {
        StringBuilder out = new StringBuilder("(?i)");
        for (int i = 0; i < patterns.size(); i++) {
            String p = patterns.get(i);
            if (p.startsWith("(?i)")) {
                p = p.substring(4);
            }
            out.append(i == 0 ? "" : "|").append("(?:").append(p).append(')');
        }
        return out.toString();
    }

    static double seconds(Duration d) {
        return d.toNanos() / 1e9;
    }

    /** An ordered argument map from key, value pairs. */
    static Map<String, Object> args(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }
}
