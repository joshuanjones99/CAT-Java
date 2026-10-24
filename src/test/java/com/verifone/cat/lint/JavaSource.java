package com.verifone.cat.lint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A Java source file as the design-rule gates read it. The Python gates work on {@code ast}, where a comment is not
 * code and a string is a value with a place; this is the Java counterpart, a lexer rather than a parser.
 *
 * <p>{@link #code} is the file with every comment, and every literal's contents, blanked to spaces (newlines kept),
 * so a regex over it sees only code. {@link #literals} lists each string literal, text blocks included, with its
 * decoded value and its line. Exact for comments, strings, text blocks and char literals, which is what the gates need.
 */
final class JavaSource {

    /** A string literal's decoded value and the line it starts on. */
    record Literal(int line, String value) {}

    /** The source with comments and literal contents blanked. */
    final String code;

    /** Every string literal, in file order. */
    final List<Literal> literals;

    private final int[] lineStarts;

    private JavaSource(String code, List<Literal> literals, int[] lineStarts) {
        this.code = code;
        this.literals = literals;
        this.lineStarts = lineStarts;
    }

    static JavaSource of(String src) {
        int[] starts = lineStarts(src);
        StringBuilder code = new StringBuilder(src.length());
        List<Literal> literals = new ArrayList<>();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && d == '/') {
                int end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(src, i, end, code);
                i = end;
            } else if (c == '/' && d == '*') {
                int close = src.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                blank(src, i, end, code);
                i = end;
            } else if (src.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !src.startsWith("\"\"\"", j)) {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n);
                literals.add(new Literal(lineOf(starts, i), unescape(src.substring(i + 3, j))));
                code.append("\"\"\"");
                blank(src, i + 3, j, code);
                if (j < n) {
                    code.append("\"\"\"");
                }
                i = Math.min(j + 3, n);
            } else if (c == '"') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '"' && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n);
                boolean closed = j < n && src.charAt(j) == '"';
                literals.add(new Literal(lineOf(starts, i), unescape(src.substring(i + 1, j))));
                code.append('"');
                blank(src, i + 1, j, code);
                if (closed) {
                    code.append('"');
                }
                i = closed ? j + 1 : j;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '\'' && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n);
                boolean closed = j < n && src.charAt(j) == '\'';
                code.append('\'');
                blank(src, i + 1, j, code);
                if (closed) {
                    code.append('\'');
                }
                i = closed ? j + 1 : j;
            } else {
                code.append(c);
                i++;
            }
        }
        return new JavaSource(code.toString(), List.copyOf(literals), starts);
    }

    /** The 1-based line a character offset of {@link #code} (or of the source; the two line up) is on. */
    int line(int offset) {
        return lineOf(lineStarts, offset);
    }

    private static void blank(String src, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(src.charAt(k) == '\n' ? '\n' : ' ');
        }
    }

    private static int[] lineStarts(String src) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int k = 0; k < src.length(); k++) {
            if (src.charAt(k) == '\n') {
                starts.add(k + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] starts, int offset) {
        int idx = Arrays.binarySearch(starts, offset);
        return idx >= 0 ? idx + 1 : -idx - 1;
    }

    /** The escapes a Java string or text block can carry that a gate might match against. */
    static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (c != '\\' || k + 1 == raw.length()) {
                out.append(c);
                continue;
            }
            char e = raw.charAt(++k);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 's' -> out.append(' ');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                default -> out.append(e);
            }
        }
        return out.toString();
    }
}
