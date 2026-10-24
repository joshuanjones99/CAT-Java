package com.verifone.cat.validate.packets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.verifone.cat.validate.Normaliser;
import com.verifone.cat.validate.PyRepr;

/**
 * Packet normalisation and structural comparison (DESIGN.md §7.2; the Python CAT's
 * {@code petrotest.validate.packets}, below the assertion).
 *
 * <p>A captured sequence is normalised, written as JSON, and compared with its baseline JSON packet
 * by packet and field by field, so a difference names a packet index and a field. The timestamp is
 * always masked. The text field goes through the normalisers. The hex payload is never normalised:
 * a volatile value on the wire is the test's to avoid capturing.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public final class Packets {

    /** The masked timestamp. */
    public static final String TIMESTAMP = "<TIMESTAMP>";

    private Packets() {}

    /** Anything that carries a packet list, as the Python CAT's {@code HostPacketsResponse} did. */
    public interface Source {
        List<?> packets();
    }

    /** One difference, anchored to a packet index and a field name. {@code null} means absent on that side. */
    public record PacketDifference(int index, String field, String expected, String actual) {

        @Override
        public String toString() {
            if (expected != null && actual != null) {
                return "packet[" + index + "]." + field + ": expected " + PyRepr.repr(expected) + ", got "
                        + PyRepr.repr(actual);
            }
            if (expected != null) {
                return "packet[" + index + "]." + field + ": missing (baseline has " + PyRepr.repr(expected) + ")";
            }
            return "packet[" + index + "]." + field + ": unexpected (this run has " + PyRepr.repr(actual) + ")";
        }
    }

    /** The result of comparing two packet sequences. */
    public record PacketsDiff(List<PacketDifference> differences, int comparedPackets, int comparedFields) {

        public PacketsDiff {
            differences = List.copyOf(differences);
        }

        public boolean ok() {
            return differences.isEmpty();
        }

        public String describe() {
            return describe(25);
        }

        /** A report naming the packets and fields that differed, at most {@code limit} of them. */
        public String describe(int limit) {
            if (differences.isEmpty()) {
                return "Packets match (" + comparedPackets + " packets, " + comparedFields + " fields)";
            }
            StringBuilder text = new StringBuilder(differences.size() + " packet difference(s) over "
                    + comparedPackets + " packets, " + comparedFields + " fields:");
            for (PacketDifference d : differences.subList(0, Math.min(limit, differences.size()))) {
                text.append("\n  ").append(d);
            }
            if (differences.size() > limit) {
                text.append("\n  ... and ").append(differences.size() - limit).append(" more");
            }
            return text.toString();
        }
    }

    /**
     * One packet with the timestamp masked and each text normaliser applied in order. Returns a copy;
     * the input is not changed.
     */
    public static Map<String, Object> normalisePacket(Map<String, Object> packet, List<Normaliser> normalisers) {
        Map<String, Object> out = new LinkedHashMap<>(packet);
        if (out.get("ts") != null) {
            out.put("ts", TIMESTAMP);
        }
        if (out.get("text") instanceof String original) {
            String text = original;
            for (Normaliser normaliser : normalisers) {
                text = normaliser.apply(text);
            }
            out.put("text", text);
        }
        return out;
    }

    /**
     * The packets as normalised JSON, in order. Accepts {@link CapturedPacket}s or maps with the
     * same fields.
     *
     * @throws IllegalArgumentException for any other element
     */
    public static String toJson(List<?> packets, List<Normaliser> normalisers) {
        List<Object> normalised = new ArrayList<>();
        for (Object packet : packets) {
            Map<String, Object> fields;
            if (packet instanceof CapturedPacket captured) {
                fields = captured.toMap();
            } else if (packet instanceof Map<?, ?> raw) {
                fields = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    fields.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else {
                throw new IllegalArgumentException("packet must be a CapturedPacket or a map, not "
                        + (packet == null ? "null" : packet.getClass().getSimpleName()));
            }
            normalised.add(normalisePacket(fields, normalisers));
        }
        return PacketJson.dumps(normalised);
    }

    /**
     * Compares two normalised packet JSON sequences, naming each packet and field that differed.
     *
     * @throws IllegalArgumentException if either side is not JSON, or not a JSON list
     */
    public static PacketsDiff compare(String expectedJson, String actualJson) {
        Object expected = PacketJson.parse(expectedJson);
        Object actual = PacketJson.parse(actualJson);
        if (!(expected instanceof List<?> e) || !(actual instanceof List<?> a)) {
            throw new IllegalArgumentException("packets must be a JSON list");
        }

        List<PacketDifference> differences = new ArrayList<>();
        int comparedFields = 0;
        int max = Math.max(e.size(), a.size());
        for (int idx = 0; idx < max; idx++) {
            if (idx < e.size() && idx >= a.size()) {
                for (Map.Entry<String, Object> field : asMap(e.get(idx)).entrySet()) {
                    differences.add(new PacketDifference(idx, field.getKey(), PyRepr.str(field.getValue()), null));
                    comparedFields++;
                }
            } else if (idx >= e.size()) {
                for (Map.Entry<String, Object> field : asMap(a.get(idx)).entrySet()) {
                    differences.add(new PacketDifference(idx, field.getKey(), null, PyRepr.str(field.getValue())));
                    comparedFields++;
                }
            } else {
                Map<String, Object> expectedPacket = asMap(e.get(idx));
                Map<String, Object> actualPacket = asMap(a.get(idx));
                TreeSet<String> names = new TreeSet<>(expectedPacket.keySet());
                names.addAll(actualPacket.keySet());
                for (String field : names) {
                    comparedFields++;
                    if (!expectedPacket.containsKey(field)) {
                        differences.add(new PacketDifference(idx, field, null, PyRepr.str(actualPacket.get(field))));
                    } else if (!actualPacket.containsKey(field)) {
                        differences.add(new PacketDifference(idx, field, PyRepr.str(expectedPacket.get(field)), null));
                    } else if (!PacketJson.deepEquals(expectedPacket.get(field), actualPacket.get(field))) {
                        differences.add(new PacketDifference(idx, field, PyRepr.str(expectedPacket.get(field)),
                                PyRepr.str(actualPacket.get(field))));
                    }
                }
            }
        }
        // Packets present on both sides, as the Python counts them: the sequences' longer length.
        int comparedPackets = e.size() + a.size() - Math.min(e.size(), a.size());
        return new PacketsDiff(differences, comparedPackets, comparedFields);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object packet) {
        if (packet instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("a packet must be a JSON object, not "
                + (packet == null ? "null" : packet.getClass().getSimpleName()));
    }
}
