package com.verifone.cat.runservice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An expected and an actual text as two columns, every line of both, paired as Python's {@code difflib} pairs them:
 * the Python CAT's {@code cat.web.view.side_by_side}.
 *
 * <p>The pairing is {@code difflib.SequenceMatcher(None, left, right, autojunk=False).get_opcodes()}, ported here
 * (no junk, no popular-element pruning, so the matching blocks are the longest common runs found left to right,
 * exactly as the Python finds them). A replaced run pairs its lines one for one; the longer side's rest is removed
 * or added.
 */
public final class SideBySide {

    /** One row: either side may be null; {@code kind} is equal, changed, removed or added. */
    public record Row(String left, String right, String kind) {}

    private SideBySide() {}

    /** Every line of both texts, paired, for two columns. */
    public static List<Row> rows(String expected, String actual) {
        List<String> left = splitLines(expected);
        List<String> right = splitLines(actual);
        List<Row> rows = new ArrayList<>();
        for (int[] op : opcodes(left, right)) {
            int tag = op[0];
            int a0 = op[1];
            int a1 = op[2];
            int b0 = op[3];
            int b1 = op[4];
            if (tag == EQUAL) {
                for (int i = a0; i < a1; i++) {
                    rows.add(new Row(left.get(i), left.get(i), "equal"));
                }
                continue;
            }
            int count = Math.max(a1 - a0, b1 - b0);
            for (int index = 0; index < count; index++) {
                String old = index < a1 - a0 ? left.get(a0 + index) : null;
                String now = index < b1 - b0 ? right.get(b0 + index) : null;
                String kind = old == null ? "added" : (now != null ? "changed" : "removed");
                rows.add(new Row(old, now, kind));
            }
        }
        return rows;
    }

    /** Python's {@code str.splitlines()} for the line breaks a receipt or TLog can hold. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
            i++;
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    static final int EQUAL = 0;
    static final int REPLACE = 1;
    static final int DELETE = 2;
    static final int INSERT = 3;

    /** {@code SequenceMatcher.get_opcodes()}: {tag, a0, a1, b0, b1} rows. */
    static List<int[]> opcodes(List<String> a, List<String> b) {
        List<int[]> answer = new ArrayList<>();
        int i = 0;
        int j = 0;
        for (int[] block : matchingBlocks(a, b)) {
            int ai = block[0];
            int bj = block[1];
            int size = block[2];
            int tag = -1;
            if (i < ai && j < bj) {
                tag = REPLACE;
            } else if (i < ai) {
                tag = DELETE;
            } else if (j < bj) {
                tag = INSERT;
            }
            if (tag >= 0) {
                answer.add(new int[] {tag, i, ai, j, bj});
            }
            i = ai + size;
            j = bj + size;
            if (size > 0) {
                answer.add(new int[] {EQUAL, ai, i, bj, j});
            }
        }
        return answer;
    }

    /** {@code SequenceMatcher.get_matching_blocks()}, ending with the {@code (len(a), len(b), 0)} sentinel. */
    static List<int[]> matchingBlocks(List<String> a, List<String> b) {
        Map<String, List<Integer>> b2j = new HashMap<>();
        for (int j = 0; j < b.size(); j++) {
            b2j.computeIfAbsent(b.get(j), k -> new ArrayList<>()).add(j);
        }
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[] {0, a.size(), 0, b.size()});
        List<int[]> blocks = new ArrayList<>();
        while (!queue.isEmpty()) {
            int[] range = queue.pop();
            int alo = range[0];
            int ahi = range[1];
            int blo = range[2];
            int bhi = range[3];
            int[] x = longestMatch(a, b, b2j, alo, ahi, blo, bhi);
            int i = x[0];
            int j = x[1];
            int k = x[2];
            if (k > 0) {
                blocks.add(x);
                if (alo < i && blo < j) {
                    queue.push(new int[] {alo, i, blo, j});
                }
                if (i + k < ahi && j + k < bhi) {
                    queue.push(new int[] {i + k, ahi, j + k, bhi});
                }
            }
        }
        blocks.sort((p, q) -> p[0] != q[0] ? Integer.compare(p[0], q[0])
                : p[1] != q[1] ? Integer.compare(p[1], q[1]) : Integer.compare(p[2], q[2]));
        List<int[]> collapsed = new ArrayList<>();
        int i1 = 0;
        int j1 = 0;
        int k1 = 0;
        for (int[] block : blocks) {
            int i2 = block[0];
            int j2 = block[1];
            int k2 = block[2];
            if (i1 + k1 == i2 && j1 + k1 == j2) {
                k1 += k2;
            } else {
                if (k1 > 0) {
                    collapsed.add(new int[] {i1, j1, k1});
                }
                i1 = i2;
                j1 = j2;
                k1 = k2;
            }
        }
        if (k1 > 0) {
            collapsed.add(new int[] {i1, j1, k1});
        }
        collapsed.add(new int[] {a.size(), b.size(), 0});
        return collapsed;
    }

    /** {@code SequenceMatcher.find_longest_match} with no junk: the earliest longest common run. */
    private static int[] longestMatch(List<String> a, List<String> b, Map<String, List<Integer>> b2j,
            int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            for (int j : b2j.getOrDefault(a.get(i), List.of())) {
                if (j < blo) {
                    continue;
                }
                if (j >= bhi) {
                    break;
                }
                int k = j2len.getOrDefault(j - 1, 0) + 1;
                newj2len.put(j, k);
                if (k > bestsize) {
                    besti = i - k + 1;
                    bestj = j - k + 1;
                    bestsize = k;
                }
            }
            j2len = newj2len;
        }
        while (besti > alo && bestj > blo && a.get(besti - 1).equals(b.get(bestj - 1))) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi
                && a.get(besti + bestsize).equals(b.get(bestj + bestsize))) {
            bestsize++;
        }
        return new int[] {besti, bestj, bestsize};
    }
}
