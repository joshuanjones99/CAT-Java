package com.verifone.cat.preflight;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.List;

/**
 * The CLI's console output, safe on a Windows console.
 *
 * <p>Java 17 encodes {@code System.out} with the console's code page (for example IBM437, or cp1252
 * in a Git Bash window), and the dash that code page can encode shows as {@code �} in a UTF-8 terminal.
 * So an em or en dash is always written as a plain hyphen, whatever the charset: the output is ASCII
 * for dashes, and any other character the charset cannot encode becomes {@code ?}.
 */
public final class CliOutput {

    /** The properties that name the console's (or a pipe's) encoding, in the order they are tried. */
    private static final List<String> ENCODING_PROPERTIES = List.of("stdout.encoding", "sun.stdout.encoding");

    private CliOutput() {}

    /** The charset the console decodes bytes with; the platform default when none is named. */
    public static Charset consoleCharset() {
        for (String key : ENCODING_PROPERTIES) {
            String name = System.getProperty(key);
            if (name != null && !name.isBlank() && Charset.isSupported(name.strip())) {
                return Charset.forName(name.strip());
            }
        }
        return Charset.defaultCharset();
    }

    /** {@code System.out} re-encoded for the console. Autoflushes, as {@code println} needs. */
    public static PrintStream stdout() {
        return new PrintStream(System.out, true, consoleCharset());
    }

    /** {@code System.err} re-encoded for the console. */
    public static PrintStream stderr() {
        return new PrintStream(System.err, true, consoleCharset());
    }

    /**
     * {@code text} as the console shows it: an em or en dash is always a hyphen, and any other character
     * {@code charset} cannot encode is {@code ?}. Dashes are folded whatever the charset, because a
     * charset that can encode them still shows them as a replacement mark in some terminals.
     */
    public static String fit(String text, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '—' || codePoint == '–') {
                out.append('-');
                return;
            }
            String one = new String(Character.toChars(codePoint));
            out.append(encoder.canEncode(one) ? one : "?");
        });
        return out.toString();
    }

    /** {@link #fit(String, Charset)} for the console's charset. */
    public static String fit(String text) {
        return fit(text, consoleCharset());
    }
}
