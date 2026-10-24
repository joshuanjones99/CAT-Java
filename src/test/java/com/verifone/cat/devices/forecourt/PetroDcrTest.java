package com.verifone.cat.devices.forecourt;

import static com.verifone.cat.devices.forecourt.Fakes.FAST;
import static com.verifone.cat.devices.forecourt.Fakes.HOST;
import static com.verifone.cat.devices.forecourt.Fakes.fixture;
import static com.verifone.cat.devices.forecourt.Fakes.push;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnreachable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The DCR client against loopback fakes. Ported from the Python {@code test_devices_petro_forecourt.py}'s DCR section. */
class PetroDcrTest {

    private Fakes.Simulator dcrSim;
    private PetroDcr dcr;
    private int listenPort;

    @BeforeEach
    void start() {
        dcrSim = new Fakes.Simulator(fixture("dcr_ack.bin"));
        listenPort = Fakes.freePort();
        dcr = PetroDcr.started(HOST, 1, listenPort, dcrSim.port(), FAST);
    }

    @AfterEach
    void stop() {
        dcr.close();
        dcrSim.close();
    }

    @Test
    void theDcrLogsInAndAnswersEachDisplayPush() {
        assertArrayEquals(fixture("dcr_login.bin"), dcrSim.waitFor(1).get(0));
        assertArrayEquals(fixture("dcr_ack.bin"), push(listenPort, fixture("dcr_display.bin"), true));
        dcr.waitForPrompt("  welcome ");
        assertEquals(List.of("", "WELCOME", "PAY INSIDE"), dcr.promptLines());
    }

    @Test
    void aSuiteThatReadsTheHeaderBeforeItWritesIsAnswered() throws IOException {
        // Master build 129 opens its reader before it writes; a reply sent whole after the read deadlocked (run 824d4a128c57).
        try (Socket conn = new Socket()) {
            conn.connect(new InetSocketAddress(HOST, listenPort), 2000);
            conn.setSoTimeout(2000);
            InputStream in = conn.getInputStream();
            byte[] header = in.readNBytes(JavaSerial.MAGIC.length);
            assertArrayEquals(JavaSerial.MAGIC, header); // before any write
            conn.getOutputStream().write(fixture("dcr_display.bin"));
            conn.getOutputStream().flush();
            byte[] rest = in.readAllBytes(); // the suite closes once the reply is whole
            assertArrayEquals(fixture("dcr_ack.bin"), concat(header, rest));
        }
        dcr.waitForPrompt("WELCOME");
        assertEquals(List.of("", "WELCOME", "PAY INSIDE"), dcr.promptLines());
    }

    @Test
    void aSoftKeyReachesTheSimulator() {
        dcr.press("L3");
        assertArrayEquals(fixture("dcr_key_l3.bin"), dcrSim.waitFor(2).get(1));
    }

    @Test
    void aCardAndAKeyPressReachTheSimulator() {
        dcr.insertCard("VCMG_LOYALTY_CARD2");
        dcr.keyPress("12345");
        var sent = dcrSim.waitFor(3);
        assertArrayEquals(fixture("dcr_card.bin"), sent.get(1));
        assertArrayEquals(fixture("dcr_keyboard.bin"), sent.get(2));
    }

    @Test
    void aDcrPrintIsKeptAndReadBackAfterAMark() {
        // GenDcrInput.java: the PrinterParameters push PATS's DCRPrinterSim records.
        int mark = dcr.printCount();
        push(listenPort, fixture("dcr_printer.bin"), true);
        assertEquals("WELCOME\nPUMP# 1\nTRAN# 9010042\nTHANK YOU", dcr.receiptSince(mark, Duration.ofSeconds(5)));
        assertThrows(ForecourtWaitExpired.class, () -> dcr.receiptSince(dcr.printCount(), Duration.ofMillis(200)));
    }

    @Test
    void aPromptThatNeverShowsTimesOutNamingTheScreen() {
        push(listenPort, fixture("dcr_display.bin"), true);
        var expired = assertThrows(ForecourtWaitExpired.class,
                () -> dcr.waitForPrompt("REMOVE NOZZLE", Duration.ofSeconds(1)));
        assertTrue(expired.getMessage().contains("PAY INSIDE"), expired.getMessage());
    }

    @Test
    void aPromptTimeoutNamesEachSoftKeyAsPatsDoes() {
        // A menu's first line is row 1: L4, not L1 (run b4f11527e248).
        dcr.setMenusForTest(new String[] {"< NO", "< YES", "", ""}, new String[] {"CANCEL >", "HELP >", "", ""});
        var expired = assertThrows(ForecourtWaitExpired.class,
                () -> dcr.waitForPrompt("REWARD CARD Y/N?", Duration.ofMillis(10)));
        assertTrue(expired.getMessage().contains("L4='< NO', L3='< YES', R4='CANCEL >'"), expired.getMessage());
    }

    @Test
    void aPromptShownSinceTheLastWaitCountsOnceItHasMovedOn() {
        // 37053 keys its ZIP before waiting; the DCR took the keys and moved on (run 0969705f4766).
        push(listenPort, fixture("dcr_display.bin"), true);
        push(listenPort, fixture("dcr_display_wayne_welcome.bin"), true);
        assertEquals(List.of("WELCOME ", " "), dcr.promptLines());
        dcr.waitForPrompt("PAY INSIDE", Duration.ofSeconds(1)); // shown, then moved on: still a match
        assertThrows(ForecourtWaitExpired.class, () -> dcr.waitForPrompt("PAY INSIDE", Duration.ofMillis(300)));
    }

    @Test
    void theWayneWelcomeIsHeard() {
        assertArrayEquals(fixture("dcr_ack.bin"), push(listenPort, fixture("dcr_display_wayne_welcome.bin"), true));
        dcr.waitForPrompt("WELCOME");
        assertEquals(List.of("WELCOME ", " "), dcr.promptLines());
    }

    @Test
    void theLoginBringsBackNoScreen() {
        // The suite answers a login with an empty list, which is why the listener must be up before the screen changes.
        assertEquals(List.of(), JavaSerial.loads(fixture("dcr_login_resend.bin")));
        push(listenPort, fixture("dcr_login_resend.bin"), true);
        var expired = assertThrows(ForecourtWaitExpired.class,
                () -> dcr.waitForPrompt("WELCOME", Duration.ofMillis(300)));
        assertTrue(expired.getMessage().contains("showing []"), expired.getMessage());
        push(listenPort, fixture("dcr_display_wayne_welcome.bin"), true);
        push(listenPort, fixture("dcr_login_resend.bin"), true);
        dcr.waitForPrompt("WELCOME"); // an empty resend leaves the screen as it was
    }

    @Test
    void aFailedLoginGivesTheListenPortBack() throws Exception {
        int port = Fakes.freePort();
        int unused = Fakes.freePort(); // nothing listens here: the login is refused
        assertThrows(DeviceUnreachable.class, () -> PetroDcr.started(HOST, 1, port, unused, FAST));
        new PushListener(port, obj -> { }, Duration.ofSeconds(1), null).close();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
