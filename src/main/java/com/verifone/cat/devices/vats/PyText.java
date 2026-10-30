package com.verifone.cat.devices.vats;

/**
 * The two pieces of Python text handling this package needs on the Java side: {@code re.escape}, because the agent
 * matches the patterns CAT builds with Python's {@code re}, and {@code repr} of a string, because the operator lines
 * and errors quote prompts and labels the way the Python CAT's do ({@code 'Enter PIN'}).
 */
final class PyText {

    /** Python 3.7+'s {@code re._special_chars_map}: the characters {@code re.escape} backslashes. */
    private static final String SPECIAL = "()[]{}?*+-|^$\\.&~# \t\n\r\u000b\f";

    private PyText() {}

    /** Python's {@code re.escape(text)}. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (SPECIAL.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Python's {@code repr(text)} for a string: single quotes unless it holds one and no double quote. */
    static String repr(String text) {
        if (text == null) {
            return "None";
        }
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(text.length() + 2).append(quote);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c == quote) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        return out.append(quote).toString();
    }
}
