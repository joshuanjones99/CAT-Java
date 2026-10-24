package com.verifone.cat.devices.commander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.commander.CgiReplies.CgiFunction;
import com.verifone.cat.devices.commander.CgiReplies.FepDetail;

/**
 * The pure parsers: ports of the Python CAT's {@code TestTlogSelection}
 * ({@code test_config_shim_with_mocks.py}), {@code TestExtractUpgradeToken} and
 * {@code TestIsUpgradeComplete} ({@code test_config_shim_upgrade.py}) and the FEP-detail tests
 * of {@code test_secondary_fep.py}. Fixtures only: evidence about this repository.
 */
class CgiRepliesTest {

    private static Element xml(String text) {
        return CgiReplies.parse(text, "test");
    }

    // ---- select_transaction_by_trseq

    @Test
    void selectsTheTransactionWhoseTrseqMatches() {
        Element root = xml("<report>\n<trans type=\"sale\">\n<trHeader>\n<trTickNum>\n<posNum>1</posNum>\n"
                + "<trSeq>123</trSeq>\n</trTickNum>\n</trHeader>\n</trans>\n</report>");
        Element trans = CgiReplies.selectTransactionByTrseq(root, "123");
        assertEquals("trans", CgiReplies.localName(trans));
        Element header = CgiReplies.child(trans, "trHeader");
        Element tick = CgiReplies.child(header, "trTickNum");
        assertEquals("123", CgiReplies.text(CgiReplies.child(tick, "trSeq")));
    }

    @Test
    void whitespaceAroundEitherNumberIsIgnored() {
        Element root = xml("<report><trans><trHeader><trTickNum><trSeq>  456  </trSeq></trTickNum></trHeader>"
                + "</trans></report>");
        assertEquals("trans", CgiReplies.localName(CgiReplies.selectTransactionByTrseq(root, "  456  ")));
    }

    @Test
    void aNumberNotInTheReportRaisesNamingIt() {
        Element root = xml("<report><trans><trHeader><trTickNum><trSeq>123</trSeq></trTickNum></trHeader>"
                + "</trans></report>");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgiReplies.selectTransactionByTrseq(root, "999"));
        assertTrue(e.getMessage().contains("999"));
        assertTrue(e.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void aTreeTooShallowRaises() {
        Element root = xml("<report>\n<trSeq>123</trSeq>\n</report>");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgiReplies.selectTransactionByTrseq(root, "123"));
        assertTrue(e.getMessage().toLowerCase().contains("tree ends"));
    }

    @Test
    void theWalkDoesNotLeaveTheReportItWasGiven() {
        // ElementTree's parent map covers only the report; a DOM node knows its whole document.
        Element outer = xml("<a><b><report><trSeq>5</trSeq></report></b></a>");
        Element report = CgiReplies.child(CgiReplies.child(outer, "b"), "report");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgiReplies.selectTransactionByTrseq(report, "5"));
        assertTrue(e.getMessage().contains("after 1 ancestor level"));
    }

    @Test
    void anAncestorThatIsNotTransRaises() {
        Element root = xml("<report><other><a><b><trSeq>7</trSeq></b></a></other></report>");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgiReplies.selectTransactionByTrseq(root, "7"));
        assertTrue(e.getMessage().contains("Reached element <other>"));
    }

    @Test
    void theMiddleOfThreeTransactionsIsTheOneReturned() {
        StringBuilder text = new StringBuilder("<report>");
        for (String n : List.of("100", "200", "300")) {
            text.append("<trans type=\"sale\"><trHeader><trTickNum><trSeq>").append(n)
                    .append("</trSeq></trTickNum></trHeader></trans>");
        }
        Element root = xml(text.append("</report>").toString());
        Element trans = CgiReplies.selectTransactionByTrseq(root, "200");
        assertNotSame(root, trans);
        long trSeqs = CgiReplies.all(trans).stream().filter(e -> CgiReplies.localName(e).endsWith("trSeq")).count();
        assertEquals(1, trSeqs);
        assertEquals("200", CgiReplies.text(CgiReplies.descendant(trans, "trSeq")));
    }

    @Test
    void namespacedTagsAreMatchedByLocalName() {
        Element root = xml("<r:report xmlns:r=\"urn:x\"><r:trans><r:trHeader><r:trTickNum><r:trSeq>9</r:trSeq>"
                + "</r:trTickNum></r:trHeader></r:trans></r:report>");
        assertEquals("trans", CgiReplies.localName(CgiReplies.selectTransactionByTrseq(root, "9")));
    }

    // ---- extract_upgrade_token / is_upgrade_complete

    @ParameterizedTest
    @ValueSource(strings = {"token", "upgradeToken", "sessionToken", "upgradeSessionToken"})
    void eachCandidateTokenTagIsFound(String tag) {
        assertEquals("abc123", CgiReplies.upgradeToken(xml("<summary><" + tag + ">abc123</" + tag + "></summary>")));
    }

    @Test
    void noCandidateTokenIsAProtocolError() {
        DeviceProtocolError e = assertThrows(DeviceProtocolError.class,
                () -> CgiReplies.upgradeToken(xml("<summary><somethingElse>x</somethingElse></summary>")));
        assertTrue(e.getMessage().contains("no recognized upgrade-token element"));
    }

    @Test
    void theSentinelInElementTextIsComplete() {
        assertTrue(CgiReplies.isUpgradeComplete(
                xml("<summary><statusId>UPGRADE_COMPLETED_SUCCESSFULLY</statusId></summary>")));
    }

    @Test
    void theSentinelInAnAttributeIsComplete() {
        assertTrue(CgiReplies.isUpgradeComplete(xml("<summary status=\"UPGRADE_COMPLETED_SUCCESSFULLY\"/>")));
    }

    @Test
    void noSentinelIsNotComplete() {
        assertFalse(CgiReplies.isUpgradeComplete(xml("<summary><statusId>UPGRADE_IN_PROGRESS</statusId></summary>")));
    }

    // ---- parse_fep_details

    @Test
    void fepDetailsAreReadByName() {
        Map<String, FepDetail> details = CgiReplies.fepDetails(FakeCommander.fixture("fepdetails.xml"));
        assertTrue(details.get("buypass").enabled() && details.get("buypass").primary());
        assertEquals("Secondary", details.get("PropFleet").fepType());
        assertFalse(details.get("PropFleet").enabled());
        assertEquals(List.of("buypass", "PropFleet"), List.copyOf(details.keySet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not xml", "<viper:x xmlns:viper='u'/>"})
    void fepDetailsThatAreNotFepDetailsRaise(String text) {
        assertThrows(DeviceProtocolError.class, () -> CgiReplies.fepDetails(text));
    }

    /** PATS's own reference reply, when a petroapps checkout is on this machine (as the Python skips without one). */
    @Test
    void patsOwnFepDetailsReferenceParses() throws Exception {
        String root = System.getenv().getOrDefault("PETROAPPS_ROOT", "C:\\gitrepos\\petroapps");
        Path reference = Path.of(root, "AutomationTests", "TestCases", "PETROCORE-37412",
                "PETROCORE-37412-fepdetails-View-Config-Reference.xml");
        assumeTrue(Files.isRegularFile(reference), "no petroapps checkout");
        Map<String, FepDetail> details = CgiReplies.fepDetails(Files.readString(reference, StandardCharsets.UTF_8));
        assertTrue(details.get("buypass").primary());
        assertEquals("Prepaid", details.get("incomm").fepType());
        assertEquals("Service", details.get("Cullinan").fepType());
    }

    // ---- parse_function_list

    @Test
    void theFuncListIsReadInOrderNamespacedOrBare() {
        List<CgiFunction> functions = CgiReplies.functionList(xml(FakeCommander.fixture("validate-funclist.xml")));
        assertEquals(List.of(
                new CgiFunction("vfuelcfg", "View fuel configuration", "View fuel configuration", "cgi", false),
                new CgiFunction("vpairingotp", "Retrieve current pairing OTP", "Retrieve current pairing OTP", "cgi", true),
                new CgiFunction("ucloudconnectprofile", "", "", "naxml", true)), functions);
    }

    // ---- faults and parsing

    @Test
    void aFaultAnywhereRaisesWithItsCode() {
        CommanderFault fault = assertThrows(CommanderFault.class,
                () -> CgiReplies.checkFault(xml(FakeCommander.fault("CGIPortal.OTPRequired"))));
        assertEquals("CGIPortal.OTPRequired", fault.code());
        assertEquals("CGILink fault: CGIPortal.OTPRequired", fault.getMessage());
    }

    @Test
    void aFaultWithNoCodeSaysSo() {
        CommanderFault fault = assertThrows(CommanderFault.class, () -> CgiReplies.checkFault(xml("<r><Fault/></r>")));
        assertEquals("(no faultCode)", fault.code());
    }

    @Test
    void aReplyWithNoFaultPasses() {
        CgiReplies.checkFault(xml(FakeCommander.NS_OK));
    }

    @Test
    void anUndeclaredPrefixIsStillReadAndItsFaultSeen() {
        Element root = xml(FakeCommander.bareFault("CGIPortal.LoginRequired"));
        assertEquals("Fault", CgiReplies.localName(root));
        assertEquals("CGIPortal.LoginRequired",
                assertThrows(CommanderFault.class, () -> CgiReplies.checkFault(root)).code());
    }

    @Test
    void textThatIsNotXmlIsAProtocolErrorQuotingIt() {
        DeviceProtocolError e = assertThrows(DeviceProtocolError.class, () -> CgiReplies.parse("oops", "POST x"));
        assertTrue(e.getMessage().startsWith("POST x: reply is not XML: 'oops'"), e.getMessage());
    }

    @Test
    void aDoctypeIsRefused() {
        assertThrows(DeviceProtocolError.class, () -> CgiReplies.parse(
                "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///c:/windows/win.ini\">]><r>&x;</r>", "test"));
    }

    @Test
    void textIsElementTreesTextNotTheSubtrees() {
        Element root = xml("<a>head<!-- c -->more<b>inner</b>tail</a>");
        assertEquals("headmore", CgiReplies.text(root));
    }
}
