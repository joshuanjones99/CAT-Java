package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.validate.ReceiptRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The receipt comparison's canonical forms: the parts of a receipt this build prints in no fixed order, the
 * run-dependent numbers a report masks, the merchant copy a print job may or may not carry, and this build's
 * known layout drift. Ported from the Python {@code validation.py} functions of the same names
 * ({@code pop_blocks_sorted}, {@code report_canonical}, {@code without_merchant_copy}, {@code layout_drift_only}).
 *
 * <p>{@link #layoutDriftOnly} needs a line diff. The Python uses {@code difflib.SequenceMatcher}; this port uses a
 * longest-common-subsequence alignment, which agrees with it on every case the tests pin and can differ on an
 * ambiguous one (a core change would expose the validate package's opcodes for exact parity).
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
final class ReceiptCanon {

    /** One POP discount's receipt block: its program's name, the rate, the gallons. */
    private static final String POP_BLOCK = "POP-DISCNT-\\d+\\nYOU RECEIVED [^\\n]*\\nFOR [^\\n]*\\n";
    private static final int FLAGS = Pattern.UNIX_LINES;
    private static final Pattern POP_RUN = Pattern.compile("(?:" + POP_BLOCK + "\\n*)+" + POP_BLOCK, FLAGS);
    private static final Pattern POP_BLOCK_PATTERN = Pattern.compile(POP_BLOCK, FLAGS);

    /** One loyalty approval of the EPS network report (37064): fuel, volume, non-fuel, discount, amount, number. */
    private static final String LOYALTY_ENTRY = "[-\\d.]+(?: +[-\\d.]+){4}\\n\\d+\\n";
    private static final Pattern LOYALTY_RUN = Pattern.compile(
            "^(?:" + LOYALTY_ENTRY + "\\n)*" + LOYALTY_ENTRY, Pattern.MULTILINE | FLAGS);
    private static final Pattern LOYALTY_ENTRY_PATTERN = Pattern.compile(LOYALTY_ENTRY, FLAGS);

    /** A run of loyalty detail lines (37031, 37033, 37036): account, total, discount. */
    private static final Pattern LOYALTY_LINES = Pattern.compile(
            "^(?:[X\\d]{12,19} +-?[\\d.]+ +-?[\\d.]+\\n)+", Pattern.MULTILINE | FLAGS);

    /** A run of fuel street price change rows (37027). */
    private static final String PRICE_ROW = "[^\\n]{10} +\\d+ +[\\d.]+ +[\\d.]+\\n +\\d+ +[\\d.]+ +[\\d.]+\\n";
    private static final Pattern PRICE_ROWS = Pattern.compile("^(?:" + PRICE_ROW + ")+", Pattern.MULTILINE | FLAGS);
    private static final Pattern PRICE_ROW_PATTERN = Pattern.compile(PRICE_ROW, FLAGS);

    /** One Terminal Batch Detail entry (37035): account, card type, the EPS reference, the total, then its STAN. */
    private static final Pattern DETAIL_ENTRY = Pattern.compile(
            "^(\\d{12,19} +[A-Z]{2} )\\d{8}( +-?[\\d,]+\\.\\d\\d\\n)\\d+$", Pattern.MULTILINE | FLAGS);

    /** The cashier report's own number (37072). */
    private static final Pattern REPORT_NUMBER = Pattern.compile("^(CASHIER REPORT #)\\d+$", Pattern.MULTILINE | FLAGS);

    /** A POP discount line where the baseline has a blank one. */
    private static final Pattern POP_DISCOUNT_LINE = Pattern.compile("POP-DISCNT-\\d+", FLAGS);

    /** A car-wash code line with the wash's PLU name before the (masked) code. */
    private static final Pattern NAMED_CARWASH_LINE = Pattern.compile("\\S.*\\S\\s+<CARWASH_CODE>", FLAGS);

    /** The store header's line before and after the Buypass table load (HANDOFF.md §1.90). */
    private static final String STORE_LINE_UNLOADED = "9999999";
    private static final String STORE_LINE_LOADED = "99999";

    private ReceiptCanon() {
    }

    /** One difflib-style edit: a tag and half-open ranges in both texts. */
    private enum Tag { EQUAL, REPLACE, DELETE, INSERT }

    private record Opcode(Tag tag, int i1, int i2, int j1, int j2) {
    }

    /** {@code _canonical}: the receipt with its POP blocks sorted, its rule applied, and its reports masked. */
    static String canonical(String text) {
        return reportCanonical(ReceiptRule.applyRule(popBlocksSorted(text)));
    }

    /**
     * {@code pop_blocks_sorted}: {@code text} with each run of adjacent POP discount blocks in name order. The
     * blocks and the blank lines between them are kept; only their order is made canonical.
     */
    static String popBlocksSorted(String text) {
        String padded = text + "\n";
        Matcher run = POP_RUN.matcher(padded);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (run.find()) {
            out.append(padded, last, run.start());
            out.append(canonicalPopRun(run.group()));
            last = run.end();
        }
        out.append(padded.substring(last));
        return out.substring(0, out.length() - 1);
    }

    private static String canonicalPopRun(String group) {
        List<String> blocks = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        Matcher block = POP_BLOCK_PATTERN.matcher(group);
        int previousEnd = -1;
        while (block.find()) {
            if (previousEnd >= 0) {
                gaps.add(group.substring(previousEnd, block.start()));
            }
            blocks.add(block.group());
            previousEnd = block.end();
        }
        Collections.sort(blocks);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            out.append(blocks.get(i));
            if (i < gaps.size()) {
                out.append(gaps.get(i));
            }
        }
        return out.toString();
    }

    /**
     * {@code report_canonical}: the end-of-plan reports' run-dependent parts made canonical. Every amount, count
     * and total is still compared; what the EPS prints in no fixed order is sorted, and the numbers it gives
     * afresh each run are masked.
     */
    static String reportCanonical(String input) {
        String text = input.replace("\r\n", "\n");
        text = replace(LOYALTY_RUN, text, group -> {
            List<String> entries = new ArrayList<>();
            Matcher entry = LOYALTY_ENTRY_PATTERN.matcher(group);
            while (entry.find()) {
                entries.add(entry.group());
            }
            Collections.sort(entries);
            return String.join("\n", entries);
        });
        text = replace(LOYALTY_LINES, text, group -> {
            List<String> lines = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < group.length(); i++) {
                if (group.charAt(i) == '\n') {
                    lines.add(group.substring(start, i + 1));
                    start = i + 1;
                }
            }
            Collections.sort(lines);
            return String.join("", lines);
        });
        text = replace(PRICE_ROWS, text, group -> {
            List<String> rows = new ArrayList<>();
            Matcher row = PRICE_ROW_PATTERN.matcher(group);
            while (row.find()) {
                rows.add(row.group());
            }
            Collections.sort(rows);
            return String.join("", rows);
        });
        text = DETAIL_ENTRY.matcher(text).replaceAll("$1<REF#>$2<STAN>");
        return REPORT_NUMBER.matcher(text).replaceAll("$1<N>");
    }

    private static String replace(Pattern pattern, String text, Function<String, String> with) {
        Matcher match = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (match.find()) {
            out.append(text, last, match.start());
            out.append(with.apply(match.group()));
            last = match.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /**
     * {@code without_merchant_copy}: {@code text} from its second copy on, when its first copy is a card sale's
     * MERCHANT COPY. A copy starts at a repeat of the receipt's first line.
     */
    static String withoutMerchantCopy(String text) {
        List<String> lines = List.of(text.split("\n", -1));
        String first = lines.isEmpty() ? "" : lines.get(0);
        if (first.isBlank()) {
            return text;
        }
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(first)) {
                starts.add(i);
            }
        }
        if (starts.size() < 2) {
            return text;
        }
        int second = starts.get(1);
        boolean merchant = lines.subList(0, second).stream().anyMatch(line -> line.contains("MERCHANT COPY"));
        return merchant ? String.join("\n", lines.subList(second, lines.size())) : text;
    }

    /**
     * {@code layout_drift_only}: true if {@code got} differs from {@code expected} only by this build's known
     * layout changes: a {@code POP-DISCNT-n} line where the baseline has a blank one, a {@code 99999} line added to
     * the store header, the wash's PLU name before a car-wash code, and the store header's {@code 99999} where the
     * baseline has {@code 9999999} (PATS's references were recorded with the Buypass tables unloaded).
     */
    static boolean layoutDriftOnly(String expected, String got) {
        List<String> old = ValidationSupport.splitLines(expected);
        List<String> now = ValidationSupport.splitLines(got);
        for (Opcode op : opcodes(old, now)) {
            if (op.tag() == Tag.EQUAL) {
                continue;
            }
            List<String> added = now.subList(op.j1(), op.j2());
            List<String> removed = old.subList(op.i1(), op.i2());
            if (op.tag() == Tag.INSERT && added.stream().allMatch(line -> line.strip().equals("99999"))) {
                continue;
            }
            if (op.tag() == Tag.REPLACE && added.size() == removed.size() && allLayoutReplacements(removed, added)) {
                continue;
            }
            return false;
        }
        return !old.equals(now);
    }

    private static boolean allLayoutReplacements(List<String> removed, List<String> added) {
        for (int i = 0; i < removed.size(); i++) {
            if (!isLayoutReplacement(removed.get(i), added.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code _is_layout_replacement}: one removed line and the added line that replaces it, as drift. */
    static boolean isLayoutReplacement(String removed, String added) {
        String was = removed.strip();
        String now = added.strip();
        if (was.equals(STORE_LINE_UNLOADED)) {
            return now.equals(STORE_LINE_LOADED);
        }
        if (was.isEmpty()) {
            return POP_DISCOUNT_LINE.matcher(now).matches();
        }
        return was.equals("<CARWASH_CODE>") && NAMED_CARWASH_LINE.matcher(now).matches();
    }

    /**
     * The edit script from {@code a} to {@code b}, as difflib's opcodes: the matching lines of a longest common
     * subsequence, and the replace, delete or insert between each run of them.
     */
    private static List<Opcode> opcodes(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Opcode> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < m || j < n) {
            if (i < m && j < n && a.get(i).equals(b.get(j))) {
                int i0 = i;
                int j0 = j;
                while (i < m && j < n && a.get(i).equals(b.get(j))) {
                    i++;
                    j++;
                }
                out.add(new Opcode(Tag.EQUAL, i0, i, j0, j));
                continue;
            }
            int i0 = i;
            int j0 = j;
            while ((i < m || j < n) && !(i < m && j < n && a.get(i).equals(b.get(j)))) {
                if (j >= n || (i < m && lcs[i + 1][j] >= lcs[i][j + 1])) {
                    i++;
                } else {
                    j++;
                }
            }
            Tag tag;
            if (i > i0 && j > j0) {
                tag = Tag.REPLACE;
            } else if (i > i0) {
                tag = Tag.DELETE;
            } else {
                tag = Tag.INSERT;
            }
            out.add(new Opcode(tag, i0, i, j0, j));
        }
        return out;
    }
}
