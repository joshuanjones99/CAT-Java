package com.verifone.cat.devices.viperpay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pos.pinpad.PinpadDevice;

/**
 * {@link ViperPayPinpad} against JDK-written bytes and real loopback sockets: a real listener receives the
 * simulator's pushes, and a real server stands in for the simulator's key port. Ported from the Python
 * CAT's {@code tests/unit/test_devices_viperpay.py}.
 *
 * <p>No live simulator is involved. Nothing here proves Petro publishes a display when the EPS gives up,
 * nor what a live pinpad shows.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class ViperPayPinpadTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration SHORT = Duration.ofMillis(300);

    private static final byte[] DONATE = UserEventAndDisplayTest.fixture("display_request_get_any_key.bin");
    private static final byte[] KEY_EVENT = UserEventAndDisplayTest.fixture("user_event_key.bin");
    private static final byte[] SWIPE = UserEventAndDisplayTest.fixture("display_request_read_card.bin");
    private static final byte[] ENTRY = UserEventAndDisplayTest.fixture("display_request_get_decimals.bin");
    private static final byte[] WELCOME = UserEventAndDisplayTest.fixture("display_request_welcome.bin");
    private static final byte[] WELCOME_ONLY = UserEventAndDisplayTest.fixture("display_request_welcome_only.bin");

    /** {@code SWIPE} as POP 002 pushes it: the same bytes with the popID string changed. */
    private static final byte[] SWIPE_POP2 = replaceOnce(SWIPE,
            new byte[] {0, 3, '0', '0', '1'}, new byte[] {0, 3, '0', '0', '2'});

    /** A real server on the simulator's key port; keeps what each connection sent. */
    static final class KeyPort implements AutoCloseable {

        final ServerSocket server;
        final List<byte[]> received = new CopyOnWriteArrayList<>();
        final CountDownLatch got = new CountDownLatch(1);

        KeyPort() throws IOException {
            server = new ServerSocket(0);
            Thread thread = new Thread(this::serve, "fake-key-port");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket conn = server.accept()) {
                conn.setSoTimeout((int) WAIT.toMillis());
                received.add(conn.getInputStream().readAllBytes());
                got.countDown();
            } catch (IOException closedBeforeAKey) {
                // the test closed the port before any key was sent
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private KeyPort keys;
    private ViperPayPinpad pinpad;
    private ScheduledExecutorService later;

    @BeforeEach
    void setUp() throws IOException {
        keys = new KeyPort();
        pinpad = new ViperPayPinpad("localhost", "1", 0, keys.port(), WAIT, WAIT);
        pinpad.start();
        later = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "viperpay-test-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        later.shutdownNow();
        pinpad.close();
        keys.close();
    }

    private void push(byte[] payload) {
        push(pinpad.listenPort(), payload);
    }

    private static void push(int port, byte[] payload) {
        try (Socket conn = new Socket("localhost", port)) {
            conn.getOutputStream().write(payload);
        } catch (IOException e) {
            throw new IllegalStateException("cannot push to port " + port, e);
        }
    }

    private void pushLater(byte[] payload, Duration delay) {
        int port = pinpad.listenPort();
        later.schedule(() -> push(port, payload), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void awaitKey() throws InterruptedException {
        assertTrue(keys.got.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "no event reached the simulator's port");
    }

    // -- the pinpad, and its prompts ---------------------------------------------

    @Test
    void itIsAPinpadDevice() {
        assertInstanceOf(PinpadDevice.class, pinpad);
        assertEquals("1", pinpad.id());
    }

    @Test
    void aPushedDisplayIsFoundByPromptAndItsKeyReachesTheSimulator() throws InterruptedException {
        push(DONATE);
        assertTrue(pinpad.atPrompt("DONATE").toLowerCase().contains("donate"));
        assertEquals(List.of("Would you like to donate", "to CHARITY?"), pinpad.display().lines());

        pinpad.key("$0.51");
        awaitKey();
        assertArrayEquals(KEY_EVENT, keys.received.get(0));
    }

    @Test
    void aKeyThatIsNotOnOfferTimesOutByName() {
        push(DONATE);
        pinpad.atPrompt("donate");
        PromptTimeout timedOut = assertThrows(PromptTimeout.class, () -> pinpad.key("Yes", null, SHORT));
        assertTrue(timedOut.getMessage().contains("offering key 'Yes'"), timedOut.getMessage());
    }

    @Test
    void aPromptThatNeverArrivesTimesOutByName() {
        PromptTimeout timedOut = assertThrows(PromptTimeout.class, () -> pinpad.atPrompt("donate", SHORT));
        assertTrue(timedOut.getMessage().contains("pinpad 1 prompt 'donate'"), timedOut.getMessage());
    }

    @Test
    void aMissedPromptSaysWhatThePinpadShowed() {
        push(WELCOME);
        pinpad.atPrompt("WELCOME", WAIT);
        PromptTimeout timedOut = assertThrows(PromptTimeout.class, () -> pinpad.atPrompt("Add a Tip ?", SHORT));
        assertTrue(timedOut.getMessage().contains("showing '[WELCOME TO OUR STORE, INSERT TAP"),
                timedOut.getMessage());
    }

    // -- cards, entries and answered displays ---------------------------------

    @Test
    void theCardAndDecimalEventsAreWhatTheJdkWrites() {
        assertArrayEquals(UserEventAndDisplayTest.fixture("user_event_card.bin"),
                UserEvent.card("1", "EMV AMMEX CREDIT").encode());
        assertArrayEquals(UserEventAndDisplayTest.fixture("user_event_decimal.bin"),
                UserEvent.entry("1", "123456").encode());
    }

    @Test
    void aCardWaitsForACardReadAndReachesTheSimulator() throws InterruptedException {
        push(DONATE);
        PromptTimeout timedOut = assertThrows(PromptTimeout.class,
                () -> pinpad.insertCard("EMV AMMEX CREDIT", null, SHORT));
        assertTrue(timedOut.getMessage().contains("pinpad 1 reading a card"), timedOut.getMessage());
        assertFalse(keys.got.await(50, TimeUnit.MILLISECONDS));
        assertTrue(keys.received.isEmpty());

        push(SWIPE);
        pinpad.atPrompt("swipe card");
        pinpad.insertCard("EMV AMMEX CREDIT");
        awaitKey();
        assertArrayEquals(UserEvent.card("001", "EMV AMMEX CREDIT").encode(), keys.received.get(0));
    }

    @Test
    void aSwipeIsTheSameReadAsAnInsert() throws InterruptedException {
        push(SWIPE);
        pinpad.swipeCard("VCMG_LOYALTY_CARD2", null, WAIT);
        awaitKey();
        assertArrayEquals(UserEvent.card("001", "VCMG_LOYALTY_CARD2").encode(), keys.received.get(0));
    }

    @Test
    void aDecimalWaitsForAnEntryPromptAndIsSentUnexponented() throws InterruptedException {
        push(SWIPE);
        PromptTimeout timedOut = assertThrows(PromptTimeout.class,
                () -> pinpad.decimal(new BigDecimal("123456"), null, SHORT));
        assertTrue(timedOut.getMessage().contains("pinpad 1 asking for an entry"), timedOut.getMessage());

        push(ENTRY);
        pinpad.atPrompt("loyalty id");
        pinpad.decimal(new BigDecimal("2000007259879938937"), null, null);
        awaitKey();
        assertArrayEquals(UserEvent.entry("1", "2000007259879938937").encode(), keys.received.get(0));
    }

    @Test
    void digitForDigitEntryKeepsTheLeadingZerosThatADecimalWouldLose() throws InterruptedException {
        push(ENTRY);
        pinpad.atPrompt("loyalty id");
        pinpad.decimalText("0001234", null, WAIT);
        awaitKey();
        assertArrayEquals(UserEvent.entry("1", "0001234").encode(), keys.received.get(0));
    }

    @Test
    void anEntryThatIsNotAnEntryIsRefusedBeforeAnythingIsSent() {
        assertThrows(IllegalArgumentException.class, () -> pinpad.decimalText("12a", null, SHORT));
        assertTrue(keys.received.isEmpty());
    }

    @Test
    void aDecimalCanWaitForThePromptThatFollowsIt() {
        push(ENTRY);
        pinpad.atPrompt("loyalty id");
        pushLater(DONATE, Duration.ofMillis(200));
        String shown = pinpad.decimal(new BigDecimal("123456"), "donate", null);
        assertTrue(shown.toLowerCase().contains("donate"), shown);
    }

    @Test
    void anAnsweredDisplayIsNotAnsweredAgain() {
        // PETROCORE-36940 swiped a card 15 ms after keying PROGRAM02, into the same display.
        push(SWIPE);
        pinpad.insertCard("VCMG_LOYALTY_CARD2");
        assertThrows(PromptTimeout.class, () -> pinpad.insertCard("EMV AMMEX CREDIT", null, SHORT));
        assertTrue(assertThrows(PromptTimeout.class, () -> pinpad.atPrompt("swipe card", SHORT))
                .getMessage().contains("prompt 'swipe card'")); // still up, but already answered

        push(SWIPE); // the pinpad asks again
        pinpad.atPrompt("swipe card");
        pinpad.insertCard("EMV AMMEX CREDIT");
    }

    @Test
    void removeCardRaisesRatherThanPretends() {
        DeviceUnavailable unavailable = assertThrows(DeviceUnavailable.class, () -> pinpad.removeCard());
        assertTrue(unavailable.getMessage().contains("VATS only"), unavailable.getMessage());
    }

    @Test
    void tapCardRaisesRatherThanPretends() {
        assertThrows(DeviceUnavailable.class, () -> pinpad.tapCard("VISA", null, SHORT));
    }

    @Test
    void aKeyPressThatCannotReachTheSimulatorSaysSo() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        try (ViperPayPinpad unreachable = new ViperPayPinpad("localhost", "1", 0, closedPort, WAIT, WAIT)) {
            unreachable.start();
            push(unreachable.listenPort(), DONATE);
            unreachable.atPrompt("donate");
            assertThrows(DeviceUnreachable.class, () -> unreachable.key("$0.51"));
        }
    }

    // -- expectPromptCleared: the wait that replaces a fixed pause ----------------

    @Test
    void aPromptIsClearedByTheNextDisplayThePinpadPushes() {
        push(DONATE);
        pinpad.atPrompt("donate");
        pushLater(SWIPE, Duration.ofMillis(200));
        assertTrue(pinpad.expectPromptCleared("donate", WAIT).contains("SWIPE CARD"));
    }

    @Test
    void aPromptThatIsStillUpTimesOutByName() {
        push(DONATE);
        pinpad.atPrompt("donate");
        PromptTimeout timedOut = assertThrows(PromptTimeout.class,
                () -> pinpad.expectPromptCleared("donate", SHORT));
        assertTrue(timedOut.getMessage().contains("pinpad 1 clearing prompt 'donate'"), timedOut.getMessage());
    }

    @Test
    void aPromptThatWasNeverUpIsAlreadyCleared() {
        push(SWIPE);
        pinpad.atPrompt("swipe card");
        assertTrue(pinpad.expectPromptCleared("donate", SHORT).contains("SWIPE CARD"));
    }

    @Test
    void nothingIsSentToTheSimulatorWhileWaitingForAPromptToClear() {
        push(DONATE);
        pinpad.atPrompt("donate");
        assertThrows(PromptTimeout.class, () -> pinpad.expectPromptCleared("donate", SHORT));
        assertTrue(keys.received.isEmpty());
    }

    // -- one listener, several POPs, and streams it cannot read -------------------

    @Test
    void anotherPopsDisplayDoesNotMakeAnAnsweredOneLookNew() {
        assertEquals("002", Display.decode(SWIPE_POP2).popId());
        push(SWIPE);
        pinpad.insertCard("VCMG_LOYALTY_CARD2");
        push(SWIPE_POP2);
        assertTrue(pinpad.awaitSequence(2, WAIT));
        assertThrows(PromptTimeout.class, () -> pinpad.insertCard("EMV AMMEX CREDIT", null, SHORT));
    }

    @Test
    void aStreamTheDecoderChokesOnDoesNotStopTheListener() {
        byte[] malformed = SWIPE.clone();
        malformed[4] = 0x71; // a back-reference before any object
        assertThrows(IllegalArgumentException.class, () -> Display.decode(malformed));
        push(malformed);
        push(DONATE);
        assertTrue(pinpad.atPrompt("donate", WAIT).toLowerCase().contains("donate"));
    }

    @Test
    void aPromptThatArrivesAfterTheWaitBeganIsWaitedFor() {
        push(DONATE);
        pinpad.atPrompt("donate");
        pushLater(ENTRY, Duration.ofMillis(100));
        assertTrue(pinpad.atPrompt("loyalty", WAIT).toLowerCase().contains("loyalty"));
    }

    // -- WAIT_FOR_PROMPT with DISPLAY_KEY_EXACT_MATCH=YES -----------------------

    @Test
    void anExactPromptIsFoundInAPushedDisplay() {
        push(WELCOME);
        assertEquals("WELCOME TO OUR STORE,INSERT TAP OR SWIPE CARD",
                pinpad.atExactPrompt("WELCOME TO OUR STORE,INSERT TAP OR SWIPE CARD", WAIT));
    }

    @Test
    void aDisplayThatOnlyContainsTheKeyIsNotAnExactMatch() {
        push(WELCOME);
        assertTrue(pinpad.atPrompt("WELCOME TO OUR STORE", WAIT).contains("WELCOME TO OUR STORE"));
        PromptTimeout timedOut = assertThrows(PromptTimeout.class,
                () -> pinpad.atExactPrompt("WELCOME TO OUR STORE", SHORT));
        assertTrue(timedOut.getMessage().contains("exact prompt 'WELCOME TO OUR STORE' (showing "),
                timedOut.getMessage());
        // The raw lines, as the display carried them (run 3bc049ab4919's trailing comma).
        assertTrue(timedOut.getMessage().contains("lines ['WELCOME TO OUR STORE', 'INSERT TAP OR SWIPE CARD'"),
                timedOut.getMessage());
    }

    @Test
    void anExactPromptWaitsForTheDisplayThatMatches() {
        push(WELCOME);
        assertTrue(pinpad.awaitSequence(1, WAIT));
        pushLater(WELCOME_ONLY, Duration.ofMillis(200));
        assertEquals("WELCOME TO OUR STORE", pinpad.atExactPrompt("WELCOME TO OUR STORE", WAIT));
    }

    @Test
    void readingADisplaySendsNothingToTheSimulatorPort() {
        push(WELCOME);
        pinpad.atPrompt("WELCOME", WAIT);
        assertTrue(keys.received.isEmpty());
    }

    private static byte[] replaceOnce(byte[] data, byte[] from, byte[] to) {
        for (int i = 0; i + from.length <= data.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(data, i, i + from.length), from)) {
                byte[] out = data.clone();
                System.arraycopy(to, 0, out, i, to.length);
                return out;
            }
        }
        throw new IllegalStateException("fixture has no popID to replace");
    }
}
