package com.verifone.cat.validate.packets;

import java.nio.file.Path;
import java.util.List;

import com.verifone.cat.validate.Normaliser;
import com.verifone.cat.validate.ReceiptAssertions;
import com.verifone.cat.validate.SnapshotSession;
import com.verifone.cat.validate.ValidationFailedException;

/**
 * {@code assertPackets}: a captured packet sequence against its snapshot (DESIGN.md §7.2). The Python
 * CAT's {@code petrotest.validate.packets.assert_packets}.
 *
 * <p>The baseline is one JSON document holding the whole exchange, named with the CAT kind
 * {@link #PACKET_SUFFIX}. The default normalisers are {@link PacketNormalisers#TEXT}; overriding them is a
 * suite-level decision, never a per-test escape hatch.
 *
 * <p>Not ported: the Validations-view hook ({@code validation_checked}). The returned
 * {@link ReceiptAssertions.Verdict} carries the two texts for a caller that wants them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public final class PacketAssertions {

    /** The baseline's filename tail: a CAT kind, since PATS's packet references are per-message files. */
    public static final String PACKET_SUFFIX = "-CATPackets.json";

    private PacketAssertions() {}

    /** {@link #assertPackets(Object, SnapshotSession, String, List)} with the default text normalisers and no name. */
    public static ReceiptAssertions.Verdict assertPackets(Object actual, SnapshotSession snapshot) {
        return assertPackets(actual, snapshot, null, null);
    }

    /**
     * Asserts that a captured packet sequence matches its snapshot.
     *
     * @param actual      a {@link Packets.Source}, a list of {@link CapturedPacket}s, or a list of maps
     * @param name        the variant for a second sequence in one test, or {@code null}
     * @param normalisers the text normalisers; {@code null} means {@link PacketNormalisers#TEXT}
     * @throws IllegalArgumentException if {@code actual} carries no packets
     * @throws com.verifone.cat.validate.SnapshotMissingException if no baseline exists and not updating
     * @throws ValidationFailedException if the sequence differs from its baseline
     */
    public static ReceiptAssertions.Verdict assertPackets(Object actual, SnapshotSession snapshot, String name,
            List<Normaliser> normalisers) {
        List<?> packets = packetsOf(actual);
        List<Normaliser> norms = normalisers == null ? PacketNormalisers.TEXT : normalisers;
        String normalised = Packets.toJson(packets, norms);
        String fileName = snapshot.path(PACKET_SUFFIX, name).getFileName().toString();

        if (snapshot.update()) {
            snapshot.record(PACKET_SUFFIX, normalised, name);
            return new ReceiptAssertions.Verdict(fileName, normalised, normalised);
        }

        Path path = snapshot.path(PACKET_SUFFIX, name);
        String baseline = snapshot.read(PACKET_SUFFIX, name);
        if (baseline == null) {
            throw snapshot.missing(PACKET_SUFFIX, name);
        }
        snapshot.claim(PACKET_SUFFIX, name);

        Packets.PacketsDiff diff = Packets.compare(baseline, normalised);
        if (diff.ok()) {
            return new ReceiptAssertions.Verdict(fileName, baseline, normalised);
        }
        throw new ValidationFailedException("packet sequence does not match its snapshot (DESIGN.md 7.2).\n"
                + diff.describe() + "\n"
                + "Volatile fields (timestamps, STAN/trace/sequence/auth) are already masked by the packet "
                + "normalisers, so every difference above is a real difference. If the change is intentional, "
                + "re-baseline in update mode on this suite and review the diff in the pull request. Baseline: "
                + path);
    }

    private static List<?> packetsOf(Object actual) {
        if (actual instanceof Packets.Source source) {
            return source.packets();
        }
        if (actual instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("cannot read packets from "
                + (actual == null ? "null" : actual.getClass().getSimpleName())
                + ". Pass a packet source, a list of CapturedPacket, or a list of maps.");
    }
}
