package com.verifone.cat.validate.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.verifone.cat.validate.Normaliser;
import com.verifone.cat.validate.packets.Packets.PacketDifference;
import com.verifone.cat.validate.packets.Packets.PacketsDiff;

/**
 * Packet normalisation, JSON and structural comparison (DESIGN.md §7.2).
 *
 * <p>Ported from the Python CAT's {@code tests/unit/test_validate_packets.py}, minus the assertion tests
 * (see {@link PacketAssertionsTest}). Packets are maps with the same fields as {@link CapturedPacket}.
 */
class PacketsTest {

    /** A packet as a map, as the Python tests built one. */
    static Map<String, Object> packet(int index, Double ts, String direction, String peer, int length,
            String hex, String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("index", index);
        p.put("ts", ts);
        p.put("direction", direction);
        p.put("peer", peer);
        p.put("length", length);
        p.put("hex", hex);
        p.put("text", text);
        return p;
    }

    static Map<String, Object> packet(int index, String text) {
        return packet(index, 1234567890.5, "in", "example.invalid:443", 100, "deadbeef", text);
    }

    private static Map<String, Object> normalise(Map<String, Object> p) {
        return Packets.normalisePacket(p, PacketNormalisers.TEXT);
    }

    private static String json(List<Map<String, Object>> packets) {
        return Packets.toJson(packets, PacketNormalisers.TEXT);
    }

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    @Test
    void normaliseMasksTheTimestamp() {
        assertEquals("<TIMESTAMP>", normalise(packet(0, 1234567890.5, "in", "p", 1, "00", null)).get("ts"));
    }

    @Test
    void normaliseLeavesHexUntouched() {
        assertEquals("deadbeef", normalise(packet(0, "x")).get("hex"));
    }

    @Test
    void normaliseMasksStanInText() {
        String text = (String) normalise(packet(0, "Stan: 000412893 and other stuff")).get("text");
        assertTrue(text.contains("Stan: <STAN>"));
        assertFalse(text.contains("000412893"));
    }

    @Test
    void normaliseMasksTraceInText() {
        String text = (String) normalise(packet(0, "Trace #: 403 complete")).get("text");
        assertTrue(text.contains("Trace #: <TRACE>"));
        assertFalse(text.contains("403"));
    }

    @Test
    void normaliseMasksSequenceInText() {
        assertTrue(((String) normalise(packet(0, "Seq#: 020 done")).get("text")).contains("Seq #: <SEQ>"));
    }

    @Test
    void normaliseMasksAuthCodeInText() {
        String text = (String) normalise(packet(0, "Auth #: 999696 approved")).get("text");
        assertTrue(text.contains("Auth #: <AUTH>"));
        assertFalse(text.contains("999696"));
    }

    @Test
    void normaliseMasksDatetime() {
        String text = (String) normalise(packet(0, "Transaction at 8/14/25 5:28:27 PM")).get("text");
        assertFalse(text.contains("8/14/25 5:28:27 PM"));
        assertTrue(text.contains("<DATETIME>"));
    }

    @Test
    void normaliseMasksInvoice() {
        assertTrue(((String) normalise(packet(0, "Invoice #: 72541 recorded")).get("text"))
                .contains("Invoice #: <INVOICE>"));
    }

    @Test
    void normaliseLeavesEmptyAndNullTextAlone() {
        assertNull(normalise(packet(0, null)).get("text"));
        assertEquals("", normalise(packet(0, "")).get("text"));
    }

    @Test
    void multipleVolatileMasksInOnePacketAreAllNormalised() {
        String text = (String) normalise(packet(0,
                "Stan: 001 with Trace #: 42 and Auth #: ABC at 8/14/25 5:28:27 PM")).get("text");
        assertFalse(text.contains("001") || text.contains("42") || text.contains("ABC") || text.contains("8/14/25"),
                text);
        assertTrue(text.contains("<STAN>") && text.contains("<TRACE>") && text.contains("<AUTH>"), text);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1234567890.123456, Double.POSITIVE_INFINITY})
    void timestampIsAlwaysMaskedRegardlessOfValue(double ts) {
        assertEquals("<TIMESTAMP>", normalise(packet(0, ts, "in", "p", 1, "00", null)).get("ts"));
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    @Test
    void singlePacketSerialisesWithItsMasks() {
        String text = json(List.of(packet(0, "Stan: 001015724")));
        assertTrue(text.contains("\"<TIMESTAMP>\""));
        assertTrue(text.contains("\"Stan: <STAN>\""));
        assertTrue(text.startsWith("[\n  {\n    \"index\": 0,"), text);
    }

    @Test
    void multiplePacketsKeepTheirOrder() {
        List<Map<String, Object>> packets = List.of(packet(0, "first"), packet(1, "second"), packet(2, "third"));
        String text = json(packets);
        int first = text.indexOf("\"index\": 0");
        int second = text.indexOf("\"index\": 1");
        int third = text.indexOf("\"index\": 2");
        assertTrue(first >= 0 && first < second && second < third, text);
    }

    @Test
    void allVolatilesInAllPacketsAreNormalised() {
        String text = json(List.of(packet(0, "Stan: 001"), packet(1, "Trace #: 42"), packet(2, "Auth #: ABC123")));
        assertFalse(text.contains("001") || text.contains("42") || text.contains("ABC123"), text);
        assertTrue(text.contains("<STAN>") && text.contains("<TRACE>") && text.contains("<AUTH>"), text);
    }

    @Test
    void allFieldsArePreservedInJson() {
        String text = json(List.of(packet(5, 1.0, "out", "example.invalid:1234", 256, "deadbeef", null)));
        assertTrue(text.contains("\"index\": 5"));
        assertTrue(text.contains("\"direction\": \"out\""));
        assertTrue(text.contains("\"peer\": \"example.invalid:1234\""));
        assertTrue(text.contains("\"length\": 256"));
        assertTrue(text.contains("\"hex\": \"deadbeef\""));
        assertTrue(text.contains("\"text\": null"));
    }

    @Test
    void emptyListSerialisesAsPythonWould() {
        assertEquals("[]", Packets.toJson(List.of(), PacketNormalisers.TEXT));
    }

    @Test
    void aNonPacketElementIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Packets.toJson(List.of("not a packet"), PacketNormalisers.TEXT));
        assertTrue(e.getMessage().contains("packet must be"), e.getMessage());
    }

    @Test
    void theNormalisersCanBeOverriddenPerCall() {
        List<Normaliser> none = List.of();
        String text = Packets.toJson(List.of(packet(0, "Stan: 001")), none);
        assertTrue(text.contains("Stan: 001"), text);
    }

    // ------------------------------------------------------------------
    // Comparison
    // ------------------------------------------------------------------

    @Test
    void identicalPacketsProduceNoDifferences() {
        String expected = json(List.of(packet(0, "same")));
        PacketsDiff diff = Packets.compare(expected, json(List.of(packet(0, "same"))));
        assertTrue(diff.ok());
        assertTrue(diff.differences().isEmpty());
    }

    @Test
    void differentTextProducesOneDifferenceNamingItsPacketAndField() {
        PacketsDiff diff = Packets.compare(json(List.of(packet(0, "expected"))), json(List.of(packet(0, "actual"))));
        assertFalse(diff.ok());
        assertEquals(1, diff.differences().size());
        PacketDifference d = diff.differences().get(0);
        assertEquals(0, d.index());
        assertEquals("text", d.field());
        assertEquals("expected", d.expected());
        assertEquals("actual", d.actual());
    }

    @Test
    void differentDirectionIsReported() {
        PacketsDiff diff = Packets.compare(json(List.of(packet(0, "x"))),
                json(List.of(Map.of("index", 0, "direction", "out"))));
        assertTrue(diff.differences().stream().anyMatch(d -> d.field().equals("direction")));
    }

    @Test
    void aMissingPacketIsReported() {
        String expected = json(List.of(packet(0, "first"), packet(1, "second")));
        PacketsDiff diff = Packets.compare(expected, json(List.of(packet(0, "first"))));
        assertFalse(diff.ok());
        assertTrue(diff.differences().stream().anyMatch(d -> d.index() == 1));
    }

    @Test
    void anExtraPacketIsReported() {
        String expected = json(List.of(packet(0, "first")));
        PacketsDiff diff = Packets.compare(expected, json(List.of(packet(0, "first"), packet(1, "second"))));
        assertFalse(diff.ok());
        assertTrue(diff.differences().stream().anyMatch(d -> d.index() == 1));
    }

    @Test
    void multipleDifferencesAreAllReported() {
        PacketsDiff diff = Packets.compare(json(List.of(packet(0, "first"), packet(1, "second"))),
                json(List.of(packet(0, "changed"), packet(1, "also changed"))));
        assertEquals(2, diff.differences().stream().filter(d -> d.field().equals("text")).count());
    }

    @Test
    void differenceStringsDescribeChangedMissingAndUnexpectedFields() {
        assertEquals("packet[5].text: expected 'foo', got 'bar'",
                new PacketDifference(5, "text", "foo", "bar").toString());
        assertTrue(new PacketDifference(3, "hex", "deadbeef", null).toString().contains("missing"));
        assertTrue(new PacketDifference(2, "peer", null, "example.invalid:443").toString().contains("unexpected"));
    }

    @Test
    void packetsDiffDescribesAMatchAndAMismatch() {
        assertTrue(new PacketsDiff(List.of(), 3, 18).describe().toLowerCase().contains("match"));
        assertTrue(new PacketsDiff(List.of(), 3, 18).describe().contains("3 packets"));
        PacketsDiff bad = new PacketsDiff(List.of(new PacketDifference(0, "text", "a", "b"),
                new PacketDifference(1, "hex", "cc", "dd")), 2, 12);
        assertTrue(bad.describe().contains("2 packet difference(s)"), bad.describe());
        assertTrue(bad.describe().contains("2 packets"), bad.describe());
    }

    @Test
    void emptyListsCompareEqual() {
        assertTrue(Packets.compare("[]", "[]").ok());
    }

    @Test
    void aFieldOnlyOnOneSideIsReportedWithItsName() {
        PacketsDiff diff = Packets.compare("[{\"index\": 0, \"text\": \"a\"}]",
                "[{\"index\": 0, \"text\": \"a\", \"extra\": \"field\"}]");
        assertFalse(diff.ok());
        List<PacketDifference> extra = new ArrayList<>();
        for (PacketDifference d : diff.differences()) {
            if (d.field().equals("extra")) {
                extra.add(d);
            }
        }
        assertEquals(1, extra.size());
        assertEquals("field", extra.get(0).actual());
    }

    @Test
    void invalidJsonIsRefusedNamingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Packets.compare("not json", "{\"valid\": \"json\"}"));
        assertTrue(e.getMessage().contains("invalid packet JSON"), e.getMessage());
    }

    @Test
    void numbersCompareNumerically() {
        assertTrue(Packets.compare("[{\"index\": 1}]", "[{\"index\": 1.0}]").ok());
    }
}
