package com.verifone.cat.devices.forecourt;

import com.verifone.cat.devices.DeviceUnreachable;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One of the Petro Simulator Suite's DCRs, as PATS's {@code DCRManager} drives it. Ported from the Python
 * {@code PetroDcr}.
 *
 * <p>PATS writes one {@code KeyPressedParameters} per soft key to the suite's port {@value #PETRO_DCR_PORT} and reads a
 * {@code Status} back. The suite writes a list of {@code DisplayParameters} to PATS's port {@value #PATS_DCR_PORT} for
 * every display change, and waits for a {@code Status} before it closes. It never resends the current screen, so the
 * listener is started once, before the first display is wanted.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): a DCR display received, or a DCR soft key acted on, has not run in Java.
 */
public final class PetroDcr implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PetroDcr.class);

    /** The suite's DCR key port (dcrCommClientServerSocketPort:1053). */
    public static final int PETRO_DCR_PORT = 1053;
    /** PATS's DCR display port (dcr.sereverPort=1074). */
    public static final int PATS_DCR_PORT = 1074;

    private static final String DISPLAY_PARAMETERS = ForecourtWire.DCR_ENT + "DisplayParameters";
    private static final String PRINTER_PARAMETERS = ForecourtWire.DCR_ENT + "PrinterParameters";

    private final String simulatorHost;
    private final int dcr;
    private final int listenPort;
    private final int simulatorPort;
    private final ForecourtTimeouts timeouts;

    private final Object changed = new Object();
    private String[] lines = new String[0];
    private final List<String[]> shown = new ArrayList<>();
    private String[] leftMenu = new String[0];
    private String[] rightMenu = new String[0];
    private final List<String[]> prints = new ArrayList<>();
    private PushListener listener;

    public PetroDcr(String simulatorHost, int dcr, int listenPort, int simulatorPort, ForecourtTimeouts timeouts) {
        this.simulatorHost = Objects.requireNonNull(simulatorHost, "simulatorHost");
        this.dcr = dcr;
        this.listenPort = listenPort;
        this.simulatorPort = simulatorPort;
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    }

    /** DCR 1 on the suite's standard ports, already started. */
    public static PetroDcr started(String simulatorHost, ForecourtTimeouts timeouts) {
        return started(simulatorHost, 1, PATS_DCR_PORT, PETRO_DCR_PORT, timeouts);
    }

    /** A DCR on the given ports, already started. */
    public static PetroDcr started(String simulatorHost, int dcr, int listenPort, int simulatorPort,
            ForecourtTimeouts timeouts) {
        PetroDcr unit = new PetroDcr(simulatorHost, dcr, listenPort, simulatorPort, timeouts);
        unit.start();
        return unit;
    }

    /**
     * Listens for displays, then logs in, as {@code DCRCommunicator.initialize} does. The login does not bring back the
     * current screen: the suite answers with an empty list.
     *
     * @throws DeviceUnreachable when the listen port is taken or the login does not get through
     */
    public void start() {
        try {
            listener = new PushListener(listenPort, this::onDisplays, timeouts.http(), ForecourtWire.DCR_ACK);
        } catch (IOException e) {
            throw new DeviceUnreachable("the Petro DCR listen port " + listenPort, "already held: " + e.getMessage(), e);
        }
        try {
            send(ForecourtWire.encodeLogin(false));
        } catch (DeviceUnreachable e) {
            close();
            throw e;
        }
    }

    @Override
    public void close() {
        if (listener != null) {
            listener.close();
        }
    }

    /** The current display's prompt lines (empty before the first display). */
    public List<String> promptLines() {
        synchronized (changed) {
            return List.of(lines);
        }
    }

    /**
     * {@code WAIT_DCR_PROMPT}: PATS's {@code DCRManager.waitForDcrDisplay}, not exact-match. Passes when a prompt line
     * contains {@code prompt} (trimmed, case-insensitive), now or on a display shown since the last wait passed.
     */
    public void waitForPrompt(String prompt, Duration timeout) {
        String needle = prompt.strip().toLowerCase(Locale.ROOT);
        Duration budget = timeout != null ? timeout : timeouts.promptWait();
        synchronized (changed) {
            BooleanSupplier ready = () -> shows(lines, needle) || shown.stream().anyMatch(l -> shows(l, needle));
            Waits.await(changed, ready, budget, () -> "DCR " + dcr + " prompt '" + prompt + "' (showing "
                    + java.util.Arrays.toString(lines) + ", soft keys " + softKeyLabels() + ")");
            if (!shows(lines, needle)) {
                LOG.info("DCR {}: '{}' was shown earlier and has moved on to {}", dcr, prompt,
                        java.util.Arrays.toString(lines));
            }
            shown.clear();
        }
    }

    /** {@code WAIT_DCR_PROMPT} with the default prompt wait. */
    public void waitForPrompt(String prompt) {
        waitForPrompt(prompt, null);
    }

    /** A soft key, PATS's {@code L1}..{@code R4} ({@code DCRManager.funKeyPress}). */
    public void press(String key) {
        send(ForecourtWire.encodeDcrKey(dcr, key));
    }

    /** How many receipts the DCR has printed so far: a mark for {@link #receiptSince}. */
    public int printCount() {
        synchronized (changed) {
            return prints.size();
        }
    }

    /**
     * The DCR's newest receipt printed after {@code mark}, once there is one. Each print sharing the newest print's
     * {@code TRAN#} is appended to it, as PATS's {@code DCRPrinterSim.displayReceipt} does.
     */
    public String receiptSince(int mark, Duration timeout) {
        Duration budget = timeout != null ? timeout : timeouts.promptWait();
        List<String[]> since;
        synchronized (changed) {
            Waits.await(changed, () -> prints.size() > mark, budget, () -> "DCR " + dcr + " to print a receipt");
            since = new ArrayList<>(prints.subList(mark, prints.size()));
        }
        String[] newest = since.get(since.size() - 1);
        String tran = tranNumber(newest);
        List<String> chosen = new ArrayList<>();
        for (String[] print : since) {
            if (!tran.isEmpty() && tran.equals(tranNumber(print))) {
                chosen.addAll(List.of(print));
            }
        }
        if (chosen.isEmpty()) {
            chosen.addAll(List.of(newest));
        }
        return String.join("\n", chosen);
    }

    /** {@code INSERT_OR_SLIDE_CARD}: names a card from the suite's deck. Nothing is awaited first. */
    public void insertCard(String label) {
        send(ForecourtWire.encodeDcrCard(dcr, label));
    }

    /** {@code KEY_PRESS}: the digits, all in one message. */
    public void keyPress(String chars) {
        send(ForecourtWire.encodeDcrKeyboard(dcr, chars));
    }

    /** Test seam: the soft-key menus, left and right, as the suite last sent them. */
    void setMenusForTest(String[] left, String[] right) {
        synchronized (changed) {
            leftMenu = left;
            rightMenu = right;
        }
    }

    // -- internals -----------------------------------------------------------

    private void send(byte[] payload) {
        Transport.send(simulatorHost, simulatorPort, payload, timeouts.http(), true);
    }

    private static boolean shows(String[] promptLines, String needle) {
        for (String line : promptLines) {
            if (line.strip().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The menus by PATS's key names: a menu's first line is row 1, which is L4/R4. */
    private String softKeyLabels() {
        List<String> keys = new ArrayList<>();
        addMenu(keys, "L", leftMenu);
        addMenu(keys, "R", rightMenu);
        return keys.isEmpty() ? "none" : String.join(", ", keys);
    }

    private static void addMenu(List<String> keys, String side, String[] menu) {
        for (int row = 0; row < menu.length; row++) {
            String text = menu[row].strip();
            if (!text.isEmpty()) {
                keys.add(side + (4 - row) + "='" + text + "'");
            }
        }
    }

    private void onDisplays(Object obj) {
        if (!(obj instanceof List<?> items)) {
            return;
        }
        synchronized (changed) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> entry) || !isForThisDcr(entry)) {
                    continue;
                }
                if (DISPLAY_PARAMETERS.equals(entry.get("__class__"))) {
                    lines = strings(entry.get("promptLines"));
                    shown.add(lines);
                    leftMenu = strings(entry.get("leftMenuLines"));
                    rightMenu = strings(entry.get("rightMenuLines"));
                } else if (PRINTER_PARAMETERS.equals(entry.get("__class__"))) {
                    prints.add(strings(entry.get("receiptLines")));
                }
            }
            changed.notifyAll();
        }
    }

    private boolean isForThisDcr(Map<?, ?> entry) {
        return ForecourtWire.intField(entry, "position").map(p -> p == dcr).orElse(false);
    }

    private static String[] strings(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return new String[0];
        }
        String[] out = new String[items.size()];
        for (int i = 0; i < out.length; i++) {
            Object item = items.get(i);
            out[i] = item == null ? "" : String.valueOf(item);
        }
        return out;
    }

    /** A {@code TRAN#} line's digits: the digits after the last {@code #} on the first line that has TRAN and {@code #}. */
    static String tranNumber(String[] lines) {
        for (String line : lines) {
            if (line.contains("TRAN") && line.contains("#")) {
                String after = line.substring(line.lastIndexOf('#') + 1);
                StringBuilder digits = new StringBuilder();
                for (char ch : after.toCharArray()) {
                    if (Character.isDigit(ch)) {
                        digits.append(ch);
                    }
                }
                return digits.toString();
            }
        }
        return "";
    }
}
