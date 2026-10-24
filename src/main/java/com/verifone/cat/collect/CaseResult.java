package com.verifone.cat.collect;

import java.util.ArrayList;
import java.util.List;

/**
 * One plan case's inventory: the PATS files its folder holds, what is missing, the tree collisions, and what was pulled
 * into CAT's suite (its translated test and that test's baselines).
 *
 * <p>The Python CAT's {@code petrotest.collect.CaseResult}.
 */
public final class CaseResult {

    /** One file {@code CICDScript.bat} would copy: its rule, the git path it would copy from, and its destination. */
    public record Copy(String rule, String source, String dest) {}

    /** One case-insensitive name held by both trees (A1). */
    public record Collision(String name, List<String> paths, boolean sameContent) {}

    /** A translated {@code .py} test or one of its baselines: written to {@code dest}, or refused (dest null). */
    public record Pulled(String source, String dest, String reason) {}

    /** The refusal reason when {@code tests/} already holds a test of that name (A5). */
    public static final String SHADOWED = "already exists under tests/";

    public final String key;
    /** The git trees holding the case folder: {@code testcases}, {@code TestCases}, or both. */
    public final List<String> trees;
    public final List<Copy> copies = new ArrayList<>();
    public final List<String> missing = new ArrayList<>();
    public final List<Collision> collisions = new ArrayList<>();
    public final List<Pulled> pulled = new ArrayList<>();
    /** The pulled tests' snapshot baselines (A3). */
    public final List<Pulled> baselines = new ArrayList<>();

    public CaseResult(String key, List<String> trees) {
        this.key = key;
        this.trees = List.copyOf(trees);
    }

    /**
     * The pulled test that runs this case, or {@code null} (Not Run). Any pulled {@code .py}: {@code <ID>-TestCase.py}
     * since the 5.4 layout, or {@code .feature} (5.6).
     */
    public String node() {
        for (Pulled p : pulled) {
            if (p.dest() != null && Selection.isCatTest(p.dest())) {
                return p.dest();
            }
        }
        return null;
    }

    /** Nothing pulled because {@code tests/} already holds a test of that name (A5): that copy runs instead. */
    public boolean shadowed() {
        return node() == null && pulled.stream().anyMatch(p -> p.reason().contains(SHADOWED));
    }

    /** A translated test was pulled, or a dry run found one it would pull. */
    public boolean hasTest() {
        for (Pulled p : pulled) {
            if (Selection.isCatTest(p.source()) && (p.dest() != null || Collect.DRY_RUN.equals(p.reason()))) {
                return true;
            }
        }
        return false;
    }
}
