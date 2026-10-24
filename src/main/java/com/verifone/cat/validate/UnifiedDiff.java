package com.verifone.cat.validate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A unified line diff naming the snapshot file, for a failure message (DESIGN.md §5.4).
 *
 * <p>The JDK has no unified-diff writer, so this one stands in for Python's
 * {@code difflib.unified_diff}. The hunk grouping and the range format follow difflib's, so a
 * message reads the same in either harness. The edit script is a longest-common-subsequence
 * walk after trimming the common prefix and suffix. A receipt is a few hundred lines, so the
 * table is small; past {@link #MAX_TABLE_CELLS} the middle is shown as one replacement rather
 * than allocating a table that size, which is still a correct diff, only a coarser one.
 *
 * <p>Compared text is already normalised, so every remaining line difference is a real one. The
 * diff is a line diff on purpose: a reviewer's attention is not spent on noise.
 */
public final class UnifiedDiff {

    /** Largest LCS table built. A 4-million-cell table is 16 MB of ints. */
    static final long MAX_TABLE_CELLS = 4_000_000L;

    private UnifiedDiff() {}

    /**
     * The diff from {@code expected} to {@code actual}, with {@code context} lines around each
     * change. Empty if the texts are the same line for line.
     */
    public static String unified(String expected, String actual, Path path, int context) {
        List<String> a = splitLines(expected);
        List<String> b = splitLines(actual);
        List<Opcode> codes = opcodes(editSteps(a, b), a, b);
        List<String> out = new ArrayList<>();
        out.add("--- " + path + " (snapshot)");
        out.add("+++ captured (this run)");
        for (List<Opcode> group : groupedOpcodes(codes, context)) {
            Opcode first = group.get(0);
            Opcode last = group.get(group.size() - 1);
            out.add("@@ -" + range(first.i1(), last.i2()) + " +" + range(first.j1(), last.j2()) + " @@");
            for (Opcode op : group) {
                if (op.tag() == Tag.EQUAL) {
                    for (int i = op.i1(); i < op.i2(); i++) {
                        out.add(" " + a.get(i));
                    }
                    continue;
                }
                if (op.tag() == Tag.REPLACE || op.tag() == Tag.DELETE) {
                    for (int i = op.i1(); i < op.i2(); i++) {
                        out.add("-" + a.get(i));
                    }
                }
                if (op.tag() == Tag.REPLACE || op.tag() == Tag.INSERT) {
                    for (int j = op.j1(); j < op.j2(); j++) {
                        out.add("+" + b.get(j));
                    }
                }
            }
        }
        return String.join("\n", out);
    }

    /** Python's {@code str.splitlines()} for text with {@code \n} endings: no trailing empty line. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }
        String[] parts = text.split("\n", -1);
        int count = text.endsWith("\n") ? parts.length - 1 : parts.length;
        for (int i = 0; i < count; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    enum Tag { EQUAL, REPLACE, DELETE, INSERT }

    /** One run of the edit script, as difflib's opcodes: half-open ranges in both texts. */
    record Opcode(Tag tag, int i1, int i2, int j1, int j2) {}

    /** One step of the edit script: keep a line, drop one from {@code a}, or add one from {@code b}. */
    private enum Step { KEEP, DROP, ADD }

    private static List<Step> editSteps(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Step> steps = new ArrayList<>();
        for (int k = 0; k < prefix; k++) {
            steps.add(Step.KEEP);
        }
        middleSteps(a.subList(prefix, a.size() - suffix), b.subList(prefix, b.size() - suffix), steps);
        for (int k = 0; k < suffix; k++) {
            steps.add(Step.KEEP);
        }
        return steps;
    }

    private static void middleSteps(List<String> a, List<String> b, List<Step> steps) {
        int m = a.size();
        int n = b.size();
        if ((long) (m + 1) * (n + 1) > MAX_TABLE_CELLS) {
            for (int k = 0; k < m; k++) {
                steps.add(Step.DROP);
            }
            for (int k = 0; k < n; k++) {
                steps.add(Step.ADD);
            }
            return;
        }
        // lcs[i][j] is the LCS length of a[i..] and b[j..].
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (a.get(i).equals(b.get(j))) {
                steps.add(Step.KEEP);
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                steps.add(Step.DROP);
                i++;
            } else {
                steps.add(Step.ADD);
                j++;
            }
        }
        for (; i < m; i++) {
            steps.add(Step.DROP);
        }
        for (; j < n; j++) {
            steps.add(Step.ADD);
        }
    }

    /** Folds the step list into difflib-style opcodes: runs of equal lines and of changes. */
    private static List<Opcode> opcodes(List<Step> steps, List<String> a, List<String> b) {
        List<Opcode> codes = new ArrayList<>();
        int i = 0;
        int j = 0;
        int k = 0;
        while (k < steps.size()) {
            int i1 = i;
            int j1 = j;
            if (steps.get(k) == Step.KEEP) {
                while (k < steps.size() && steps.get(k) == Step.KEEP) {
                    i++;
                    j++;
                    k++;
                }
                codes.add(new Opcode(Tag.EQUAL, i1, i, j1, j));
                continue;
            }
            while (k < steps.size() && steps.get(k) != Step.KEEP) {
                if (steps.get(k) == Step.DROP) {
                    i++;
                } else {
                    j++;
                }
                k++;
            }
            Tag tag = i > i1 && j > j1 ? Tag.REPLACE : i > i1 ? Tag.DELETE : Tag.INSERT;
            codes.add(new Opcode(tag, i1, i, j1, j));
        }
        if (codes.isEmpty()) {
            codes.add(new Opcode(Tag.EQUAL, 0, 0, 0, 0));
        }
        return codes;
    }

    /**
     * Hunks with {@code context} lines either side, merged where two changes sit within
     * {@code 2 * context} lines of each other. This is difflib's {@code get_grouped_opcodes}.
     */
    static List<List<Opcode>> groupedOpcodes(List<Opcode> input, int context) {
        List<Opcode> codes = new ArrayList<>(input);
        if (codes.get(0).tag() == Tag.EQUAL) {
            Opcode c = codes.get(0);
            codes.set(0, new Opcode(Tag.EQUAL, Math.max(c.i1(), c.i2() - context), c.i2(),
                    Math.max(c.j1(), c.j2() - context), c.j2()));
        }
        int last = codes.size() - 1;
        if (codes.get(last).tag() == Tag.EQUAL) {
            Opcode c = codes.get(last);
            codes.set(last, new Opcode(Tag.EQUAL, c.i1(), Math.min(c.i2(), c.i1() + context),
                    c.j1(), Math.min(c.j2(), c.j1() + context)));
        }
        int window = context + context;
        List<List<Opcode>> groups = new ArrayList<>();
        List<Opcode> group = new ArrayList<>();
        for (Opcode c : codes) {
            int i1 = c.i1();
            int j1 = c.j1();
            if (c.tag() == Tag.EQUAL && c.i2() - c.i1() > window) {
                group.add(new Opcode(Tag.EQUAL, c.i1(), Math.min(c.i2(), c.i1() + context),
                        c.j1(), Math.min(c.j2(), c.j1() + context)));
                groups.add(group);
                group = new ArrayList<>();
                i1 = Math.max(i1, c.i2() - context);
                j1 = Math.max(j1, c.j2() - context);
            }
            group.add(new Opcode(c.tag(), i1, c.i2(), j1, c.j2()));
        }
        boolean onlyEqual = group.size() == 1 && group.get(0).tag() == Tag.EQUAL;
        if (!group.isEmpty() && !onlyEqual) {
            groups.add(group);
        }
        return groups;
    }

    /** difflib's {@code _format_range_unified}: a 1-based start and a count. */
    static String range(int start, int stop) {
        int beginning = start + 1;
        int length = stop - start;
        if (length == 1) {
            return Integer.toString(beginning);
        }
        if (length == 0) {
            beginning -= 1;
        }
        return beginning + "," + length;
    }
}
