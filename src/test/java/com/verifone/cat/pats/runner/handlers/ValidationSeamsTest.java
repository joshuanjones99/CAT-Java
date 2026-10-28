package com.verifone.cat.pats.runner.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.handlers.configuration.BuypassV2;
import com.verifone.cat.pats.runner.handlers.validation.ValidationContext;
import com.verifone.cat.pos.pinpad.PinpadDevice;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The validation family's two rig seams {@link StandardHandlers} now wires: {@code VALIDATE_TRANSACTION}'s POS reads
 * over the Commander's transaction list and TLog (the Python's {@code pos} fixture's {@code tlog_source}), and the
 * simulators' packet folders. A loopback Commander and a temporary tools folder only.
 */
class ValidationSeamsTest {

    private static final String LISTED = "<transNumList>"
            + "<trTickNum><posNum>101</posNum><trSeq>1010008</trSeq></trTickNum>"
            + "<trTickNum><posNum>101</posNum><trSeq>1010007</trSeq></trTickNum>"
            + "</transNumList>";

    @TempDir
    Path root;

    @Test
    void theNewestTransactionAndItsTlogComeFromTheCommander() throws Exception {
        try (FakeCommander commander = new FakeCommander(request -> switch (request.cmd()) {
            case "validate" -> FakeCommander.Reply.ok(FakeCommander.FRESH_COOKIE);
            case "vtransnumlist" -> FakeCommander.Reply.ok(LISTED);
            default -> FakeCommander.Reply.ok("<transList><trans type=\"sale\" trseq=\""
                    + request.fields().get("trseq") + "\"/></transList>");
        })) {
            HandlerFixtures fixtures = withCommander(WiringFixtures.unbrowsed(root, pinpad()),
                    PdkShim.onPort(commander.host(), commander.port(), Duration.ofSeconds(5), () -> ""));
            ValidationContext.PosTransactions reads = StandardHandlers.posTransactions(fixtures);

            assertEquals("1010008", reads.newestTransactionNumber());
            // A mark older than the newest: the newer one is read, and named.
            ValidationContext.PosTlog read = reads.newestTlog("1010007", 2.0);
            assertEquals("1010008", read.trseq());
            assertEquals("1010008", read.tlog().getAttribute("trseq"));
            // No mark: the newest, read blind.
            ValidationContext.PosTlog blind = reads.newestTlog(null, 0.0);
            assertEquals(null, blind.trseq());
            assertEquals("1010008", blind.tlog().getAttribute("trseq"));
        }
    }

    @Test
    void packetsAreTheSimulatorsFolderAndAResetEmptiesIt() throws Exception {
        Path folder = Files.createDirectories(root.resolve("BUYPASS").resolve(StandardHandlers.PACKET_FOLDER));
        Files.writeString(folder.resolve("request1.xml"), "<a/>");
        Files.writeString(folder.resolve("response1.xml"), "<b/>");
        ValidationContext.PacketSource packets = StandardHandlers.packetFolders(root, null);

        assertEquals(2, packets.captured("BUYPASS").size());
        assertEquals(List.of(), packets.captured("PCATS01"), "a device with no folder has captured nothing");
        packets.reset("BUYPASS");
        assertEquals(List.of(), packets.captured("BUYPASS"));
        assertTrue(Files.isDirectory(folder), "the folder itself stays");
    }

    @Test
    void buypassOnFepsimV2IsUnavailableRatherThanReadFromTheWrongFolder() {
        BuypassV2 v2 = new BuypassV2() {
            @Override
            public String configText() {
                return "";
            }

            @Override
            public void applyConfig(String text) {
            }
        };
        ValidationContext.PacketSource packets = StandardHandlers.packetFolders(root, v2);
        assertThrows(DeviceUnavailable.class, () -> packets.captured("BUYPASS"));
        assertEquals(List.of(), packets.captured("PCATS01"));
    }

    @Test
    void withoutAPacketSourceThePacketActionsAreUnavailable() {
        HandlerFixtures fixtures = WiringFixtures.unbrowsed(root, pinpad());
        assertEquals(null, fixtures.packets(), "the fixtures before packets were added give none");
    }

    private static HandlerFixtures withCommander(HandlerFixtures f, PdkShim commander) {
        return new HandlerFixtures(f.pos(), f.posShim(), f.receipts(), f.pinpad(), commander, f.commanderAccess(),
                f.petroapps(), f.toolsRoot(), f.resultsDir(), f.environment(), f.inits(), f.dcr(), f.fuelPumps(),
                f.cloudSite(), f.buypassV2(), f.widgetText(), f.posUiTimings(), f.saleTiming(), f.devicesTiming(),
                f.saleRmi(), f.printer(), f.cashier(), f.packets());
    }

    private static PinpadDevice pinpad() {
        return (PinpadDevice) Proxy.newProxyInstance(PinpadDevice.class.getClassLoader(),
                new Class<?>[] {PinpadDevice.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
