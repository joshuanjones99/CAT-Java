package com.verifone.cat.devices.forecourt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DCR link checks against the suite's own log lines, from run 33e23af47960 (build 65, DCR 1 refused for four hours) and
 * run 6b35dd5a9d86 (build 1097, DCR 1 connected). Ported from the Python {@code test_petro_dcr_link.py}.
 */
class DcrLinkTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 18, 50, 30);
    private static final String RECONNECT = "Oct 01 %s.712 PATS INFO  DRWAdapterImpl [KeepAlive Thread for DCR[2]] - "
            + "DCR[1] comm error..reconnecting";
    private static final String DISPLAY = "Oct 01 %s.106 PATS INFO  DRWAdapterImpl [KeepAlive Thread for DCR[13]] - "
            + "Sending Display command to DCR[1]:";
    /** master055.02 build 41: the DCR is named only on the second line. */
    private static final String BUILD_41_DISPLAY = "Oct 01 %s.418 PATS INFO  DRWAdapterImpl [pool-1-thread-36] - "
            + "Sending Display command to DCR[{]}:\n"
            + "Oct 01 %s.418 PATS INFO  DcrSimRT [pool-1-thread-36] - displayPromptMenu Position %d";
    private static final String OTHER_DCR = "Oct 01 %s.290 PATS INFO  DRWAdapterImpl [FUEL-StatusNotifier-[12]-10] - "
            + "DCR[13] comm error..reconnecting";

    @TempDir
    Path tmp;

    private Path log(String... lines) throws IOException {
        Path path = tmp.resolve("log4j-application.log");
        Files.writeString(path, Stream.of(lines).collect(Collectors.joining("\n", "", "\n")), StandardCharsets.UTF_8);
        return path;
    }

    private static String fmt(String pattern, Object... args) {
        return String.format(pattern, args);
    }

    @Test
    void aLiveReconnectLoopWithNoDisplayIsAFault() throws IOException {
        Path log = log(fmt(RECONNECT, "18:49:01"), fmt(RECONNECT, "18:49:36"), fmt(RECONNECT, "18:50:12"));
        String fault = DcrLink.fault(log, 1, NOW);
        assertTrue(fault != null && fault.contains("reconnected it at least 3 times since Oct 01 18:49:01"), fault);
        assertTrue(fault.contains("GetSupportedTerminalIds"), fault);
    }

    @Test
    void aDisplayAfterTheReconnectsMeansConnected() throws IOException {
        Path log = log(fmt(RECONNECT, "18:49:01"), fmt(RECONNECT, "18:49:36"), fmt(RECONNECT, "18:50:00"),
                fmt(DISPLAY, "18:50:12"));
        assertNull(DcrLink.fault(log, 1, NOW));
    }

    @Test
    void oneOrTwoReconnectsAreNotYetAFault() throws IOException {
        Path log = log(fmt(DISPLAY, "18:48:00"), fmt(RECONNECT, "18:49:36"), fmt(RECONNECT, "18:50:12"));
        assertNull(DcrLink.fault(log, 1, NOW));
    }

    @Test
    void anotherDcrsLoopIsNotDcr1s() throws IOException {
        String[] lines = new String[5];
        for (int n = 0; n < lines.length; n++) {
            lines[n] = fmt(OTHER_DCR, "18:50:0" + n);
        }
        assertNull(DcrLink.fault(log(lines), 1, NOW));
    }

    @Test
    void aStoppedSuitesLeftoverLoopIsNotAFault() throws IOException {
        String[] lines = new String[4];
        for (int n = 0; n < lines.length; n++) {
            lines[n] = fmt(RECONNECT, "18:4" + n + ":00");
        }
        assertNull(DcrLink.fault(log(lines), 1, LocalDateTime.of(2026, 10, 2, 12, 0, 0)));
    }

    @Test
    void noLogIsUnknownNotDown() {
        assertNull(DcrLink.fault(tmp.resolve("absent.log"), 1, NOW));
    }

    @Test
    void aBuild41DisplayMeansConnected() throws IOException {
        Path log = log(fmt(RECONNECT, "18:49:01"), fmt(RECONNECT, "18:49:36"), fmt(RECONNECT, "18:50:00"),
                fmt(BUILD_41_DISPLAY, "18:50:12", "18:50:12", 1));
        assertNull(DcrLink.fault(log, 1, NOW));
    }

    @Test
    void aBuild41DisplayOnDcr10IsNotDcr1s() throws IOException {
        Path log = log(fmt(BUILD_41_DISPLAY, "18:50:12", "18:50:12", 10));
        assertEquals(0, DcrLink.reinitSignal(log, 0, DcrLink.displaySignal(1)));
        assertEquals(1, DcrLink.reinitSignal(log, 0, DcrLink.displaySignal(10)));
    }
}
