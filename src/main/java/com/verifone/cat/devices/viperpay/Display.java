package com.verifone.cat.devices.viperpay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.verifone.cat.devices.viperpay.JavaSerialReader.JavaObject;

/**
 * One {@code DisplayRequest}: what the ViperPay simulator shows on a pinpad and what it will accept.
 * The simulator pushes one per display change to the listener port. Ported from the Python CAT's
 * {@code decode_display_request} and its {@code Display}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param popId       the POP the display belongs to, as sent ("001" or "1")
 * @param commandType the command ({@code GET_ANY_KEY}, {@code READ_CARD}, ...), or null
 * @param lines       the display's lines; a line may be null or blank
 * @param keys        the soft keys on offer
 * @param minDigits   the shortest entry, for an entry command
 * @param maxDigits   the longest entry, for an entry command
 */
public record Display(
        String popId,
        String commandType,
        List<String> lines,
        List<String> keys,
        int minDigits,
        int maxDigits) {

    private static final String DISPLAY_REQUEST = ".DisplayRequest";

    public Display {
        popId = popId == null ? "" : popId;
        // Null lines are kept: the simulator sends them, and List.copyOf would refuse them.
        lines = lines == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(lines));
        keys = keys == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(keys));
    }

    /** The lines that are not empty, joined with spaces: the display as one line of text. */
    public String text() {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (line != null && !line.isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(line);
            }
        }
        return text.toString();
    }

    /**
     * One {@code DisplayRequest} stream, as the simulator writes it to the listener port.
     *
     * @throws IllegalArgumentException if the stream is malformed or is not a {@code DisplayRequest}
     */
    public static Display decode(byte[] data) {
        Object value = JavaSerialReader.loads(data);
        if (!(value instanceof JavaObject obj) || !obj.className().endsWith(DISPLAY_REQUEST)) {
            throw new IllegalArgumentException("expected a DisplayRequest, got " + describe(value));
        }
        return new Display(
                string(obj.field("popID")),
                string(obj.field("commandType")),
                strings(obj.field("message")),
                strings(obj.field("keyList")),
                integer(obj.field("minDigits")),
                integer(obj.field("maxDigits")));
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static int integer(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                out.add(item instanceof String s ? s : null);
            }
        }
        return out;
    }

    private static String describe(Object value) {
        String text = String.valueOf(value);
        return text.length() > 80 ? text.substring(0, 80) : text;
    }
}
