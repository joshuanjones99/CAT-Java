package com.verifone.cat.validate.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.validate.Normaliser;
import com.verifone.cat.validate.SnapshotMissingException;
import com.verifone.cat.validate.SnapshotSession;
import com.verifone.cat.validate.ValidationFailedException;

/**
 * {@code assertPackets}: update mode, the baseline and a pass or a failure (DESIGN.md §7.2).
 *
 * <p>Ported from the Python CAT's {@code tests/unit/test_validate_packets.py}, assertion half. Every file
 * is written under {@code @TempDir}.
 */
class PacketAssertionsTest {

    private static final String CASE = "PETROCORE-1-TestCase.py";

    private static SnapshotSession session(Path dir, String test, boolean update) {
        return SnapshotSession.forNodeId(dir.resolve(CASE), CASE + "::" + test, update, new HashSet<>());
    }

    @Test
    void aSequenceMatchesItsSnapshot(@TempDir Path dir) {
        List<Map<String, Object>> packets = List.of(PacketsTest.packet(0, "request"),
                PacketsTest.packet(1, "response"));
        PacketAssertions.assertPackets(packets, session(dir, "test_matches", true), "test", null);
        PacketAssertions.assertPackets(packets, session(dir, "test_matches", false), "test", null);
    }

    @Test
    void volatileValuesInTheTextAreNormalisedBeforeCompare(@TempDir Path dir) {
        PacketAssertions.assertPackets(List.of(PacketsTest.packet(0, "Stan: 000000001")),
                session(dir, "test_normalise", true), "baseline", null);
        PacketAssertions.assertPackets(List.of(PacketsTest.packet(0, "Stan: 000000002")),
                session(dir, "test_normalise", false), "baseline", null);
    }

    @Test
    void aRealChangeFailsTheComparison(@TempDir Path dir) {
        PacketAssertions.assertPackets(List.of(PacketsTest.packet(0, "request")),
                session(dir, "test_detect", true), "test", null);
        ValidationFailedException e = assertThrows(ValidationFailedException.class, () -> PacketAssertions.assertPackets(
                List.of(PacketsTest.packet(0, 1234567890.5, "out", "example.invalid:443", 100, "deadbeef", "request")),
                session(dir, "test_detect", false), "test", null));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
        assertTrue(e.getMessage().contains("packet sequence"), e.getMessage());
    }

    @Test
    void aMissingBaselineFailsAndSaysHowToCreateIt(@TempDir Path dir) {
        SnapshotMissingException e = assertThrows(SnapshotMissingException.class, () -> PacketAssertions.assertPackets(
                List.of(PacketsTest.packet(0, null)), session(dir, "no_baseline", false), null, null));
        assertTrue(e.getMessage().contains("no snapshot baseline"), e.getMessage());
    }

    @Test
    void aValueThatCarriesNoPacketsIsRefused(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PacketAssertions.assertPackets("not a packet list", session(dir, "test", false), null, null));
        assertTrue(e.getMessage().contains("cannot read packets"), e.getMessage());
    }

    @Test
    void aPacketSourceIsReadThroughItsPacketsMethod(@TempDir Path dir) {
        Packets.Source source = () -> List.of(PacketsTest.packet(0, "SALE 10.00"));
        PacketAssertions.assertPackets(source, session(dir, "test_source", true));
        PacketAssertions.assertPackets(source, session(dir, "test_source", false));
    }

    @Test
    void customNormalisersReplaceTheDefaults(@TempDir Path dir) {
        PacketAssertions.assertPackets(List.of(PacketsTest.packet(0, "Stan: 001")),
                session(dir, "test_custom", true), "baseline", null);
        // With no normalisers the STAN is no longer masked, so the second run differs.
        List<Normaliser> none = List.of();
        assertThrows(ValidationFailedException.class, () -> PacketAssertions.assertPackets(
                List.of(PacketsTest.packet(0, "Stan: 002")), session(dir, "test_custom", false), "baseline", none));
    }

    @Test
    void updateModeRecordsTheSnapshotWithoutComparing(@TempDir Path dir) {
        SnapshotSession update = session(dir, "test_update", true);
        PacketAssertions.assertPackets(List.of(PacketsTest.packet(0, "request")), update, null, null);
        assertEquals(1, update.written().size());
    }
}
