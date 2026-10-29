package com.verifone.cat.pos.receipt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A captured receipt, plus an honest statement of what is missing. Ported from
 * {@code petrotest.pos.receipt.Receipt}.
 *
 * <p>{@link #coverage()} says which sections are present, so a snapshot can refuse to pass on a
 * receipt narrower than its baseline (DESIGN.md 5.3, 14 Q3): a "no" answer must fail loudly, not
 * degrade the test silently.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record Receipt(
        List<ReceiptLine> lines,
        Source source,
        List<String> header,
        List<String> footer,
        String total,
        List<String> mop) {

    public Receipt {
        lines = lines == null ? List.of() : List.copyOf(lines);
        source = source == null ? Source.DOM : source;
        header = header == null ? List.of() : List.copyOf(header);
        footer = footer == null ? List.of() : List.copyOf(footer);
        mop = mop == null ? List.of() : List.copyOf(mop);
    }

    /** An item-only receipt, the shape both the DOM and serial paths start from. */
    public static Receipt ofLines(List<ReceiptLine> lines, Source source) {
        return new Receipt(lines, source, null, null, null, null);
    }

    /**
     * A receipt kept exactly as printed, one raw line per printed line, blank lines and column spacing included, as
     * {@link SerialReceiptCapture} keeps the POS's. {@link #text()} gives {@code text} back unchanged.
     *
     * <p>The DCR's receipt is the Python's {@code receipt_since} string, which the validation normalises and compares
     * as it is. CAT-Java run 5285e78aa370 (PETROCORE-118399) put it through {@link ReceiptParser#parse}, which drops
     * blank lines and collapses spacing, so the centred header and the amount columns of the baseline no longer
     * matched.
     */
    public static Receipt printed(String text, Source source) {
        List<ReceiptLine> lines = new ArrayList<>();
        String[] raw = text.split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            lines.add(ReceiptLine.ofRaw(i, raw[i]));
        }
        return ofLines(lines, source);
    }

    /** Which sections this capture actually contains: "items", "header", "footer", "total", "mop". */
    public Set<String> coverage() {
        Set<String> present = new TreeSet<>();
        if (!lines.isEmpty()) {
            present.add("items");
        }
        if (!header.isEmpty()) {
            present.add("header");
        }
        if (!footer.isEmpty()) {
            present.add("footer");
        }
        if (total != null) {
            present.add("total");
        }
        if (!mop.isEmpty()) {
            present.add("mop");
        }
        return Set.copyOf(present);
    }

    /** The whole receipt as text, sections in receipt order, newline-joined. */
    public String text() {
        List<String> blocks = new ArrayList<>(header);
        for (ReceiptLine line : lines) {
            blocks.add(line.text());
        }
        if (total != null) {
            blocks.add(total);
        }
        blocks.addAll(mop);
        blocks.addAll(footer);
        return String.join("\n", blocks);
    }

    @Override
    public String toString() {
        return text();
    }
}
