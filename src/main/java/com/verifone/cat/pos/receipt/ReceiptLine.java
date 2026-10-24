package com.verifone.cat.pos.receipt;

/**
 * One printed (or on-screen) receipt line. Ported from {@code petrotest.pos.receipt.ReceiptLine}.
 *
 * <p>{@code index} is the POS's own row number, recovered from the element ID suffix on the DOM
 * path, or the line's position in the printed text on the serial path. A gap in the DOM must not
 * shift every line below it: that shift is the DESIGN.md 3.3 failure class.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param index       the row number
 * @param description the item or label text, empty when the line is raw
 * @param amount      the amount column, empty when the line is raw
 * @param quantity    the quantity column, empty when none
 * @param raw         the whole line as one string; preferred by {@link #text()} when non-empty
 */
public record ReceiptLine(int index, String description, String amount, String quantity, String raw) {

    public ReceiptLine {
        description = description == null ? "" : description;
        amount = amount == null ? "" : amount;
        quantity = quantity == null ? "" : quantity;
        raw = raw == null ? "" : raw;
    }

    /** A whole line, kept exactly as read. */
    public static ReceiptLine ofRaw(int index, String raw) {
        return new ReceiptLine(index, "", "", "", raw);
    }

    /** An item line split into its columns. */
    public static ReceiptLine item(int index, String description, String amount) {
        return new ReceiptLine(index, description, amount, "", "");
    }

    /** The line as a single string, for snapshot comparison. */
    public String text() {
        if (!raw.isEmpty()) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        for (String part : new String[] {description, quantity, amount}) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("  ");
            }
            out.append(part);
        }
        return out.toString();
    }
}
