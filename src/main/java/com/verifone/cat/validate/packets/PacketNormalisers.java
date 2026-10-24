package com.verifone.cat.validate.packets;

import java.util.List;

import com.verifone.cat.validate.Normaliser;

/**
 * The normalisers for a packet's visible {@code text} field (the Python CAT's
 * {@code PACKET_TEXT_NORMALISERS}).
 *
 * <p>Derived from the receipt normalisers where the patterns apply. Every one is {@code RUN}-volatile:
 * if it appears in a captured packet it changes on every execution. The text is a best-effort
 * rendering, so these match the visible form, not the wire encoding. The timestamp field and the hex
 * payload are handled in {@link Packets}, not here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public final class PacketNormalisers {

    /** The default text normalisers, applied in order. */
    public static final List<Normaliser> TEXT = List.of(
            Normaliser.mask("datetime_stamp",
                    "\\b\\d{1,2}/\\d{1,2}/\\d{2,4}[ \\t]+\\d{1,2}:\\d{2}(?::\\d{2})?[ \\t]*(?:AM|PM)",
                    "<DATETIME>")
                    .withEvidence("8/14/25 5:28:27 PM", "10/31/25 11:04:13 AM"),
            Normaliser.mask("date",
                    "\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b",
                    "<DATE>")
                    .withEvidence("8/14/25", "10/31/25", "3/1/21"),
            Normaliser.mask("time",
                    "\\b\\d{1,2}:\\d{2}(?::\\d{2})?[ \\t]*(?:AM|PM)?(?![\\d:])",
                    "<TIME>")
                    .withEvidence("5:28:27 PM", "11:04:13 AM", "13:10:58"),
            Normaliser.mask("stan",
                    "\\bStan:[ \\t]*\\d+",
                    "Stan: <STAN>")
                    .withEvidence("Stan: 000412893", "Stan: 001015724"),
            Normaliser.mask("trace",
                    "\\bTrace[ \\t]*#[ \\t]*:?[ \\t]*\\d+",
                    "Trace #: <TRACE>")
                    .withEvidence("Trace #: 403", "Trace #: 17"),
            Normaliser.mask("seq",
                    "\\bSeq(?:uence)?[ \\t]*#?[ \\t]*:?[ \\t]*\\d+",
                    "Seq #: <SEQ>")
                    .withEvidence("Seq#: 020"),
            Normaliser.mask("invoice",
                    "\\bInv(?:oice)?[ \\t]*#[ \\t]*:?[ \\t]*\\d+",
                    "Invoice #: <INVOICE>")
                    .withEvidence("Invoice #: 72541", "Inv#  1000810"),
            Normaliser.mask("auth_code",
                    "\\bAuth[ \\t]*#[ \\t]*:?[ \\t]*[A-Z0-9]+",
                    "Auth #: <AUTH>")
                    .withEvidence("Auth #: 999696", "Auth# 028833"));

    private PacketNormalisers() {}
}
