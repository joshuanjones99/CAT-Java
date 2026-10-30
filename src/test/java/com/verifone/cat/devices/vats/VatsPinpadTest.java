package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.rig.Rig;

/**
 * {@link VatsPinpad} against an in-memory agent ({@link FakeTerminal}, the Python's stand-in SDK modelled op for op)
 * and a real loopback card server: the Python's {@code test_vats_agent.py} pinpad tests. Evidence about this
 * repository only.
 */
class VatsPinpadTest {

    @TempDir
    Path dir;

    private FakeTerminal fake;
    private CardServerStub cardServer;
    private VatsPinpad pad;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTerminal();
        cardServer = CardServerStub.answering();
        pad = pinpad(Map.of());
    }

    @AfterEach
    void tearDown() throws IOException {
        pad.close();
        cardServer.close();
    }

    private VatsPinpad pinpad(Map<String, String> cardImages) throws IOException {
        Path images = dir.resolve("images");
        Files.createDirectories(images);
        Files.writeString(images.resolve("Visa IINE.xml"), "<card/>");
        Path msr = dir.resolve("MSRCardData.xml");
        Files.writeString(msr, "<Cards><Card><label>Fleet1</label><track1/><track2>7078=2503</track2><track3/></Card>"
                + "</Cards>");
        Rig.Vats config = new Rig.Vats("pinpad.testrig.invalid", null, null, cardServer.port(), null, null, null,
                cardImages);
        VatsPinpad made = new VatsPinpad(fake, config, new CardImages(List.of(images)), msr, Duration.ofSeconds(2),
                null);
        made.confirmTakenWait(Duration.ofMillis(300));
        return made;
    }

    private List<Object> confirms() {
        return fake.argsOf("enter_keys");
    }

    private long count(String name) {
        return fake.names().stream().filter(name::equals).count();
    }

    @Test
    void atPromptMatchesCaseInsensitivelyAndNamesTheScreenOnTimeout() {
        fake.show(List.of("INSERT, TAP OR", "SWIPE CARD"));
        assertTrue(pad.atPrompt("swipe card", null).contains("SWIPE CARD"));
        VatsTimeout e = assertThrows(VatsTimeout.class, () -> pad.atPrompt("Enter PIN", Duration.ofMillis(100)));
        assertTrue(e.getMessage().contains("showing: INSERT, TAP OR"), e.getMessage());
        assertTrue(e.getMessage().startsWith("pinpad prompt 'Enter PIN'"), e.getMessage());
    }

    @Test
    void aPromptWithRegexCharactersIsMatchedLiterally() {
        fake.show(List.of("TOTAL $7.75"));
        pad.atPrompt("$7.75", null);
        assertThrows(VatsTimeout.class, () -> pad.atPrompt("$7X75", Duration.ofMillis(100)));
    }

    @Test
    void keyPressesTheSoftKeyAndFailsWhenItNeverShows() {
        fake.show(List.of("DO YOU WANT CASHBACK"), "YES", "NO");
        pad.key("YES");
        assertEquals(List.of("YES"), fake.argsOf("gui_press_button"));
        VatsTimeout e = assertThrows(VatsTimeout.class, () -> pad.key("CREDIT", null, Duration.ofMillis(100)));
        assertTrue(e.getMessage().contains("soft key 'CREDIT'"), e.getMessage());
    }

    @Test
    void digitsGoAsOneSequenceWithLeadingZerosKept() {
        pad.enterDigits("0042", null, null);
        pad.decimal(new BigDecimal("5.00"), null, null);
        assertEquals(List.of("0042<CONFIRM>", "500<CONFIRM>"), confirms());
    }

    @Test
    void decimalTextKeysTheTextAsWrittenAndRefusesWhatIsNotAnEntry() {
        pad.decimalText("0001234", null, null);
        assertEquals(List.of("0001234<CONFIRM>"), confirms());
        assertThrows(IllegalArgumentException.class, () -> pad.decimalText("12a", null, null));
    }

    @Test
    void aContactCardIsLoadedOnBttThenInserted() {
        pad.insertCard("EMV_CT_VISA", "Visa IINE.xml", null, null);
        byte[] select = cardServer.requests.get(0);
        byte[] start = cardServer.requests.get(1);
        byte[] path = dir.resolve("images").resolve("Visa IINE.xml").toString().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(path, Arrays.copyOfRange(select, select.length - path.length, select.length));
        assertEquals("0007ea05df0102b100", HexFormat.of().formatHex(start));
        assertTrue(fake.names().contains("insert_smartcard"));
    }

    @Test
    void aBlankImageUsesTheRigsCardImagesAndOtherwiseFailsUpFront() throws IOException {
        VatsPinpad mapped = pinpad(Map.of("EMV CREDIT", "Visa IINE.xml"));
        mapped.insertCard("EMV CREDIT", "", null, null);
        assertFalse(cardServer.requests.isEmpty());
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class,
                () -> mapped.insertCard("EMV DEBIT", "", null, null));
        assertTrue(e.getMessage().contains("names no CARD_SIMULATION_IMAGE_FILE"), e.getMessage());
        mapped.close();
    }

    @Test
    void anImageThatIsNowhereIsNamedWithTheFoldersSearched() {
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class,
                () -> pad.insertCard("EMV_CT_VISA", "Nope.xml", null, null));
        assertTrue(e.getMessage().contains("card image 'Nope.xml'"), e.getMessage());
        assertTrue(e.getMessage().contains(dir.resolve("images").toString()), e.getMessage());
    }

    @Test
    void aContactlessCardOnAContactRigIsRefusedNotInserted() {
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class,
                () -> pad.insertCard("EMV_CTLS_UNIONPAY", "Visa IINE.xml", null, null));
        assertTrue(e.getMessage().contains("one interface at a time"), e.getMessage());
        assertTrue(cardServer.requests.isEmpty());
    }

    @Test
    void aMagstripeLabelIsSwipedWithItsMsrTracks() {
        pad.insertCard("Fleet1");
        // Three strings, never null: the real SDK takes len() of each.
        FakeTerminal.Call swipe = fake.calls.stream().filter(c -> c.name().equals("swipe_magcard")).findFirst()
                .orElseThrow();
        assertEquals(List.of("", "7078=2503", ""), swipe.args());
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class, () -> pad.insertCard("Unknown Fleet"));
        assertTrue(e.getMessage().contains("no <Card> labelled so"), e.getMessage());
    }

    @Test
    void removeCardTakesItOffTheProbe() {
        fake.show(List.of("Remove Card"));
        pad.insertCard("EMV_CT_VISA", "Visa IINE.xml", null, null);
        pad.removeCard();
        assertTrue(fake.names().contains("remove_smartcard"));
        byte[] last = cardServer.requests.get(cardServer.requests.size() - 1);
        assertEquals("0007ea05df0102c100", HexFormat.of().formatHex(last));
    }

    @Test
    void abortPaymentCancelsOnTheTerminalAndTakesTheCardOut() {
        // Recovery's VOID rung on a vats rig: run 79d2c092eb4d drained with an Amex still in.
        pad.insertCard("EMV_CT_VISA", "Visa IINE.xml", null, null);
        assertEquals(List.of("CANCEL", "card removed", "simulated card stopped"), pad.abortPayment());
        List<String> tail = fake.calls.stream().filter(c -> c.name().equals("enter_keys")
                || c.name().equals("remove_smartcard")).map(c -> c.name() + c.args()).toList();
        assertEquals(List.of("enter_keys[<CANCEL>]", "remove_smartcard[]"), tail.subList(tail.size() - 2, tail.size()));
    }

    @Test
    void abortPaymentOnAnIdleTerminalOnlyPressesCancel() {
        assertEquals("CANCEL", pad.abortPayment().get(0));
        assertFalse(fake.names().contains("remove_smartcard"));
    }

    @Test
    void aPinTheTerminalTakesIsConfirmedOnce() {
        fake.show(List.of("Enter PIN"));
        fake.afterConfirm.add(List.of("PROCESSING", "PLEASE WAIT"));
        pad.enterDigits("4315", null, null);
        assertEquals(List.of("4315<CONFIRM>"), confirms());
    }

    @Test
    void aConfirmTheTerminalDroppedIsSentAgain() {
        fake.show(List.of("Enter PIN", "****"));
        fake.afterConfirm.add(List.of("Enter PIN", "****"));
        fake.afterConfirm.add(List.of("PROCESSING"));
        try (StderrCapture err = new StderrCapture()) {
            pad.enterDigits("4315", null, null);
            assertTrue(err.text().contains("CONFIRM again"), err.text());
        }
        assertEquals(List.of("4315<CONFIRM>", "<CONFIRM>"), confirms());
    }

    @Test
    void aPromptThatNeverClearsGetsOneMoreConfirmAndNoMore() {
        fake.show(List.of("Or Press", "ENTER", "To Continue"));
        try (StderrCapture err = new StderrCapture()) {
            pad.enterDigits("", null, null);
            assertTrue(err.text().contains("after a second CONFIRM (keys not yet taken: none); left as it is"),
                    err.text());
        }
        assertEquals(List.of("<CONFIRM>", "<CONFIRM>"), confirms());
    }

    @Test
    void aDroppedConfirmIsLoggedWithTheKeysTheTerminalHasNotTaken() {
        fake.show(List.of("Enter PIN", "****"));
        fake.afterConfirm.add(List.of("Enter PIN", "****"));
        fake.afterConfirm.add(List.of("PROCESSING"));
        fake.pendingKeys = "<CONFIRM>";
        try (StderrCapture err = new StderrCapture()) {
            pad.enterDigits("4315", null, null);
            assertTrue(err.text().contains("keys not yet taken by the terminal: '<CONFIRM>'"), err.text());
        }
    }

    @Test
    void cardPresentIsTheTerminalsReaderAndUnknownWhenItDoesNotSay() {
        fake.show(List.of("WELCOME"));
        fake.cardin = 1;
        assertEquals(Boolean.TRUE, pad.cardPresent());
        fake.cardin = 0;
        assertEquals(Boolean.FALSE, pad.cardPresent());
        fake.cardin = -1;
        assertNull(pad.cardPresent());
    }

    @Test
    void aCardLeftInByAnEarlierSessionNeedsAnInsertBeforeItsRemoval() {
        fake.show(List.of("Welcome", "Click me"));
        fake.cardin = 1;
        fake.call("remove");
        assertEquals(Boolean.TRUE, pad.cardPresent(), "dropped: this session inserted nothing");
        assertTrue(pad.clearStuckCard().contains("left by an earlier session; removed"));
        assertEquals(Boolean.FALSE, pad.cardPresent());
        assertEquals("", pad.clearStuckCard());
    }

    @Test
    void removeCardClearsACardTheReaderStillHolds() {
        fake.show(List.of("Remove Card"));
        fake.cardin = 1;
        try (StderrCapture err = new StderrCapture()) {
            pad.removeCard();
            assertTrue(err.text().contains("left by an earlier session; removed"), err.text());
        }
        assertTrue(fake.names().contains("insert_smartcard"));
        assertEquals(Boolean.FALSE, pad.cardPresent());
    }

    @Test
    void abortClearsACardTheReaderHoldsThoughThisPcNeverInsertedOne() {
        fake.show(List.of("Remove Card"));
        fake.cardin = 1;
        assertTrue(pad.abortPayment().stream().anyMatch(d -> d.contains("left by an earlier session; removed")));
        fake.show(List.of("WELCOME"));
        assertFalse(pad.abortPayment().stream().anyMatch(d -> d.contains("removed")));
    }

    @Test
    void anInsertDoesNotWaitOnTheReadersCardInStatus() {
        fake.show(List.of("INSERT CARD"));
        fake.cardin = 0;
        pad.insertCard("EMV_CT_VISA", "Visa IINE.xml", null, null);
        assertFalse(fake.names().contains("get_cardin_status"));
    }

    private void insertThen(List<String> first) {
        fake.show(List.of("INSERT, TAP OR SWIPE CARD"));
        fake.afterInsert.add(first);
        pad.insertCard("EMV_CT_VISA", "Visa IINE.xml", null, null);
    }

    @Test
    void aChipNotReadIsReinsertedAndTheWaitGoesOn() {
        insertThen(List.of("CHIP NOT READ", "TRY AGAIN"));
        fake.show(List.of("CHIP NOT READ", "TRY AGAIN"));
        fake.afterRemove.add(List.of("INSERT, TAP OR SWIPE CARD"));
        fake.afterInsert.add(List.of("Enter PIN"));
        try (StderrCapture err = new StderrCapture()) {
            assertTrue(pad.atPrompt("Enter PIN", null).contains("Enter PIN"));
            assertTrue(err.text().contains("CHIP NOT READ"), err.text());
        }
        assertEquals(2, count("insert_smartcard"));
        assertTrue(fake.names().contains("remove_smartcard"));
        assertTrue(cardServer.requests.size() >= 4, "the image was loaded on BTT again");
    }

    @Test
    void aChipNeverReadIsReinsertedTwiceThenTheWaitFails() {
        insertThen(List.of("CHIP NOT READ"));
        fake.show(List.of("CHIP NOT READ"));
        VatsTimeout e = assertThrows(VatsTimeout.class, () -> pad.atPrompt("Enter PIN", Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("showing: CHIP NOT READ"), e.getMessage());
        assertEquals(3, count("insert_smartcard"));
    }

    @Test
    void noReinsertWithoutACardInserted() {
        fake.show(List.of("CHIP NOT READ"));
        assertThrows(VatsTimeout.class, () -> pad.atPrompt("Enter PIN", Duration.ofMillis(200)));
        assertFalse(fake.names().contains("insert_smartcard"));
    }

    @Test
    void aDeclineEndsAWaitForAnotherPromptAtOnce() {
        insertThen(List.of("DECLINED"));
        DeviceError e = assertThrows(DeviceError.class, () -> pad.atPrompt("Enter PIN", Duration.ofSeconds(30)));
        assertTrue(e.getMessage().contains("declined the payment while CAT waited for 'Enter PIN'"), e.getMessage());
        assertTrue(pad.declinedAtNanos().isPresent());
    }

    @Test
    void aSwipeStartsANewPaymentWithNoDeclineCarried() {
        // Run 827f698b5965: only a dip cleared the mark, so one DECLINED failed every later swipe's receipt wait.
        pad.declinedAt(1L);
        pad.insertCard("Fleet1");
        assertTrue(pad.declinedAtNanos().isEmpty());
    }

    @Test
    void aWaitForDeclinedItselfMatchesIt() {
        insertThen(List.of("DECLINED"));
        assertTrue(pad.atPrompt("DECLINED", null).contains("DECLINED"));
    }

    @Test
    void settleEndsOnAQuietTerminalAndNotesADecline() {
        fake.show(List.of("APPROVED"));
        fake.redrawing = false;
        assertTrue(pad.settle(Duration.ofSeconds(5)));
        assertTrue(pad.declinedAtNanos().isEmpty());
        fake.show(List.of("DECLINED"));
        fake.redrawing = false;
        assertTrue(pad.settle(Duration.ofSeconds(5)));
        assertTrue(pad.declinedAtNanos().isPresent());
    }

    @Test
    void settleWaitsOutABusyTerminalToItsBudget() {
        fake.show(List.of("Authorizing", "Please wait..."));
        fake.redrawing = false;
        assertFalse(pad.settle(Duration.ofMillis(500)));
        assertFalse(pad.settle(Duration.ZERO));
    }

    @Test
    void expectPromptClearedPassesWhenGoneAndNamesThePromptWhenNot() {
        fake.show(List.of("PLEASE WAIT"));
        assertEquals("PLEASE WAIT", pad.expectPromptCleared("Enter PIN", null));
        VatsTimeout e = assertThrows(VatsTimeout.class,
                () -> pad.expectPromptCleared("please wait", Duration.ofMillis(100)));
        assertTrue(e.getMessage().startsWith("pinpad still showing 'please wait'"), e.getMessage());
    }

    @Test
    void aContactlessCardIsPresentedReadAndTakenAway() throws IOException {
        Rig.Vats config = new Rig.Vats("pinpad.testrig.invalid", null, null, cardServer.port(), null, "contactless",
                null, Map.of());
        Path images = dir.resolve("images");
        VatsPinpad tap = new VatsPinpad(fake, config, new CardImages(List.of(images)), null, Duration.ofSeconds(2),
                null);
        tap.insertCard("EMV_CTLS_UNIONPAY", "Visa IINE.xml", null, null);
        assertTrue(fake.names().contains("wait_html_display_update"));
        assertFalse(fake.names().contains("insert_smartcard"), "a tap is not an insert");
        assertEquals(3, cardServer.requests.size(), "SELECT, START, then STOP");
        tap.close();
    }

    @Test
    void aCardServerProblemFailsTheCardLoadWithItsCause() throws IOException {
        Rig.Vats config = new Rig.Vats("pinpad.testrig.invalid", null, null, cardServer.port(), null, null, null,
                Map.of());
        VatsPinpad noBtt = new VatsPinpad(fake, config, new CardImages(List.of(dir.resolve("images"))), null,
                Duration.ofSeconds(2), "BTT is not started for a terminal-only session");
        DeviceError e = assertThrows(DeviceError.class,
                () -> noBtt.insertCard("EMV_CT_VISA", "Visa IINE.xml", null, null));
        assertTrue(e.getMessage().contains("BTT is not started for a terminal-only session"), e.getMessage());
    }

    @Test
    void commConfigIsTheTerminalsNetworkSettingsAndAnSdkFailureIsNamed() {
        assertEquals("True", pad.commConfig("eth0").get("DHCP"));
        AgentError e = assertThrows(AgentError.class, () -> pad.commConfig("bluetooth"));
        assertTrue(e.getMessage().contains("unknown interface"), e.getMessage());
    }

    @Test
    void theDisplayIsTheLinesAndTheirJoinedPrompt() {
        fake.show(List.of("TOTAL", "$7.75"));
        assertEquals(List.of("TOTAL", "$7.75"), pad.display().lines());
        assertEquals("TOTAL $7.75", pad.display().prompt());
        fake.show(List.of());
        assertNull(pad.display().prompt());
    }

    @Test
    void thePatternHelpersArePythons() {
        assertEquals("(?i)\\$7\\.75", VatsPinpad.pattern("$7.75"));
        assertEquals("(?i)(?:a)|(?:chip not read)", VatsPinpad.anyOf(List.of("(?i)a", VatsPinpad.CHIP_NOT_READ)));
        assertEquals(List.of("Enter PIN"), VatsPinpad.promptLines(List.of("Enter PIN", "****", "  ")));
        assertEquals("'it'", PyText.repr("it"));
        assertEquals("\"it's\"", PyText.repr("it's"));
        assertEquals("Enter\\ PIN\\#", PyText.escape("Enter PIN#"));
    }
}
