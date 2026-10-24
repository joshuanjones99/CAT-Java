package com.verifone.cat.pos.receipt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text parsing for printed receipts: the serial path, and hardware-free fixtures. Ported from the
 * Python {@code parse_receipt_text} and {@code transaction_number}.
 *
 * <p>This does no semantic masking. Dates, STANs and cashier names are normalised once, globally,
 * by {@code com.verifone.cat.validate} (DESIGN.md 5.4).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ReceiptParser {

    /** A printed line ending in a currency-ish amount, e.g. {@code WOODSTOCK SINGLE T  1  9.60}. */
    private static final Pattern AMOUNT = Pattern.compile(
            "^(?<body>.*?)\\s{2,}(?<amount>-?\\$?\\s?[\\d,]+\\.\\d{2})\\s*$",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** {@code   PREPAY CA #02      1     10.00}: the quantity sits between description and amount. */
    private static final Pattern QTY = Pattern.compile(
            "^(?<description>.*?)\\s{2,}(?<quantity>\\d+)$", Pattern.UNICODE_CHARACTER_CLASS);

    private ReceiptParser() {}

    /**
     * The {@code TRAN#} a printed receipt carries, or null for a report. PATS's
     * {@code WorkFlowUtility.getTransNumber}: the first line holding {@code TRAN#}, digits after
     * its last {@code #}.
     */
    public static Long transactionNumber(List<String> lines) {
        for (String line : lines) {
            if (line.toUpperCase(Locale.ROOT).contains("TRAN#")) {
                String tail = line.substring(line.lastIndexOf('#') + 1);
                String digits = tail.replaceAll("\\D", "");
                return digits.isEmpty() ? null : Long.parseLong(digits);
            }
        }
        return null;
    }

    /** Parses a printed receipt. Blank lines are dropped; column spacing inside a line is kept. */
    public static Receipt parse(String text, Source source) {
        List<ReceiptLine> lines = new ArrayList<>();
        String[] rawLines = text.replace("\r\n", "\n").split("\n", -1);
        for (int index = 0; index < rawLines.length; index++) {
            String raw = rawLines[index];
            String line = ReceiptText.normalise(raw);
            if (line.isEmpty()) {
                continue;
            }
            Matcher amount = AMOUNT.matcher(stripTrailing(raw));
            if (!amount.lookingAt()) {
                lines.add(ReceiptLine.ofRaw(index, line));
                continue;
            }
            String body = ReceiptText.normalise(amount.group("body"));
            String amountText = ReceiptText.normalise(amount.group("amount"));
            String quantity = "";
            Matcher qty = QTY.matcher(stripTrailing(amount.group("body")));
            if (qty.lookingAt()) {
                body = ReceiptText.normalise(qty.group("description"));
                quantity = qty.group("quantity");
            }
            lines.add(new ReceiptLine(index, body, amountText, quantity, ""));
        }
        return Receipt.ofLines(lines, source);
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
