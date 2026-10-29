package com.verifone.cat.publish;

import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.collect.CaseResult.Pulled;

/** What a publish did, or would do: the Python's {@code petrotest.publish.Publication}. */
public final class Publication {

    public final String branch;
    public final String parentRef;
    public final String parent;
    public final String catCommit;
    public final String fetchError;
    public final boolean dryRun;
    /** Empty: a dry run, or nothing differed from {@link #parent}. */
    public String commit = "";
    public final List<Pulled> written = new ArrayList<>();
    public final List<Pulled> unchanged = new ArrayList<>();
    public final List<Pulled> refused = new ArrayList<>();
    /** CAT-owned paths {@code prune} removed: a layout CAT no longer produces. */
    public final List<String> removed = new ArrayList<>();
    /** PATS's own {@code -Tlog.xml} references replaced by CAT's, only with {@code replacePatsTlogs}. */
    public final List<Pulled> replaced = new ArrayList<>();
    /** The commit went onto origin's {@link #branch} ({@code push}), not a local branch. */
    public boolean pushed;

    Publication(String branch, String parentRef, String parent, String catCommit, String fetchError, boolean dryRun) {
        this.branch = branch;
        this.parentRef = parentRef;
        this.parent = parent;
        this.catCommit = catCommit;
        this.fetchError = fetchError == null ? "" : fetchError;
        this.dryRun = dryRun;
    }

    private static String head(String sha) {
        return sha.substring(0, Math.min(11, sha.length()));
    }

    /** The report, as the Python prints it; {@code petroapps} names the checkout in the push hint. */
    public List<String> lines(String petroapps) {
        List<String> out = new ArrayList<>();
        if (!fetchError.isEmpty()) {
            out.add("WARNING: git fetch origin failed, so " + parentRef + " may be stale: " + fetchError);
        }
        out.add("CAT " + head(catCommit) + " -> petroapps " + branch + " on " + parentRef);
        for (Pulled p : refused) {
            out.add("  refused   " + p.source() + ": " + p.reason());
        }
        for (Pulled p : written) {
            out.add("  " + (dryRun ? "would write" : "wrote") + "  " + p.dest());
        }
        String verb = dryRun ? "would replace PATS's" : "replaced PATS's";
        for (Pulled p : replaced) {
            out.add("  " + verb + " " + p.dest());
        }
        verb = dryRun ? "would remove" : "removed";
        for (String path : removed) {
            out.add("  " + verb + " " + path);
        }
        out.add(written.size() + " file(s) " + (dryRun ? "to write" : "written") + ", " + replaced.size()
                + " PATS TLog reference(s) replaced, " + unchanged.size() + " unchanged, " + refused.size()
                + " refused, " + removed.size() + " " + (dryRun ? "to remove" : "removed"));
        if (!commit.isEmpty() && pushed) {
            out.add("commit " + head(commit) + " pushed onto origin/" + branch);
        } else if (!commit.isEmpty()) {
            out.add("commit " + head(commit) + " on local branch " + branch + ". Not pushed:");
            out.add("  git -C " + petroapps + " push origin " + branch);
        }
        return out;
    }

    public List<String> lines() {
        return lines("<petroapps>");
    }
}
