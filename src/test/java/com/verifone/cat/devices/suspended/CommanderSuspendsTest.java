package com.verifone.cat.devices.suspended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.eps.TestRig;
import com.verifone.cat.preflight.Surfaces.UnsettledSuspends;

/**
 * {@link CommanderSuspends}: the Python CAT's suspended-ticket tests in {@code test_devices_preflight.py}, plus
 * the request itself driven over HTTP to {@link FakeCommander} on loopback. Evidence about this repository, not
 * about a Commander.
 */
class CommanderSuspendsTest {

    /**
     * The shape of the lab Commander's {@code vrubyrept} Day summary, 2026-10-11, cut to the suspend counters,
     * read with one ticket parked on Reg101 (the Python's HANDOFF.md 1.128).
     */
    static final String SUMMARY_WITH_ONE_PARKED = """
            <ns0:summaryPd xmlns:ns0="urn:vfi-sapphire:rp.summary.2001-10-01"
             xmlns:ns1="urn:vfi-sapphire:np.domain.2001-07-01"><totals>
            <suspendSettled><count>0</count><amount>0.00</amount></suspendSettled>
            <suspendUnsettled><count>1</count><amount>18.25</amount></suspendUnsettled>
            <suspendVoid><count>0</count><amount>0.00</amount></suspendVoid>
            <suspendTotal><count>1</count><amount>18.25</amount></suspendTotal></totals></ns0:summaryPd>""";

    @TempDir
    Path dir;

    static Element xml(String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theUnsettledSuspendsAreReadOffTheSummary() {
        UnsettledSuspends reading = new CommanderSuspends(() -> xml(SUMMARY_WITH_ONE_PARKED)).get();
        assertEquals(new UnsettledSuspends(1, "18.25"), reading);
    }

    @Test
    void aSummaryWithoutTheCounterIsAnErrorNotZero() {
        DeviceError e = assertThrows(DeviceError.class,
                () -> CommanderSuspends.parseUnsettledSuspends(xml("<summaryPd><totals/></summaryPd>")));
        assertTrue(e.getMessage().contains("no suspendUnsettled"), e.getMessage());
    }

    @Test
    void aCounterWithNoCountIsAnErrorNotZero() {
        DeviceError e = assertThrows(DeviceError.class, () -> CommanderSuspends.parseUnsettledSuspends(
                xml("<summaryPd><suspendUnsettled><amount>1.00</amount></suspendUnsettled></summaryPd>")));
        assertTrue(e.getMessage().contains("with no count"), e.getMessage());
    }

    @Test
    void anUnprefixedSummaryReadsTheSame() {
        UnsettledSuspends reading = CommanderSuspends.parseUnsettledSuspends(
                xml("<summaryPd><suspendUnsettled><count> 0 </count></suspendUnsettled></summaryPd>"));
        assertEquals(new UnsettledSuspends(0, ""), reading);
    }

    @Test
    void theDaySummaryIsOneVrubyreptReadForPeriodTwo() {
        try (FakeCommander fake = new FakeCommander(request -> "validate".equals(request.cmd())
                ? FakeCommander.Reply.ok(FakeCommander.FRESH_COOKIE)
                : FakeCommander.Reply.ok(SUMMARY_WITH_ONE_PARKED))) {
            PdkShim shim = PdkShim.onPort(fake.host(), fake.port(), Duration.ofSeconds(5), null);
            UnsettledSuspends reading = new CommanderSuspends(() -> CommanderSuspends.dayReport(shim)).get();

            assertEquals(new UnsettledSuspends(1, "18.25"), reading);
            List<String> commands = fake.commands();
            assertEquals(2, commands.size(), commands.toString());
            assertTrue(commands.get(0).startsWith("validate"), commands.toString());
            assertEquals("vrubyrept", commands.get(1));
            String body = fake.bodies().get(1);
            assertTrue(body.contains("period=2"), body);
            assertTrue(body.contains("filename=current"), body);
            assertTrue(body.contains("reptname=summary"), body);
        }
    }

    @Test
    void fromRigBuildsAReaderWithoutContactingTheCommander() {
        // Building the source must not reach the rig: the fixture's Commander is an .invalid name.
        assertInstanceOf(CommanderSuspends.class, CommanderSuspends.fromRig(TestRig.load(dir)));
    }
}
