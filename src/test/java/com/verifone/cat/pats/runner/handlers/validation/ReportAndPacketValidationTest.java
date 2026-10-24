package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.context;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredNothing;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.receiptSource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.run;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.xml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code VALIDATE_REPORT}, {@code RESET_PACKETS} and {@code VALIDATE_PACKETS}, driven through the runner with
 * fakes. Ported from the Python {@code tests/unit/test_pats_runner.py} report and packet cases.
 */
class ReportAndPacketValidationTest {

    private static final String RULE = "BUYPASS_PACKET-RULE.txt";

    @TempDir
    Path folder;

    /** A batch report's entries as {@code stan, amount, stan, amount, ...}: each batch's own number and total. */
    private static String batch(String... entries) {
        StringBuilder batches = new StringBuilder();
        for (int i = 0; i < entries.length; i += 2) {
            batches.append("<batch><batchProp batchNum=\"").append(entries[i]).append("\"/><cardEntry epsSTAN=\"")
                    .append(entries[i]).append("\"><amount>").append(entries[i + 1]).append("</amount></cardEntry></batch>");
        }
        return "<reportData><batchTotals>" + batches + "</batchTotals></reportData>";
    }

    private CaseRun reportCase(ValidationKit.FakeCommander commander, ValidationKit.FakeReferences refs,
            boolean update, Map<String, String> env, Path baseline) {
        ValidationContext context = context(receiptSource(""), new ValidationKit.FakePos(), commander,
                new ValidationKit.FakePackets(), refs.sharedFile("AutomationTests/rulefiles/reports",
                        "REPORT-RULE.txt", "ATTRIBUTE_NAMES=batchNum;\n"), env);
        return run(kase("PETROCORE-1", action(0, "VALIDATE_REPORT", "SELECT_FILE", "PETROCORE-1-Report.xml",
                "CMD", "vviperrept", "REPORTNAME", "batch", "REPORTTYPE", "cashier", "CASHIERNUM", "3")),
                context, baseline, update, declaredNothing(), folder);
    }

    @Test
    void aReportMatchesItsReferenceAndAMismatchFails() {
        String reference = "<reportData><total>20.00</total></reportData>";
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Report", "PETROCORE-1-Report.xml", reference);
        ValidationKit.FakeCommander commander = new ValidationKit.FakeCommander();
        commander.reports.put("vviperrept/batch", xml("<root>" + reference + "</root>"));

        assertEquals("", verdict(reportCase(commander, refs, false, noEnv(), folder)));

        commander.reports.put("vviperrept/batch", xml("<root><reportData><total>20.01</total></reportData></root>"));
        String message = verdict(reportCase(commander, refs, false, noEnv(), folder));
        assertTrue(message.contains("report differs from PETROCORE-1-Report.xml"), message);
    }

    @Test
    void theReportCommandIsCalledWithPatsDefaultsAndTheCashierOnlyForACashierReport() {
        String reference = "<reportData/>";
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Report", "PETROCORE-1-Report.xml", reference);
        ValidationKit.FakeCommander commander = new ValidationKit.FakeCommander();
        commander.reports.put("vviperrept/batch", xml(reference));

        verdict(reportCase(commander, refs, false, noEnv(), folder));
        assertEquals(List.of("vviperrept/batch period=1 file=current cashier=3 register="), commander.reportCalls);
    }

    @Test
    void aBatchReportMatchesItsEntriesInAnyOrderWhateverTheirStanAndTheirAmountsStillCount() {
        String reference = batch("9742", "20.00", "9486", "24.00");
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Report", "PETROCORE-1-Report.xml", reference);
        ValidationKit.FakeCommander commander = new ValidationKit.FakeCommander();

        commander.reports.put("vviperrept/batch", xml("<root>" + batch("76825", "24.00", "76970", "20.00") + "</root>"));
        assertEquals("", verdict(reportCase(commander, refs, false, noEnv(), folder)));

        commander.reports.put("vviperrept/batch", xml("<root>" + batch("76825", "24.00", "76970", "20.01") + "</root>"));
        String message = verdict(reportCase(commander, refs, false, noEnv(), folder));
        assertTrue(message.contains("20.01"), message);
    }

    @Test
    void aReportRebaseWritesCatsBaselineAndIsThenJudgedAgainstIt() throws IOException {
        String reference = "<reportData><total>20.00</total></reportData>";
        String built = "<reportData><total>22.81</total></reportData>";
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Report", "PETROCORE-1-Report.xml", reference);
        ValidationKit.FakeCommander commander = new ValidationKit.FakeCommander();
        Path baseline = folder.resolve("PETROCORE-1-CATReport.xml");

        commander.reports.put("vviperrept/batch", xml(reference));
        assertEquals("", verdict(reportCase(commander, refs, false, Map.of("CAT_REPORT_REBASE", "1"), folder)));
        assertFalse(Files.exists(baseline), "matches PATS's: no CAT baseline");

        commander.reports.put("vviperrept/batch", xml(built));
        assertEquals("", verdict(reportCase(commander, refs, false, Map.of("CAT_REPORT_REBASE", "1"), folder)));
        assertTrue(Files.isRegularFile(baseline));

        assertEquals("", verdict(reportCase(commander, refs, false, noEnv(), folder)),
                "the built report is judged against CAT's baseline now");
        commander.reports.put("vviperrept/batch", xml(reference));
        String message = verdict(reportCase(commander, refs, false, noEnv(), folder));
        assertTrue(message.contains("report differs from PETROCORE-1-CATReport.xml"), message);
    }

    @Test
    void aReportWithNoMatchingElementIsAFailedValidation() {
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Report", "PETROCORE-1-Report.xml", "<reportData/>");
        ValidationKit.FakeCommander commander = new ValidationKit.FakeCommander();
        commander.reports.put("vviperrept/batch", xml("<other/>"));

        String message = verdict(reportCase(commander, refs, false, noEnv(), folder));
        assertTrue(message.contains("the Commander's reply holds no report element"), message);
    }

    private CaseRun packetCase(ValidationKit.FakePackets packets, ValidationKit.FakeReferences refs,
            Map<String, String> env, Path baseline, String device, String names, String rule) {
        ValidationContext context = context(receiptSource(""), new ValidationKit.FakePos(),
                new ValidationKit.FakeCommander(), packets, refs, env);
        return run(kase("PETROCORE-9", action(0, "VALIDATE_PACKETS", "DEVICE", device,
                "SELECT_REFERENCE_FILE", names, "SELECT_RULE_FILE", rule)), context, baseline, false,
                declaredNothing(), folder);
    }

    private Path capture(ValidationKit.FakePackets packets, String device) throws IOException {
        Path dir = folder.resolve("captured-" + device);
        Files.createDirectories(dir);
        packets.folders.put(device, dir);
        return dir;
    }

    @Test
    void resetEmptiesOnlyTheDevicesPatsReadsPacketsFrom() throws IOException {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        Path dir = capture(packets, "BUYPASS");
        Files.writeString(dir.resolve("stale.xml"), "<x/>");
        ValidationContext context = context(receiptSource(""), new ValidationKit.FakePos(),
                new ValidationKit.FakeCommander(), packets, new ValidationKit.FakeReferences(), noEnv());

        CaseRun run = run(kase("PETROCORE-9", action(0, "RESET_PACKETS", "DEVICE", "BUYPASS"),
                action(1, "RESET_PACKETS", "DEVICE", "POS")), context, folder, false, declaredNothing(), folder);
        assertEquals("", verdict(run));
        assertEquals(List.of("BUYPASS"), packets.resets);
        assertFalse(Files.exists(dir.resolve("stale.xml")));
    }

    @Test
    void packetsAreComparedWhereTheSimulatorWritesThemAndAChangedAmountFails() throws IOException {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        Path dir = capture(packets, "BUYPASS");
        String packet = "<SaleCreditRequest><Amount>10.00</Amount><STAN>1</STAN></SaleCreditRequest>";
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Packets/BUYPASS", "PETROCORE-9-SaleCreditRequest.xml", packet);

        Files.writeString(dir.resolve("SaleCreditRequest.xml"), packet.replace("10.00", "11.00"));
        String message = verdict(packetCase(packets, refs, noEnv(), folder, "BUYPASS",
                "PETROCORE-9-SaleCreditRequest.xml", RULE));
        assertTrue(message.contains("packets differ"), message);
        assertTrue(message.contains("Amount"), message);

        Files.writeString(dir.resolve("SaleCreditRequest.xml"), packet);
        assertEquals("", verdict(packetCase(packets, refs, noEnv(), folder, "BUYPASS",
                "PETROCORE-9-SaleCreditRequest.xml", RULE)));
    }

    @Test
    void aPacketTimeIsComparedWithoutItsDaylightSavingOffset() throws IOException {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        Path dir = capture(packets, "PCATS01");
        String packet = "<GetRewardsRequest><EndTime>00:00:00-05:00</EndTime></GetRewardsRequest>";
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Packets/PCATS01", "PETROCORE-9-GetRewardsRequest.xml", packet);

        Files.writeString(dir.resolve("GetRewardsRequest.xml"), packet.replace("-05:00", "-04:00"));
        assertEquals("", verdict(packetCase(packets, refs, noEnv(), folder, "PCATS01",
                "PETROCORE-9-GetRewardsRequest.xml", "PCATS_PACKET-RULE.txt")));

        Files.writeString(dir.resolve("GetRewardsRequest.xml"), packet.replace("00:00:00", "01:00:00"));
        String message = verdict(packetCase(packets, refs, noEnv(), folder, "PCATS01",
                "PETROCORE-9-GetRewardsRequest.xml", "PCATS_PACKET-RULE.txt"));
        assertTrue(message.contains("EndTime"), message);
    }

    @Test
    void theLastPacketDecidesAsInPatsAndAnEarlierDifferenceDoesNotFailTheCase() throws IOException {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        Path dir = capture(packets, "BUYPASS");
        String request = "<R><TPPID>RVE103</TPPID></R>";
        String response = "<S><Code>000</Code></S>";
        Files.writeString(dir.resolve("SaleCreditRequest.xml"), request.replace("103", "106"));
        Files.writeString(dir.resolve("SaleCreditResponse.xml"), response);
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Packets/BUYPASS", "PETROCORE-9-SaleCreditRequest.xml", request)
                .reference("Packets/BUYPASS", "PETROCORE-9-SaleCreditResponse.xml", response);

        assertEquals("", verdict(packetCase(packets, refs, noEnv(), folder, "BUYPASS",
                "PETROCORE-9-SaleCreditRequest.xml, PETROCORE-9-SaleCreditResponse.xml", RULE)));
    }

    @Test
    void aListedPacketNeverCapturedIsNamedInTheFailure() throws IOException {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        Path dir = capture(packets, "BUYPASS");
        String request = "<R><TPPID>RVE103</TPPID></R>";
        String response = "<S><Code>000</Code></S>";
        Files.writeString(dir.resolve("SaleReversalRequest.xml"), request.replace("103", "106"));
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Packets/BUYPASS", "PETROCORE-9-SaleReversalRequest.xml", request)
                .reference("Packets/BUYPASS", "PETROCORE-9-SaleReversalResponse.xml", response);
        String names = "PETROCORE-9-SaleReversalRequest.xml, PETROCORE-9-SaleReversalResponse.xml";

        String message = verdict(packetCase(packets, refs, noEnv(), folder, "BUYPASS", names, RULE));
        assertTrue(message.contains("never captured: PETROCORE-9-SaleReversalResponse.xml"), message);

        Files.writeString(dir.resolve("SaleReversalResponse.xml"), response);
        assertEquals("", verdict(packetCase(packets, refs, noEnv(), folder, "BUYPASS", names, RULE)));
    }

    @Test
    void aCatPacketBaselineOverridesPatsReferenceAndUpdateWritesIt() throws IOException {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        Path dir = capture(packets, "PCATS01");
        String name = "PETROCORE-9-GetRewardsResponse.xml";
        String reference = "<G><Line>Discount From: PCATS01</Line></G>";
        String live = "<G><Line>https://uqr.to/20gc7</Line></G>";
        Files.writeString(dir.resolve("GetRewardsResponse.xml"), live);
        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Packets/PCATS01", name, reference);
        Path baseline = folder.resolve("PETROCORE-9-GetRewardsResponse-PCATS01-CATPacket.xml");

        String message = verdict(packetCase(packets, refs, noEnv(), folder, "PCATS01", name, "PCATS_PACKET-RULE.txt"));
        assertTrue(message.contains("Discount From"), message);

        assertEquals("", verdict(packetCase(packets, refs, Map.of("CAT_PACKET_REBASE", "1"), folder, "PCATS01", name,
                "PCATS_PACKET-RULE.txt")));
        assertEquals(live, Files.readString(baseline));

        message = verdict(packetCase(packets, refs, noEnv(), folder, "PCATS01", name, "PCATS_PACKET-RULE.txt"));
        assertEquals("", message, "judged against CAT's baseline now");

        Files.writeString(baseline, reference.replace("PCATS01", "PCATS02"));
        message = verdict(packetCase(packets, refs, noEnv(), folder, "PCATS01", name, "PCATS_PACKET-RULE.txt"));
        assertTrue(message.contains(baseline.getFileName().toString()), message);
    }

    @Test
    void aDeviceWithNoPacketsIsAFailedValidation() {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        String message = verdict(packetCase(packets, new ValidationKit.FakeReferences(), noEnv(), folder, "POS",
                "PETROCORE-9-X.xml", RULE));
        assertTrue(message.contains("PATS does not support packet validation for 'POS'"), message);
    }

    @Test
    void aCaseKeyWithNoNumberHasNoPacketNameAndIsAHardStep() {
        ValidationKit.FakePackets packets = new ValidationKit.FakePackets();
        ValidationContext context = context(receiptSource(""), new ValidationKit.FakePos(),
                new ValidationKit.FakeCommander(), packets, new ValidationKit.FakeReferences(), noEnv());
        RunnableCase kase = kase("PETROCORE", action(0, "VALIDATE_PACKETS", "DEVICE", "BUYPASS",
                "SELECT_REFERENCE_FILE", "X.xml", "SELECT_RULE_FILE", RULE));
        CaseRun run = run(kase, context, folder, false, declaredNothing(), folder);

        assertThrows(PatsStepError.class, run::run);
    }

    @Test
    void thePacketPrefixIsTheCaseKeyUpToItsFirstNumber() {
        assertEquals("PETROCORE-9", ValidationSupport.packetPrefix("PETROCORE-9"));
        assertEquals("PETROCORE-36848", ValidationSupport.packetPrefix("PETROCORE-36848-extra"));
        assertThrows(PatsStepError.class, () -> ValidationSupport.packetPrefix("PETROCORE"));
    }
}
