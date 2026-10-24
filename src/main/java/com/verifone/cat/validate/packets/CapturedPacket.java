package com.verifone.cat.validate.packets;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One captured host packet: the Python CAT's {@code CapturedPacket}, field for field and in the same
 * order, so {@link #toMap()} is the dict {@code model_dump()} would produce.
 *
 * @param index     position in the capture
 * @param ts        capture time in seconds, or {@code null}; always masked when compared
 * @param direction {@code in} or {@code out}
 * @param peer      {@code host:port}, or {@code null}
 * @param length    payload length in bytes
 * @param hex       the wire payload, hex-encoded, never normalised
 * @param text      a best-effort printable rendering, normalised by the packet normalisers; or {@code null}
 */
public record CapturedPacket(int index, Double ts, String direction, String peer, Integer length, String hex,
        String text) {

    /** The packet's fields, in declaration order. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        out.put("ts", ts);
        out.put("direction", direction);
        out.put("peer", peer);
        out.put("length", length);
        out.put("hex", hex);
        out.put("text", text);
        return out;
    }
}
