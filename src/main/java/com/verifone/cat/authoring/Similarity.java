package com.verifone.cat.authoring;

/**
 * {@code difflib.SequenceMatcher.ratio()} with no junk heuristics: twice the matched characters over both lengths, the
 * matched characters found as {@code difflib} finds them (the longest common block first, then recursively either side of
 * it). Port of the part of {@code difflib} the checker's suggestions need.
 *
 * <p>Not ported: {@code autojunk}, which the Python switches on only for a sequence of 200 characters or more. No phrase
 * or line the checker suggests for is that long, so the scores agree in practice.
 */
final class Similarity {

    private Similarity() {}

    /** {@code SequenceMatcher(a=a, b=b).ratio()}. */
    static double ratio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) {
            return 1.0;
        }
        return 2.0 * matched(a, 0, a.length(), b, 0, b.length()) / total;
    }

    private static int matched(String a, int alo, int ahi, String b, int blo, int bhi) {
        int[] best = longest(a, alo, ahi, b, blo, bhi);
        int i = best[0];
        int j = best[1];
        int k = best[2];
        if (k == 0) {
            return 0;
        }
        return k + matched(a, alo, i, b, blo, j) + matched(a, i + k, ahi, b, j + k, bhi);
    }

    /** The first longest common block, as {@code find_longest_match}: earliest in {@code a}, then earliest in {@code b}. */
    private static int[] longest(String a, int alo, int ahi, String b, int blo, int bhi) {
        int bestI = alo;
        int bestJ = blo;
        int bestK = 0;
        int[] previous = new int[bhi + 1];
        for (int i = alo; i < ahi; i++) {
            int[] current = new int[bhi + 1];
            for (int j = blo; j < bhi; j++) {
                if (a.charAt(i) == b.charAt(j)) {
                    int k = previous[j] + 1;
                    current[j + 1] = k;
                    if (k > bestK) {
                        bestI = i - k + 1;
                        bestJ = j - k + 1;
                        bestK = k;
                    }
                }
            }
            previous = current;
        }
        return new int[] {bestI, bestJ, bestK};
    }
}
