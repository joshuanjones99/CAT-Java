package com.verifone.cat.pos.receipt;

/**
 * A {@link ReceiptSource} over fixed text, for unit tests and the PATS port. Ported from the
 * Python {@code StaticReceiptSource}. Parses once, on the first capture.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class StaticReceiptSource implements ReceiptSource {

    private final String text;
    private final Source source;
    private Receipt parsed;

    public StaticReceiptSource(String text) {
        this(text, Source.SERIAL);
    }

    public StaticReceiptSource(String text, Source source) {
        this.text = text;
        this.source = source;
    }

    @Override
    public Receipt capture() {
        if (parsed == null) {
            parsed = ReceiptParser.parse(text, source);
        }
        return parsed;
    }
}
