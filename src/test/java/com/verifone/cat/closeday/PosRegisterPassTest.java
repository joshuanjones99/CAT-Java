package com.verifone.cat.closeday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.closeday.CloseDayFakes.Access;
import com.verifone.cat.closeday.CloseDayFakes.Box;
import com.verifone.cat.closeday.CloseDayFakes.Capture;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.DriverError;
import com.verifone.cat.pos.driver.PosDriver.ElementState;
import com.verifone.cat.pos.pages.ItemPanel;

/**
 * The day close's register pass against a fake register: the nag and message paths, the open-sale void, the
 * suspended recall-and-void loop, the forecourt settle and the prepay refund, and that a failed pass is reported
 * while the close still goes on. Ports of the register cases of the Python's {@code tests/unit/test_close_day.py}
 * ({@code TestTheNagIsClearedFirst}, {@code TestMessagesAreDismissed}, {@code TestPumpFillsArePaidOff},
 * {@code TestPrepaysNobodyFuelledAreRefunded}, {@code TestSignOnNagIsAnswered},
 * {@code TestSuspendedTransactionsAreVoided}).
 *
 * <p>Evidence about this code only, never about a register.
 */
class PosRegisterPassTest {

    private static final Duration SHORT = Duration.ofMillis(150);
    private static final PosRegisterPass.Timing FAST =
            new PosRegisterPass.Timing(SHORT, SHORT, SHORT, SHORT, SHORT, SHORT, SHORT, Duration.ofMillis(5));

    private static final Locator TITLE = Locators.OVERLAY_MSG_VIEW_TITLE;
    private static final Locator OK_BTN = Locators.OVERLAY_MSG_VIEW_OK_BTN;
    private static final Locator CANCEL_BTN = Locators.OVERLAY_MSG_VIEW_CANCEL_BTN;
    private static final Locator ROWS =
            Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_TRANSACTION_PANEL_TRANS_STATUS_PANEL_TRANS_LBL;
    private static final Locator DETAIL_CLOSE = Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON;

    @TempDir
    Path dir;

    /** A register whose every read is empty and every press recorded, unless a test overrides it. */
    static class FakeScreen implements RegisterScreen {
        final List<String> calls = new ArrayList<>();
        final Map<Locator, List<ElementState>> shown = new HashMap<>();
        boolean atLogin;
        boolean idle = true;
        boolean holdsFuel;
        boolean reviewShown;
        boolean closed;

        @Override
        public List<ElementState> elementStates(Locator locator) {
            return shown.getOrDefault(locator, List.of());
        }

        @Override
        public List<String> visibleTexts(Locator locator) {
            return List.of();
        }

        @Override
        public void press(Locator locator, Duration timeout) {
            calls.add("press " + locator.value());
        }

        @Override
        public boolean dispatchPress(Locator locator, int index) {
            calls.add("dispatch " + index);
            return false;
        }

        @Override
        public void clearBlockingOverlays() {
            calls.add("clear");
        }

        @Override
        public boolean atLogin() {
            return atLogin;
        }

        @Override
        public boolean isIdle() {
            return idle;
        }

        @Override
        public boolean reviewOverlayShown() {
            return reviewShown;
        }

        @Override
        public void closeReviewOverlay() {
            calls.add("close review");
            reviewShown = false;
        }

        @Override
        public String clearOpenSale() {
            calls.add("clearOpenSale");
            idle = true;
            return "voided";
        }

        @Override
        public boolean holdsDispensedFuel() {
            return holdsFuel;
        }

        @Override
        public void signOnFromLogin(String cashierId, String password) {
            calls.add("sign on " + cashierId);
            atLogin = false;
        }

        @Override
        public void selectMop(ItemPanel.MOP mop, Duration timeout) {
            calls.add("mop " + mop);
        }

        @Override
        public void keypadOk(Duration timeout) {
            calls.add("keypad ok");
        }

        @Override
        public void expectIdle(Duration timeout) {
        }

        @Override
        public Optional<String> reserved(int pump) {
            return Optional.empty();
        }

        @Override
        public void close() {
            calls.add("close");
            closed = true;
        }
    }

    private static PosRegisterPass pass(RegisterScreen screen, PrepayPumps.Opener petro, Capture out) {
        return new PosRegisterPass(() -> screen, petro, "91", "CASHIER_PW", () -> "secret", FAST, out.stream);
    }

    private static PosRegisterPass pass(RegisterScreen screen, Capture out) {
        return pass(screen, null, out);
    }

    // ---- messages (TestMessagesAreDismissed) ----------------------------------------------------

    /** Messages shown one after another; each press of the button reading "OK" (or "Yes") removes the top one. */
    static class Messages extends FakeScreen {
        final List<String[]> messages = new ArrayList<>(); // {title, button name}
        final List<String> pressed = new ArrayList<>();
        int guarded;
        boolean guardRemovesNag = true;

        Messages(String... titleButtonPairs) {
            for (int i = 0; i < titleButtonPairs.length; i += 2) {
                messages.add(new String[] {titleButtonPairs[i], titleButtonPairs[i + 1]});
            }
        }

        @Override
        public List<ElementState> elementStates(Locator locator) {
            if (messages.isEmpty()) {
                return List.of();
            }
            String[] top = messages.get(0);
            if (locator.equals(TITLE)) {
                return List.of(new ElementState(true, top[0]));
            }
            if (locator.name().equals(top[1])) {
                return List.of(new ElementState(true, top[0].equals("Delete?") ? "Yes" : "OK"));
            }
            return List.of();
        }

        @Override
        public void press(Locator locator, Duration timeout) {
            pressed.add(locator.name());
            messages.remove(0);
        }

        @Override
        public void clearBlockingOverlays() {
            guarded++;
            if (guardRemovesNag && !messages.isEmpty()) {
                messages.remove(0);
            }
        }
    }

    @Test
    void eachOkOnlyMessageIsPressedAway() {
        Messages register = new Messages("Fueling Position OFFLINE", CANCEL_BTN.name(), "Info", OK_BTN.name());
        Capture out = new Capture();
        pass(register, out).dismissMessages(register);
        assertEquals(List.of(CANCEL_BTN.name(), OK_BTN.name()), register.pressed);
        assertTrue(register.messages.isEmpty());
        assertTrue(out.text().contains("dismissing 'Fueling Position OFFLINE' on the register"), out.text());
    }

    @Test
    void aQuestionIsNeverAnswered() {
        Messages register = new Messages("Delete?", OK_BTN.name());
        pass(register, new Capture()).dismissMessages(register);
        assertEquals(List.of(), register.pressed);
    }

    @Test
    void aNagGoesToTheDriversGuardAndIsNeverPressedHere() {
        Messages register = new Messages("NOT ONBOARDED", CANCEL_BTN.name(), "Info", OK_BTN.name());
        pass(register, new Capture()).dismissMessages(register);
        assertEquals(1, register.guarded);
        assertEquals(List.of(OK_BTN.name()), register.pressed);
    }

    @Test
    void aNagTheGuardLeavesUpEndsTheDismissals() {
        Messages register = new Messages("NOT ONBOARDED", CANCEL_BTN.name());
        register.guardRemovesNag = false;
        pass(register, new Capture()).dismissMessages(register);
        assertEquals(1, register.guarded);
        assertEquals(List.of(), register.pressed);
    }

    // ---- after a sign-on (TestSignOnNagIsAnswered) ----------------------------------------------

    @Test
    void aNagThatShowsAfterTheSignOnIsCleared() {
        int[] looks = {0};
        Messages register = new Messages() {
            @Override
            public List<ElementState> elementStates(Locator locator) {
                looks[0]++;
                return looks[0] > 2 ? List.of(new ElementState(true, "NOT ONBOARDED")) : List.of();
            }
        };
        register.guardRemovesNag = false;
        pass(register, new Capture()).answerSignOnMessages(register);
        assertEquals(1, register.guarded);
    }

    @Test
    void noMessageAfterTheSignOnMeansNothingIsPressed() {
        Messages register = new Messages();
        pass(register, new Capture()).answerSignOnMessages(register);
        assertEquals(0, register.guarded);
        assertEquals(List.of(), register.pressed);
    }

    @Test
    void aFailedAutoEndOfDayAfterTheSignOnIsPressedAway() {
        // Run 4a04258badad: its OK is OVERLAY_MSG_VIEW_CANCEL_BTN.
        Messages register = new Messages("Auto End of Day Failed", CANCEL_BTN.name());
        pass(register, new Capture()).answerSignOnMessages(register);
        assertEquals(List.of(CANCEL_BTN.name()), register.pressed);
        assertTrue(register.messages.isEmpty());
    }

    // ---- the open sale ------------------------------------------------------------------------

    @Test
    void anIdleRegisterOrTheLoginScreenIsLeftAlone() {
        FakeScreen idle = new FakeScreen();
        pass(idle, new Capture()).voidOpenSale(idle);
        FakeScreen login = new FakeScreen();
        login.atLogin = true;
        login.idle = false;
        pass(login, new Capture()).voidOpenSale(login);
        assertEquals(List.of(), idle.calls);
        assertEquals(List.of(), login.calls);
    }

    @Test
    void anOpenSaleIsClearedAfterTheReviewOverlayIsClosed() {
        FakeScreen register = new FakeScreen();
        register.idle = false;
        register.reviewShown = true;
        Capture out = new Capture();
        pass(register, out).voidOpenSale(register);
        assertEquals(List.of("close review", "clearOpenSale"), register.calls);
        assertTrue(out.text().contains("sale open on the register before closing the day: voided"), out.text());
    }

    // ---- suspended transactions (TestSuspendedTransactionsAreVoided) --------------------------

    /** Recall lists the suspended ones; Recall again brings the first onto the ticket; the void clears it. */
    static final class Suspended extends FakeScreen {
        static final Locator RECALL = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(2);
        static final Locator BACK = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7);
        int suspended;
        boolean listed;
        boolean csrPage;
        final List<String> pressed = new ArrayList<>();

        Suspended(int suspended, boolean csrPage) {
            this.suspended = suspended;
            this.csrPage = csrPage;
        }

        @Override
        public List<ElementState> elementStates(Locator locator) {
            if (csrPage) {
                return locator.equals(BACK) ? List.of(new ElementState(true, "Return To Sales")) : List.of();
            }
            return locator.equals(RECALL) ? List.of(new ElementState(true, "Recall")) : List.of();
        }

        @Override
        public void press(Locator locator, Duration timeout) {
            if (locator.equals(BACK)) {
                pressed.add("return to sales");
                csrPage = false;
                return;
            }
            pressed.add("recall");
            if (listed && suspended > 0) {
                listed = false;
                idle = false;
                suspended--;
            } else {
                listed = suspended > 0;
            }
        }

        @Override
        public List<String> visibleTexts(Locator locator) {
            return listed && locator.equals(Locators.REVIEW_OVERLAY_VIEW_ITEM_N)
                    ? List.of("1. 10/21 20:56 3010023 SUSPEND 34.00") : List.of();
        }

        @Override
        public String clearOpenSale() {
            pressed.add("void");
            idle = true;
            return "voided";
        }
    }

    @Test
    void eachSuspendedIsRecalledAndVoidedUntilTheListIsEmpty() {
        Suspended register = new Suspended(2, false);
        Capture out = new Capture();
        assertEquals(2, pass(register, out).voidSuspended(register));
        assertEquals(List.of("recall", "recall", "void", "recall", "recall", "void", "recall"), register.pressed);
        assertTrue(out.text().contains("suspended transactions recalled and voided before closing the day: 2"));
    }

    @Test
    void withNoneSuspendedOneRecallFindsNothing() {
        Suspended register = new Suspended(0, false);
        assertEquals(0, pass(register, new Capture()).voidSuspended(register));
        assertEquals(List.of("recall"), register.pressed);
    }

    @Test
    void fromTheCsrPageItReturnsToSalesFirst() {
        // Run 726c85ae343d (2026-10-23): left on the CSR page, no Recall key, nothing voided.
        Suspended register = new Suspended(1, true);
        PosRegisterPass pass = pass(register, new Capture());
        pass.returnToSales(register);
        assertEquals(1, pass.voidSuspended(register));
        assertEquals(List.of("return to sales", "recall", "recall", "void", "recall"), register.pressed);
    }

    // ---- the forecourt (TestPumpFillsArePaidOff) ----------------------------------------------

    /** Pumps holding sales, "due" or "sold", current first; a tile's tap claims a lone one, else opens the view. */
    static class Forecourt extends FakeScreen {
        final Map<Integer, List<String>> tiles = new LinkedHashMap<>();
        Integer detail;
        String ticket = "";
        final List<String> log = new ArrayList<>();

        private String tile(int pump) {
            StringBuilder text = new StringBuilder(String.valueOf(pump));
            for (String sale : tiles.get(pump)) {
                if (sale.equals("due")) {
                    text.append("+1.00");
                }
            }
            return text.toString();
        }

        private Integer pumpOf(Locator locator) {
            for (int pump : tiles.keySet()) {
                if (locator.equals(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA.at(pump))) {
                    return pump;
                }
            }
            return null;
        }

        @Override
        public List<ElementState> elementStates(Locator locator) {
            Integer pump = pumpOf(locator);
            if (pump != null) {
                return List.of(new ElementState(true, tile(pump)));
            }
            if (detail != null && locator.equals(ROWS)) {
                List<ElementState> rows = new ArrayList<>();
                for (int i = 0; i < tiles.get(detail).size(); i++) {
                    rows.add(new ElementState(true, "Current"));
                }
                return rows;
            }
            if (detail != null && locator.equals(DETAIL_CLOSE)) {
                return List.of(new ElementState(true, ""));
            }
            return List.of();
        }

        @Override
        public void press(Locator locator, Duration timeout) {
            if (locator.equals(DETAIL_CLOSE)) {
                detail = null;
                return;
            }
            int pump = pumpOf(locator);
            if (tiles.get(pump).size() == 1) {
                claim(pump, 0);
            } else {
                detail = pump;
            }
        }

        @Override
        public boolean dispatchPress(Locator locator, int index) {
            claim(detail, index);
            detail = null;
            return true;
        }

        private void claim(int pump, int index) {
            if (tiles.get(pump).get(index).equals("due")) {
                ticket = String.valueOf(pump);
                tiles.get(pump).remove(index);
            } else {
                log.add("error on pump " + pump);
            }
        }

        @Override
        public boolean holdsDispensedFuel() {
            return !ticket.isEmpty();
        }

        @Override
        public String clearOpenSale() {
            log.add("paid pump " + ticket);
            ticket = "";
            return "settled";
        }
    }

    @Test
    void eachDueFillIsPaidAndASoldOneIsLeft() {
        Forecourt register = new Forecourt();
        register.tiles.put(1, new ArrayList<>(List.of("due")));
        register.tiles.put(2, new ArrayList<>(List.of("sold", "due")));
        register.tiles.put(3, new ArrayList<>());
        Capture out = new Capture();
        pass(register, out).settleForecourt(register);
        assertEquals(List.of("paid pump 1", "error on pump 2", "paid pump 2"), register.log);
        assertEquals(List.of("sold"), register.tiles.get(2));
        assertTrue(register.tiles.get(1).isEmpty());
        assertTrue(out.text().contains("pump 2: sale 1 not claimed (paid, not finalised?)"), out.text());
    }

    @Test
    void atTheLoginScreenACashierIsSignedOnFirst() {
        Forecourt register = new Forecourt();
        register.atLogin = true;
        register.tiles.put(1, new ArrayList<>(List.of("due")));
        pass(register, new Capture()).settleForecourt(register);
        assertEquals("sign on 91", register.calls.get(0));
        assertEquals(List.of("paid pump 1"), register.log);
    }

    @Test
    void withNoPasswordTheForecourtIsNotSettled() {
        Forecourt register = new Forecourt();
        register.atLogin = true;
        register.tiles.put(1, new ArrayList<>(List.of("due")));
        Capture out = new Capture();
        new PosRegisterPass(() -> register, null, "91", "CASHIER_PW", () -> "", FAST, out.stream)
                .settleForecourt(register);
        assertEquals(List.of(), register.calls);
        assertEquals(List.of(), register.log);
        assertTrue(out.text().contains("not settling pump sales: $CASHIER_PW is not set"), out.text());
    }

    @Test
    void aReserveIsReportedNotCleared() {
        Forecourt register = new Forecourt() {
            @Override
            public Optional<String> reserved(int pump) {
                return pump == 1 ? Optional.of("$10.00") : Optional.empty();
            }
        };
        register.tiles.put(1, new ArrayList<>());
        Capture out = new Capture();
        pass(register, out).settleForecourt(register);
        assertTrue(out.text().contains("pump 1: $10.00 reserved; left for a person (not cleared by CAT)"), out.text());
        assertEquals(List.of(), register.calls);
    }

    // ---- prepays nobody fuelled (TestPrepaysNobodyFuelledAreRefunded) -------------------------

    /** Tiles as Reg101 drew them on 2026-10-14: "10.00" a prepay, "-10.00" a refund; a tile's tap claims a refund. */
    static class Prepays extends FakeScreen {
        final Map<Integer, String> current = new LinkedHashMap<>();
        int ticket;
        final List<String> log = new ArrayList<>();
        Petro petro;
        boolean rebuys = true;

        Prepays(Map<Integer, String> amounts) {
            int highest = amounts.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            for (int pump = 1; pump <= highest; pump++) {
                current.put(pump, amounts.getOrDefault(pump, ""));
            }
        }

        @Override
        public List<ElementState> elementStates(Locator locator) {
            for (Map.Entry<Integer, String> tile : current.entrySet()) {
                int pump = tile.getKey();
                if (locator.equals(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA.at(pump))) {
                    return List.of(new ElementState(true, pump + tile.getValue()));
                }
                if (locator.equals(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT.at(pump))) {
                    return List.of(new ElementState(true, tile.getValue()));
                }
            }
            return List.of();
        }

        @Override
        public void press(Locator locator, Duration timeout) {
            calls.add("press " + locator.value());
            for (int pump : current.keySet()) {
                if (locator.equals(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA.at(pump))
                        && current.get(pump).startsWith("-")) {
                    ticket = pump;
                    current.put(pump, "");
                }
            }
        }

        @Override
        public boolean holdsDispensedFuel() {
            return ticket != 0;
        }

        @Override
        public String clearOpenSale() {
            if (ticket == 0) {
                return "idle";
            }
            log.add("paid pump " + ticket);
            ticket = 0;
            return "settled";
        }

        /** The re-bought prepay's CASH payment: the Commander authorises the pump. */
        @Override
        public void expectIdle(Duration timeout) {
            if (!rebuys) {
                throw new AssertionError("the register was not idle after the prepay's CASH");
            }
            int pump = rebuying();
            log.add("re-bought " + pump);
            petro.states.put(pump, "BUSYOFF");
        }

        private int rebuying() {
            for (int i = calls.size() - 1; i >= 0; i--) {
                for (int pump : current.keySet()) {
                    if (calls.get(i).equals("press " + Locators.FUEL_PANEL_0_FUEL_BUTTON_N.at(pump).value())) {
                        return pump;
                    }
                }
            }
            throw new IllegalStateException("no pump was pressed for the prepay");
        }
    }

    static final class Petro implements PrepayPumps {
        final Prepays register;
        final Map<Integer, String> states;
        boolean closed;

        Petro(Prepays register, Map<Integer, String> states) {
            this.register = register;
            this.states = new HashMap<>(states);
            register.petro = this;
        }

        @Override
        public String reported(int pump) {
            return states.get(pump);
        }

        @Override
        public void liftAtZero(int pump) {
            if (!"BUSYOFF".equals(states.get(pump))) {
                throw new IllegalStateException("pump " + pump + " never reached [BUSYOFF]");
            }
            register.log.add("lifted " + pump + " at $0.00");
        }

        @Override
        public void hangUp(int pump) {
            states.put(pump, "IDLEOFF");
            register.current.put(pump, "-" + register.current.get(pump));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void aLivePrepayIsLiftedAndAStrandedOneReboughtThenBothRefunded() {
        Prepays register = new Prepays(Map.of(1, "", 15, "10.00", 16, "10.00"));
        Petro petro = new Petro(register, Map.of(15, "BUSYOFF", 16, "IDLEOFF"));
        Capture out = new Capture();
        pass(register, () -> petro, out).settleForecourt(register);
        assertEquals(List.of("lifted 15 at $0.00", "re-bought 16", "lifted 16 at $0.00", "paid pump 15",
                "paid pump 16"), register.log);
        assertTrue(register.current.values().stream().allMatch(String::isEmpty), register.current.toString());
        assertTrue(petro.closed);
        assertTrue(out.text().contains("pump 16: prepay 10.00 nobody fuelled re-bought, then lifted"), out.text());
        // The rebuy, as Reg101's pump 16 was re-bought by hand: tile, $10 prepay, Total, CASH, OK.
        List<String> rebuy = register.calls.subList(0, 6);
        assertEquals(List.of("press " + Locators.FUEL_PANEL_0_FUEL_BUTTON_N.at(16).value(),
                "press " + Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_BUTTONS_PANEL_PREPAY_BUTTONS_N.at(2).value(),
                "mop PAYMENT_GATEWAY", "mop CASH", "keypad ok", "press "
                        + Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA.at(15).value()), rebuy);
    }

    @Test
    void aRefundAlreadyDueIsClaimedWithoutPetro() {
        Prepays register = new Prepays(Map.of(16, "-10.00"));
        pass(register, () -> {
            throw new AssertionError("Petro is not needed for a refund already due");
        }, new Capture()).settleForecourt(register);
        assertEquals(List.of("paid pump 16"), register.log);
        assertEquals("", register.current.get(16));
    }

    @Test
    void withoutPetroAPrepayIsLeftAndSaysWhy() {
        Prepays register = new Prepays(Map.of(5, "50.00"));
        Capture out = new Capture();
        pass(register, () -> {
            throw new com.verifone.cat.devices.DeviceUnreachable("petro", "port 1072 is held");
        }, out).settleForecourt(register);
        assertEquals(List.of(), register.log);
        assertEquals("50.00", register.current.get(5));
        assertTrue(out.text().contains("prepays nobody fuelled are left for a person: Petro cannot be driven"),
                out.text());
    }

    @Test
    void aRebuyThatFailsIsReportedAndTheRestGoOn() {
        Prepays register = new Prepays(Map.of(5, "50.00", 15, "10.00"));
        register.rebuys = false;
        Petro petro = new Petro(register, Map.of(5, "IDLEOFF", 15, "BUSYOFF"));
        Capture out = new Capture();
        pass(register, () -> petro, out).settleForecourt(register);
        assertTrue(out.text().contains("pump 5: prepay 50.00 not cleared"), out.text());
        assertEquals(List.of("lifted 15 at $0.00", "paid pump 15"), register.log);
        assertEquals("50.00", register.current.get(5));
        assertEquals("", register.current.get(15));
        assertTrue(petro.closed);
    }

    // ---- the whole pass, and the close around it (TestTheNagIsClearedFirst) --------------------

    @Test
    void thePassRunsInThePythonsOrderAndReleasesTheRegister() {
        FakeScreen register = new FakeScreen();
        register.idle = false;
        register.shown.put(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(2), List.of(new ElementState(true, "Recall")));
        Capture err = new Capture();
        pass(register, new Capture()).clearBlockingNag(err.stream);
        // guard, the open sale voided, one Recall that lists nothing, then the guard again (the settle's sales).
        assertEquals(List.of("clear", "clearOpenSale",
                "press " + Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(2).value(), "clear", "close"), register.calls);
        assertEquals("", err.text());
    }

    @Test
    void aFailingStepIsReportedNeverThrownAndTheRegisterIsReleased() {
        FakeScreen register = new FakeScreen() {
            @Override
            public void clearBlockingOverlays() {
                throw new DriverError("a nag is showing, but no visible OK button sits beside it");
            }
        };
        Capture err = new Capture();
        pass(register, new Capture()).clearBlockingNag(err.stream);
        assertTrue(register.closed);
        assertTrue(err.text().contains("could not check the register for a blocking nag before closing the day "
                + "(DriverError: a nag is showing"), err.text());
        assertTrue(err.text().strip().endsWith("closing anyway"), err.text());
    }

    @Test
    void anUnreachableRegisterStillClosesTheDay() throws Exception {
        Box box = new Box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));
        Capture out = new Capture();
        Capture err = new Capture();
        PosRegisterPass pass = new PosRegisterPass(() -> {
            box.calls.add("connect");
            throw new DriverError("CDP relay down");
        }, null, "91", "CASHIER_PW", () -> "secret", FAST, out.stream);

        CloseDay close = new CloseDay(new Access(box), pass, dir.resolve("base"), dir.resolve("results"),
                CloseDayFakes.fast(), out.stream, err.stream);

        assertEquals(CloseDay.Outcome.CLOSED, close.close());
        assertTrue(box.calls.indexOf("connect") < box.calls.indexOf("cclosepdnow"), box.calls.toString());
        assertTrue(err.text().contains("DriverError: CDP relay down"), err.text());
        assertTrue(err.text().contains("closing anyway"), err.text());
    }

    @Test
    void aPassThatClearedNothingDoesNotMakeACancelledCloseASuccess() throws Exception {
        Box box = new Box(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.CANCELED));
        Capture out = new Capture();
        Capture err = new Capture();
        FakeScreen register = new FakeScreen() {
            @Override
            public void clearBlockingOverlays() {
                throw new DriverError("nag still up");
            }
        };
        CloseDay close = new CloseDay(new Access(box), pass(register, out), dir.resolve("base"),
                dir.resolve("results"), CloseDayFakes.fast(), out.stream, err.stream);

        assertEquals(CloseDay.Outcome.FAILED, close.close());
        assertFalse(out.text().contains("closed the period"), out.text());
        assertTrue(err.text().contains("was CANCELLED by the Commander"), err.text());
    }
}
