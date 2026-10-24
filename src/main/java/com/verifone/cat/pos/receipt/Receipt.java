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
