package com.verifone.cat.preflight;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.List;

/**
 * The CLI's console output, safe on a Windows console.
 *
 * <p>Java 17 encodes {@code System.out} with the console's code page (for example IBM437), which
 * has no em dash, so {@code —} printed as {@code �}. This helper picks the charset the console
 * decodes with and replaces any character that charset cannot encode: an em or en dash becomes
 * {@code -}, anything else {@code ?}. The output is ASCII where it has to be and unchanged where it can be.
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

    /** {@code text} with every character {@code charset} cannot encode replaced by ASCII. */
    public static String fit(String text, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            String one = new String(Character.toChars(codePoint));
            if (encoder.canEncode(one)) {
                out.append(one);
            } else {
                out.append(codePoint == '—' || codePoint == '–' ? "-" : "?");
            }
        });
        return out.toString();
    }

    /** {@link #fit(String, Charset)} for the console's charset. */
    public static String fit(String text) {
        return fit(text, consoleCharset());
    }
}
