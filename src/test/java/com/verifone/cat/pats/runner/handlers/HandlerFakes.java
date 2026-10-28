package com.verifone.cat.pats.runner.handlers;

import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pats.runner.handlers.sale.LastTlog;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;
import com.verifone.cat.pats.runner.handlers.sale.SaleScreen;
import com.verifone.cat.pats.runner.handlers.sale.ScannerData;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.pos.pinpad.PinpadDisplay;
import com.verifone.cat.pos.pinpad.PinpadDouble;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fakes for the handler families: the POS screen, the register's RMI, the pumps, the DCR, the pinpad, the scanner
 * data and the TLog. Each records what it was asked, so a test asserts the calls and their order. None touches a
 * register, a pinpad, a pump or a device: evidence about this repository only.
 */
public final class HandlerFakes {

    private HandlerFakes() {
    }

    /** A POS screen the test sets: its overlay, its express labels in sequence, the settle outcomes, the receipt. */
    public static final class Screen implements SaleScreen {

        public final List<String> calls = new ArrayList<>();
        public Map<String, String> overlay;
        public String text = "screen";
        public String receipt = "";
        public final Deque<List<String>> express = new ArrayDeque<>();
        public final Deque<Boolean> settles = new ArrayDeque<>();
        public int clears;
        /** The clear (1-based) on which the overlay goes; 0 when it never goes. */
        public int overlayRemovedAtClear;

        @Override
        public String screenText() {
            return text;
        }

        @Override
        public boolean settled(String before, Duration cap) {
            calls.add("settled");
            return settles.isEmpty() ? true : settles.poll();
        }

        @Override
        public Map<String, String> overlay() {
            return overlay == null ? null : new HashMap<>(overlay);
        }

        @Override
        public List<String> expressLabels() {
            if (express.size() > 1) {
                return express.poll();
            }
            return express.isEmpty() ? List.of() : express.peek();
        }

        @Override
        public void clearBlockingOverlays() {
            clears++;
            if (overlayRemovedAtClear > 0 && clears == overlayRemovedAtClear) {
                overlay = null;
            }
        }

        @Override
        public String receiptText() {
            return receipt;
        }
    }

    /** The register's RMI, recording each call as {@code name:args}. */
    public static final class Rmi implements SaleRmi {

        public final List<String> calls = new ArrayList<>();
        public final List<ScanItem> scans = new ArrayList<>();
        /** When true, ADD_ITEM's register asks for the item's scanner file, as the register does for a scanned item. */
        public boolean addItemScans;

        @Override
        public void pressKeys(String keys) {
            calls.add("keys:" + keys);
        }

        @Override
        public void pressKey(String key) {
            calls.add("key:" + key);
        }

        @Override
        public void pressEnter(String data) {
            calls.add("enter:" + data);
        }

        @Override
        public void pressWithData(String key, String data) {
            calls.add("with:" + key + ":" + data);
        }

        @Override
        public void scanItem(ScanItem item) {
            scans.add(item);
            calls.add("scan:" + item.scanData());
        }

        @Override
        public void mop(String mop, String data) {
            calls.add("mop:" + mop + ":" + data);
        }

        @Override
        public void otherMop(String otherMop, String data, String position, String specialDiscount) {
            calls.add("othermop:" + otherMop + ":" + data + ":" + position + ":" + specialDiscount);
        }

        @Override
        public void total() {
            calls.add("total");
        }

        @Override
        public void addItem(AddItem item) {
            calls.add("additem:" + item.plu() + ":" + item.quantity());
            if (addItemScans) {
                item.scan().run();
            }
        }

        @Override
        public void addDeptItem(AddDeptItem item) {
            calls.add("deptitem:" + item.dept() + ":" + item.amount() + ":" + item.quantity());
        }

        @Override
        public void prepay(String pump, String grade, String fuelProduct, String amount, String mop,
                Runnable untilIdle) {
            calls.add("prepay:" + pump + ":" + grade + ":" + fuelProduct + ":" + amount + ":" + mop);
            untilIdle.run();
        }

        @Override
        public void fuel(String pump, String currentStacked, Runnable untilClaimable) {
            calls.add("fuel:" + pump + ":" + currentStacked);
            untilClaimable.run();
        }

        @Override
        public void refund() {
            calls.add("refund");
        }

        @Override
        public void voidTicket() {
            calls.add("voidTicket");
        }

        @Override
        public void voidLine(String plu, String quantity) {
            calls.add("voidLine:" + plu + ":" + quantity);
        }

        @Override
        public void errorCorrect() {
            calls.add("errorCorrect");
        }

        @Override
        public void suspend() {
            calls.add("suspend");
        }
    }

    /** The forecourt's pumps: the states the test sets, and each call recorded. */
    public static final class Pumps implements FuelPumps {

        public final List<String> calls = new ArrayList<>();
        public final Map<Integer, String> states = new HashMap<>();

        @Override
        public void fillAmount(int pump, String amount, int hose, boolean prepay, boolean preset) {
            calls.add("fill:" + pump + ":" + amount + ":" + hose + ":" + prepay + ":" + preset);
        }

        @Override
        public void handleUp(int pump, int hose, boolean prepay) {
            calls.add("up:" + pump + ":" + hose + ":" + prepay);
        }

        @Override
        public void handleDown(int pump, boolean prepay) {
            calls.add("down:" + pump + ":" + prepay);
        }

        @Override
        public Optional<String> state(int pump) {
            return Optional.ofNullable(states.get(pump));
        }

        @Override
        public void waitUntilIdle(int pump, Duration timeout) {
            calls.add("idle:" + pump);
        }
    }

    /** The DCR: each call recorded. */
    public static final class Dcr implements DcrDevice {

        public final List<String> calls = new ArrayList<>();

        @Override
        public void insertCard(String card) {
            calls.add("card:" + card);
        }

        @Override
        public void waitForPrompt(String text, Duration timeout) {
            calls.add("prompt:" + text);
        }

        @Override
        public void keyPress(String key) {
            calls.add("key:" + key);
        }

        @Override
        public void press(String functionKey) {
            calls.add("press:" + functionKey);
        }
    }

    /**
     * A pinpad whose display is the set of prompts it shows. A prompt in {@code showing} is there at once; one in
     * {@code showingAfterWait} appears only to a wait with a timeout above zero; any other prompt times out.
     */
    public static final class Pinpad implements PinpadDevice {

        public final List<String> calls = new ArrayList<>();
        public final List<Duration> promptTimeouts = new ArrayList<>();
        public final Set<String> showing = new HashSet<>();
        public final Set<String> showingAfterWait = new HashSet<>();

        @Override
        public String id() {
            return "fake-pinpad";
        }

        @Override
        public PinpadDisplay display() {
            return new PinpadDisplay("fake-pinpad", List.of(), "", null, false, null, null, 0);
        }

        @Override
        public String atPrompt(String prompt, Duration timeout) {
            promptTimeouts.add(timeout);
            calls.add("prompt:" + prompt);
            if (showing.contains(prompt)) {
                return prompt;
            }
            if (!timeout.isZero() && showingAfterWait.contains(prompt)) {
                return prompt;
            }
            throw new PinpadDouble.WaitTimeout("the prompt " + prompt + " did not show");
        }

        @Override
        public String expectPromptCleared(String prompt, Duration timeout) {
            return "";
        }

        @Override
        public String insertCard(String label, String waitForPrompt, Duration timeout) {
            calls.add("card:" + label);
            return label;
        }

        @Override
        public String swipeCard(String label, String waitForPrompt, Duration timeout) {
            calls.add("swipe:" + label);
            return label;
        }

        @Override
        public String tapCard(String label, String waitForPrompt, Duration timeout) {
            calls.add("tap:" + label);
            return label;
        }

        @Override
        public void removeCard() {
            calls.add("remove");
        }

        @Override
        public String key(String key, String waitForPrompt, Duration timeout) {
            calls.add("key:" + key);
            return key;
        }

        @Override
        public String decimal(BigDecimal value, String waitForPrompt, Duration timeout) {
            calls.add("decimal:" + value.toPlainString());
            return value.toPlainString();
        }
    }

    /** Scanner data by file name, with each read recorded; an unknown file is a test failure. */
    public static final class Scanner implements ScannerData {

        public final Map<String, String> files = new HashMap<>();
        public final List<String> reads = new ArrayList<>();

        @Override
        public String xml(String fileName) {
            reads.add(fileName);
            String xml = files.get(fileName);
            if (xml == null) {
                throw new IllegalStateException("no scanner data " + fileName + " in the fake");
            }
            return xml;
        }
    }

    /** A TLog STAN, set by the test. */
    public static final class Tlog implements LastTlog {

        public Optional<String> stan = Optional.empty();

        @Override
        public Optional<String> stan(com.verifone.cat.pats.runner.CaseRun run) {
            return stan;
        }
    }

    /** A scanner-data file with one {@code <Data>}: the token, its type, and a status. */
    public static String scanData(String token, int type) {
        return "<ScannerData><Data><scanData>" + token + "</scanData><scanDataType>" + type
                + "</scanDataType></Data></ScannerData>";
    }
}
