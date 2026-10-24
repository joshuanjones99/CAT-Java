package com.verifone.cat.validate;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * Python's {@code str()} and {@code repr()} for the values the TLog and packet comparers put in
 * their messages, so a Java difference reads like the Python CAT's.
 *
 * <p>Covers strings, {@code None}, booleans, numbers, lists and dicts. Other objects use
 * {@link Object#toString()}. Used only for report text, never for comparison or for the stored
 * snapshot form. The string quoting follows Python's rule (single quotes unless the text holds
 * one and no double quote); escapes are the common ones.
 */
public final class PyRepr {

    private PyRepr() {}

    /** Python's {@code str(value)}: a string as itself, anything else by {@link #repr(Object)}. */
    public static String str(Object value) {
        return value instanceof String text ? text : repr(value);
    }

    /** Python's {@code repr(value)}. */
    public static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String text) {
            return reprString(text);
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (value instanceof Double d) {
            return floatRepr(d);
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(repr(entry.getKey())).append(": ").append(repr(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Collection<?> items) {
            return seq(items, "[", "]");
        }
        return value.toString();
    }

    /** Python's {@code repr(str)} for a string. */
    public static String reprString(String text) {
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(quote);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\x%02x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append(quote).toString();
    }

    /** Python's {@code repr(float)} for the values JSON produces: {@code 1.0}, {@code 1234567890.5}. */
    public static String floatRepr(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String plain = new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    private static String seq(Collection<?> items, String open, String close) {
        StringBuilder sb = new StringBuilder(open);
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(repr(item));
        }
        return sb.append(close).toString();
    }
}
