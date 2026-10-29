package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.rig.Rig;

/**
 * The shipped {@code agent.py}, extracted and run as a real subprocess through the real {@link AgentClient}, against
 * the Python CAT's stand-in SDK ({@link StandInSdk}): the Python's {@code test_vats_agent.py} process tests. Skipped
 * where no Python 3.9+ is found. Evidence about this repository, not a rig.
 */
class AgentProcessTest {

    @TempDir
    Path dir;

    private StandInSdk fake;
    private AgentClient agent;

    @BeforeEach
    void start() throws IOException {
        Path python = StandInSdk.python();
        Assumptions.assumeTrue(python != null, "no Python 3.9+ on this machine to run agent.py");
        fake = StandInSdk.write(dir);
        agent = new AgentClient(fake.sdk(python), 11000, dir.resolve("work"), AgentScript.extract(dir.resolve("a")))
                .start();
    }

    @AfterEach
    void stop() {
        if (agent != null) {
            agent.close();
        }
    }

    @Test
    void theExtractedAgentIsTheShippedOneByteForByte() throws IOException {
        assertArrayEquals(AgentScript.bytes(), Files.readAllBytes(dir.resolve("a").resolve("agent.py")));
        assertEquals(dir.resolve("a").resolve("agent.py"), AgentScript.extract(dir.resolve("a")), "idempotent");
    }

    @Test
    void helloReadsTheModelAndHandsTheSdkOnlyItsOwnArgv() throws IOException {
        Map<String, Object> result = agent.call("hello", 5, Map.of("response_ms", 100));
        assertEquals("M400", result.get("model"));
        assertEquals("VOS2", result.get("os"));
        assertEquals("/0", result.get("region"));
        // The SDK prints on import; the reply still parsed, so stdout stayed protocol-only.
        List<Object> first = fake.calls().stream().filter(c -> c.get(0).equals("set_terminal_specific_values"))
                .findFirst().orElseThrow();
        assertEquals(List.of("--km4_port", "11000"), ((List<?>) first.get(1)).get(1));
        assertTrue(Files.readString(agent.logFile()).contains("terminal.py: imported"), "the SDK's prints go to the log");
    }

    @Test
    void anSdkFailureIsAReplyAndTheAgentStaysUp() {
        AgentError e = assertThrows(AgentError.class, () -> agent.call("comm", Map.of("interface", "bluetooth")));
        assertTrue(e.getMessage().contains("unknown interface"), e.getMessage());
        Map<?, ?> config = (Map<?, ?>) agent.call("comm", Map.of("interface", "eth0")).get("config");
        assertEquals("True", config.get("DHCP"));
    }

    @Test
    void thePinpadDrivesTheRealAgent() throws IOException {
        Path images = dir.resolve("images");
        Files.createDirectories(images);
        Files.writeString(images.resolve("Visa IINE.xml"), "<card/>");
        Path msr = dir.resolve("MSRCardData.xml");
        Files.writeString(msr, "<Cards><Card><label>Fleet1</label><track2>7078=2503</track2></Card></Cards>");
        try (CardServerStub cardServer = CardServerStub.answering()) {
            Rig.Vats config = new Rig.Vats("pinpad.testrig.invalid", null, null, cardServer.port(), null, null, null,
                    Map.of());
            VatsPinpad pad = new VatsPinpad(agent, config, new CardImages(List.of(images)), msr,
                    Duration.ofSeconds(2), null);
            fake.show(List.of("INSERT, TAP OR", "SWIPE CARD"), List.of("CREDIT"), -1);
            assertTrue(pad.atPrompt("swipe card", null).contains("SWIPE CARD"));
            VatsTimeout timeout = assertThrows(VatsTimeout.class, () -> pad.atPrompt("$7.75", Duration.ofMillis(200)));
            assertTrue(timeout.getMessage().contains("showing: INSERT, TAP OR | SWIPE CARD"), timeout.getMessage());
            pad.key("CREDIT");
            pad.insertCard("EMV_CT_VISA", "Visa IINE.xml", null, null);
            pad.insertCard("Fleet1");
            pad.enterDigits("0042", null, null);
            List<List<Object>> calls = fake.calls();
            assertTrue(fake.names().contains("insert_smartcard"));
            assertTrue(calls.contains(List.of("swipe_magcard", List.of("", "7078=2503", ""))), calls.toString());
            assertTrue(calls.contains(List.of("enter_keys", List.of("0042<CONFIRM>"))), calls.toString());
            assertEquals(2, cardServer.requests.size(), "SELECT and START on BTT");
            pad.close();
        }
    }

    @Test
    void aClosedAgentIsNotRunningAndSaysWhereItsLogIs() {
        agent.close();
        DeviceUnreachable e = assertThrows(DeviceUnreachable.class, () -> agent.call("screen"));
        assertTrue(e.getMessage().contains("not running; see " + agent.logFile()), e.getMessage());
    }

    @Test
    void anAgentThatDiesIsNamedWithItsLog() throws IOException {
        agent.close();
        Path python = StandInSdk.python();
        VatsSdk broken = new VatsSdk(dir.resolve("nowhere"), python);
        Files.createDirectories(broken.app());
        agent = new AgentClient(broken, 11000, dir.resolve("work2"), AgentScript.extract(dir.resolve("a"))).start();
        DeviceUnreachable e = assertThrows(DeviceUnreachable.class, () -> agent.call("hello", 5, Map.of()));
        assertTrue(e.getMessage().contains("exited during 'hello'") || e.getMessage().contains("not running"),
                e.getMessage());
        assertTrue(Files.readString(agent.logFile()).contains("ModuleNotFoundError"), "its traceback is logged");
    }
}
