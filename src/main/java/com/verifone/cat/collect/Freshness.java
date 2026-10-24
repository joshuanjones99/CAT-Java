package com.verifone.cat.collect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Whether the petroapps working tree is current with {@code origin/<branch>}.
 *
 * <p>CAT reads a plan's tests from {@code origin/<branch>} in git objects, but other readers take the working tree as it
 * stands: the TLog rule file, the receipt rules and the {@code configurations/} a case imports. A checkout behind its
 * remote therefore runs today's tests against old rules. This is the check; {@link #check} names the fix and, when
 * asked, applies it the way the PATS {@code CICDScript.bat} does, in ways git itself refuses to clobber.
 *
 * <p>The Python CAT's {@code petrotest.collect.Freshness} and {@code freshness}.
 *
 * @param ok     {@code true} when the checkout is at or ahead of {@code origin/<branch>}
 * @param detail one line for the operator: where the checkout is, and the fix when it is not
 */
public record Freshness(boolean ok, String detail) {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(300);
    private static final Map<String, String> NO_PROMPT = GitUtil.NO_PROMPT;

    /**
     * Is {@code repo}'s {@code HEAD} at or ahead of {@code origin/<branch>}? Empty when {@code repo} is not a checkout.
     *
     * @param fetchError {@code null} fetches first; a string is the outcome of a fetch the caller already made this
     *                   run, {@code ""} for success, so a run start does not fetch twice
     * @param update     {@code false} never touches the working tree, the index or {@code HEAD}; {@code true} checks
     *                   out and fast-forwards, and never stashes, resets, forces or merges a branch of its own
     */
    public static Optional<Freshness> check(Path repo, String branch, String fetchError, boolean update) {
        if (!Files.exists(repo.resolve(".git"))) {
            return Optional.empty();
        }
        String error = fetchError == null ? Collect.fetchBranch(repo, branch) : fetchError;
        if (error.contains("fatal:")) { // git's own reason, not the whole command line before it
            error = error.substring(error.lastIndexOf("fatal:"));
        }
        if (!error.isEmpty()) {
            return Optional.of(new Freshness(false, "cannot fetch origin/" + branch + ", so " + repo
                    + " cannot be shown current: " + error + " (sign in: press s in CAT)"));
        }
        String head;
        String where;
        int behind;
        try {
            head = text(GitUtil.git(repo, GIT_TIMEOUT, null, "rev-parse", "--abbrev-ref", "HEAD"));
            where = text(GitUtil.git(repo, GIT_TIMEOUT, null, "log", "-1", "--format=%h %cs", "HEAD"));
            behind = Integer.parseInt(text(GitUtil.git(repo, GIT_TIMEOUT, null, "rev-list", "--count",
                    "HEAD..origin/" + branch)));
        } catch (CollectException | NumberFormatException e) {
            return Optional.of(new Freshness(false,
                    "cannot compare " + repo + " with origin/" + branch + ": " + e.getMessage()));
        }
        if (behind == 0) {
            return Optional.of(new Freshness(true, head + " at " + where + ", up to date with origin/" + branch));
        }
        String fix = head.equals(branch)
                ? "git -C " + repo + " pull"
                : "git -C " + repo + " checkout " + branch + ", then git -C " + repo + " pull";
        String stale = repo + " is on " + head + " at " + where + ", " + behind + " commit(s) behind origin/" + branch;
        if (!update) {
            return Optional.of(new Freshness(false, stale + ": " + fix));
        }
        try {
            if (!head.equals(branch)) {
                GitUtil.git(repo, GIT_TIMEOUT, NO_PROMPT, "-c", "gc.auto=0", "-c", "maintenance.auto=false",
                        "checkout", "--quiet", branch);
            }
            GitUtil.git(repo, GIT_TIMEOUT, NO_PROMPT, "-c", "gc.auto=0", "-c", "maintenance.auto=false",
                    "merge", "--ff-only", "--quiet", "origin/" + branch);
            String now = text(GitUtil.git(repo, GIT_TIMEOUT, null, "log", "-1", "--format=%h %cs", "HEAD"));
            return Optional.of(new Freshness(true,
                    branch + " at " + now + ", brought up to date from " + head + " at " + where));
        } catch (CollectException e) {
            return Optional.of(new Freshness(false,
                    stale + ", and git refused to bring it current: " + e.getMessage() + "; " + fix));
        }
    }

    private static String text(byte[] stdout) {
        return new String(stdout, StandardCharsets.UTF_8).strip();
    }
}
