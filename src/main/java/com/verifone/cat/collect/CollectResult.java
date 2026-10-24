package com.verifone.cat.collect;

import java.util.ArrayList;
import java.util.List;

/**
 * A finished collection: which commit was read, the shared assets, every case's inventory, and whether the fetch
 * before it succeeded. {@link #lines()} is the report, PATS's echo lines included.
 *
 * <p>The Python CAT's {@code petrotest.collect.Collection}.
 */
public final class CollectResult {

    /** One shared asset: the file count PATS's {@code xcopy} would find (0 = "does not exist"). */
    public record Asset(String source, String dest, int files) {}

    public final String repo;
    public final String revision;
    /** The ref actually read: {@code origin/<revision>}, else {@code <revision>}. */
    public final String ref;
    public final String commit;
    public final String dist;
    public final List<CaseResult> cases;
    public final List<Asset> assets;
    /** Where the cases were written, as shown to the operator; {@code null} for a dry run. */
    public final String outDir;
    /** {@code null} when no fetch was asked for; else whether {@code git fetch} succeeded. */
    public final Boolean fetched;
    public final String fetchError;

    public CollectResult(String repo, String revision, String ref, String commit, String dist, List<CaseResult> cases,
            List<Asset> assets, String outDir, Boolean fetched, String fetchError) {
        this.repo = repo;
        this.revision = revision;
        this.ref = ref;
        this.commit = commit;
        this.dist = dist;
        this.cases = List.copyOf(cases);
        this.assets = List.copyOf(assets);
        this.outDir = outDir;
        this.fetched = fetched;
        this.fetchError = fetchError;
    }

    /** The report, one string per line. A failed fetch is the first line and starts {@code WARNING:}. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        if (Boolean.FALSE.equals(fetched)) {
            out.add("WARNING: git fetch origin " + revision + " failed, so this is NOT the remote's latest: "
                    + fetchError + ". Using the local " + ref + " (" + commit.substring(0, 11) + ") instead.");
        }
        String how = fetched == null ? "not fetched" : fetched ? "fetched from origin" : "fetch failed";
        out.add("petroapps " + repo + " at " + ref + " (" + commit.substring(0, 11) + ", " + how + "), dist " + dist);
        out.add("shared assets (CICDScript.bat lines 400-441):");
        for (Asset asset : assets) {
            String state = asset.files() > 0 ? asset.files() + " files" : "does not exist";
            out.add("  " + asset.source() + " -> " + asset.dest() + ": " + state);
        }
        for (CaseResult c : cases) {
            String trees = c.trees.isEmpty() ? "no case folder" : String.join("+", c.trees);
            out.add(c.key + " [" + trees + "]: " + c.copies.size() + " PATS files");
            for (String line : c.missing) {
                out.add(line + " does not exist");
            }
            for (CaseResult.Collision col : c.collisions) {
                String same = col.sameContent() ? "same content" : "DIFFERENT content";
                out.add("  collision: " + String.join(" vs ", col.paths()) + " (" + same + ")");
            }
            for (CaseResult.Pulled p : c.pulled) {
                out.add(p.dest() != null ? "  pulled " + p.source() + " -> " + p.dest()
                        : "  not pulled " + p.source() + ": " + p.reason());
            }
            for (CaseResult.Pulled b : c.baselines) {
                out.add(b.dest() != null ? "  pulled baseline " + b.source() + " -> " + b.dest()
                        : "  not pulled baseline " + b.source() + ": " + b.reason());
            }
            if (c.shadowed()) {
                out.add("  " + c.key + " runs the copy already under tests/, not petroapps'");
            } else if (c.node() == null) {
                out.add("  " + c.key + " has no translated .py test: Not Run");
            }
        }
        long pulledCount = cases.stream().filter(c -> c.node() != null).count();
        long local = cases.stream().filter(CaseResult::shadowed).count();
        long baselines = cases.stream().flatMap(c -> c.baselines.stream()).filter(b -> b.dest() != null).count();
        out.add(cases.size() + " cases: " + cases.stream().mapToInt(c -> c.copies.size()).sum()
                + " PATS files inventoried, " + cases.stream().mapToInt(c -> c.missing.size()).sum()
                + " 'does not exist' lines, " + cases.stream().mapToInt(c -> c.collisions.size()).sum()
                + " tree collisions, " + pulledCount + " with a pulled .py test, " + local
                + " running the copy under tests/, " + (cases.size() - pulledCount - local) + " Not Run, "
                + baselines + " baselines pulled");
        return out;
    }
}
