package com.verifone.cat.commander.snapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The part of Python's {@code difflib} the snapshot shows: {@code SequenceMatcher} (no junk function, autojunk on)
 * and the lines of {@code unified_diff(a, b, n=0, lineterm="")}. Ported step for step from CPython 3.12's
 * {@code difflib.py}, so a difference reads line for line as the Python CAT prints it, including which of two equal
 * lines is taken as the match.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): evidence about this repository only.
 */
final class Difflib {

    private Difflib() {}

    /** One opcode: {@code replace}, {@code delete}, {@code insert} or {@code equal}, over {@code a[i1:i2]}, {@code b[j1:j2]}. */
    record Opcode(String tag, int i1, int i2, int j1, int j2) {}

    /** {@code SequenceMatcher(None, a, b).get_opcodes()}. */
    static List<Opcode> opcodes(List<String> a, List<String> b) {
        return new Matcher(a, b).opcodes();
    }

    /**
     * {@code unified_diff(a, b, "snapshot", "now", n=0, lineterm="")}, every line of it: the two file headers, an
     * {@code @@} line per hunk, then {@code -} and {@code +} lines.
     */
    static List<String> unifiedDiff(List<String> a, List<String> b, String fromFile, String toFile) {
        List<String> out = new ArrayList<>();
        boolean started = false;
        for (List<Opcode> group : groupedOpcodes(opcodes(a, b), 0)) {
            if (!started) {
                started = true;
                out.add("--- " + fromFile);
                out.add("+++ " + toFile);
            }
            Opcode first = group.get(0);
            Opcode last = group.get(group.size() - 1);
            out.add("@@ -" + range(first.i1(), last.i2()) + " +" + range(first.j1(), last.j2()) + " @@");
            for (Opcode code : group) {
                if (code.tag().equals("equal")) {
                    for (String line : a.subList(code.i1(), code.i2())) {
                        out.add(" " + line);
                    }
                    continue;
                }
                if (code.tag().equals("replace") || code.tag().equals("delete")) {
                    for (String line : a.subList(code.i1(), code.i2())) {
                        out.add("-" + line);
                    }
                }
                if (code.tag().equals("replace") || code.tag().equals("insert")) {
                    for (String line : b.subList(code.j1(), code.j2())) {
                        out.add("+" + line);
                    }
                }
            }
        }
        return out;
    }

    /** {@code difflib._format_range_unified}. */
    private static String range(int start, int stop) {
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

    /** {@code SequenceMatcher.get_grouped_opcodes(n)}. */
    static List<List<Opcode>> groupedOpcodes(List<Opcode> source, int n) {
        List<Opcode> codes = new ArrayList<>(source);
        if (codes.isEmpty()) {
            codes.add(new Opcode("equal", 0, 1, 0, 1));
        }
        Opcode head = codes.get(0);
        if (head.tag().equals("equal")) {
            codes.set(0, new Opcode("equal", Math.max(head.i1(), head.i2() - n), head.i2(),
                    Math.max(head.j1(), head.j2() - n), head.j2()));
        }
        Opcode tail = codes.get(codes.size() - 1);
        if (tail.tag().equals("equal")) {
            codes.set(codes.size() - 1, new Opcode("equal", tail.i1(), Math.min(tail.i2(), tail.i1() + n),
                    tail.j1(), Math.min(tail.j2(), tail.j1() + n)));
        }
        int nn = n + n;
        List<List<Opcode>> groups = new ArrayList<>();
        List<Opcode> group = new ArrayList<>();
        for (Opcode code : codes) {
            int i1 = code.i1();
            int j1 = code.j1();
            if (code.tag().equals("equal") && code.i2() - i1 > nn) {
                group.add(new Opcode("equal", i1, Math.min(code.i2(), i1 + n), j1, Math.min(code.j2(), j1 + n)));
                groups.add(group);
                group = new ArrayList<>();
                i1 = Math.max(i1, code.i2() - n);
                j1 = Math.max(j1, code.j2() - n);
            }
            group.add(new Opcode(code.tag(), i1, code.i2(), j1, code.j2()));
        }
        if (!group.isEmpty() && !(group.size() == 1 && group.get(0).tag().equals("equal"))) {
            groups.add(group);
        }
        return groups;
    }

    /** {@code SequenceMatcher} over two lists of lines, with {@code isjunk=None} and {@code autojunk=True}. */
    private static final class Matcher {

        private record Block(int i, int j, int size) {}

        private final List<String> a;
        private final List<String> b;
        private final Map<String, List<Integer>> b2j = new HashMap<>();

        Matcher(List<String> a, List<String> b) {
            this.a = a;
            this.b = b;
            for (int i = 0; i < b.size(); i++) {
                b2j.computeIfAbsent(b.get(i), k -> new ArrayList<>()).add(i);
            }
            int n = b.size();
            if (n >= 200) {
                int ntest = n / 100 + 1;
                Set<String> popular = new HashSet<>();
                for (Map.Entry<String, List<Integer>> entry : b2j.entrySet()) {
                    if (entry.getValue().size() > ntest) {
                        popular.add(entry.getKey());
                    }
                }
                popular.forEach(b2j::remove);
            }
        }

        /** {@code find_longest_match}; with no junk function only the non-junk extension loops can move it. */
        private Block longest(int alo, int ahi, int blo, int bhi) {
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
            return new Block(besti, bestj, bestsize);
        }

        /** {@code get_matching_blocks}. */
        private List<Block> matchingBlocks() {
            int la = a.size();
            int lb = b.size();
            Deque<int[]> queue = new ArrayDeque<>();
            queue.push(new int[] {0, la, 0, lb});
            List<Block> blocks = new ArrayList<>();
            while (!queue.isEmpty()) {
                int[] q = queue.pop();
                int alo = q[0];
                int ahi = q[1];
                int blo = q[2];
                int bhi = q[3];
                Block x = longest(alo, ahi, blo, bhi);
                if (x.size() > 0) {
                    blocks.add(x);
                    if (alo < x.i() && blo < x.j()) {
                        queue.push(new int[] {alo, x.i(), blo, x.j()});
                    }
                    if (x.i() + x.size() < ahi && x.j() + x.size() < bhi) {
                        queue.push(new int[] {x.i() + x.size(), ahi, x.j() + x.size(), bhi});
                    }
                }
            }
            blocks.sort((p, q) -> p.i() != q.i() ? Integer.compare(p.i(), q.i())
                    : p.j() != q.j() ? Integer.compare(p.j(), q.j()) : Integer.compare(p.size(), q.size()));
            List<Block> merged = new ArrayList<>();
            int i1 = 0;
            int j1 = 0;
            int k1 = 0;
            for (Block block : blocks) {
                if (i1 + k1 == block.i() && j1 + k1 == block.j()) {
                    k1 += block.size();
                } else {
                    if (k1 > 0) {
                        merged.add(new Block(i1, j1, k1));
                    }
                    i1 = block.i();
                    j1 = block.j();
                    k1 = block.size();
                }
            }
            if (k1 > 0) {
                merged.add(new Block(i1, j1, k1));
            }
            merged.add(new Block(la, lb, 0));
            return merged;
        }

        /** {@code get_opcodes}. */
        List<Opcode> opcodes() {
            int i = 0;
            int j = 0;
            List<Opcode> answer = new ArrayList<>();
            for (Block block : matchingBlocks()) {
                String tag = "";
                if (i < block.i() && j < block.j()) {
                    tag = "replace";
                } else if (i < block.i()) {
                    tag = "delete";
                } else if (j < block.j()) {
                    tag = "insert";
                }
                if (!tag.isEmpty()) {
                    answer.add(new Opcode(tag, i, block.i(), j, block.j()));
                }
                i = block.i() + block.size();
                j = block.j() + block.size();
                if (block.size() > 0) {
                    answer.add(new Opcode("equal", block.i(), i, block.j(), j));
                }
            }
            return answer;
        }
    }
}
