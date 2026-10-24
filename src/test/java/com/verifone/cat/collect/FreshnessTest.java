package com.verifone.cat.collect;

import static com.verifone.cat.collect.PetroappsFixture.CASE;
import static com.verifone.cat.collect.PetroappsFixture.IDENTITY;
import static com.verifone.cat.collect.PetroappsFixture.commit;
import static com.verifone.cat.collect.PetroappsFixture.files;
import static com.verifone.cat.collect.PetroappsFixture.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Freshness} against real git: a bare remote and a dirty checkout, both under {@code @TempDir}. The Python CAT's
 * {@code TestFreshness} and {@code TestFreshnessUpdate}. The claim under test is what git does to the operator's files,
 * so nothing is faked.
 */
class FreshnessTest {

    @BeforeAll
    static void needsGit() {
        Assumptions.assumeTrue(PetroappsFixture.gitAvailable(), "git is not on PATH");
    }

    @TempDir
    Path tmp;

    /** A remote with one commit on master, and a checkout made current with it and left dirty. */
    private record Current(Path remote, Path local, String sha) {}

    private Current current() throws IOException {
        Path[] pair = PetroappsFixture.remoteAndCheckout(tmp);
        String sha = commit(pair[0], "refs/heads/master", files(CASE + "/PETROCORE-1/a.py", "a"));
        PetroappsFixture.track(pair[1], "master");
        return new Current(pair[0], pair[1], sha);
    }

    /** A child of {@code parent} holding {@code files}, as the remote's new master. */
    private static String newer(Path remote, String parent, Map<String, byte[]> files) {
        String tree = git(remote, "rev-parse", commit(remote, "refs/heads/scratch", files) + "^{tree}");
        String child = git(remote, withIdentity("commit-tree", "-p", parent, "-m", "newer", tree));
        git(remote, "update-ref", "refs/heads/master", child);
        return child;
    }

    private static String[] withIdentity(String... args) {
        String[] all = new String[IDENTITY.length + args.length];
        System.arraycopy(IDENTITY, 0, all, 0, IDENTITY.length);
        System.arraycopy(args, 0, all, IDENTITY.length, args.length);
        return all;
    }

    private static String text(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Freshness check(Path repo, String fetchError, boolean update) {
        Optional<Freshness> result = Freshness.check(repo, "master", fetchError, update);
        assertTrue(result.isPresent());
        return result.get();
    }

    @Test
    void aCheckoutAtOriginIsCurrent() throws IOException {
        Current c = current();
        Freshness result = check(c.local(), null, false);
        assertTrue(result.ok(), result.detail());
        assertTrue(result.detail().contains("up to date with origin/master"));
    }

    @Test
    void aCheckoutBehindOriginNamesTheCountAndThePull() throws IOException {
        Current c = current();
        newer(c.remote(), c.sha(), files(CASE + "/PETROCORE-1/a.py", "a"));
        Freshness result = check(c.local(), null, false);
        assertFalse(result.ok());
        assertTrue(result.detail().contains("1 commit(s) behind origin/master"));
        assertTrue(result.detail().endsWith("git -C " + c.local() + " pull"));
    }

    @Test
    void aCheckoutOnAnotherBranchSaysToSwitch() throws IOException {
        Path[] pair = PetroappsFixture.remoteAndCheckout(tmp);
        commit(pair[0], "refs/heads/master", files(CASE + "/PETROCORE-1/a.py", "a"));
        git(pair[1], "branch", "-m", "master056.00");
        Freshness result = check(pair[1], null, false);
        assertFalse(result.ok());
        assertTrue(result.detail().contains("is on master056.00"));
        assertTrue(result.detail().contains("git -C " + pair[1] + " checkout master"));
    }

    @Test
    void aFailedFetchIsNotCurrent() throws IOException {
        Current c = current();
        git(c.local(), "remote", "set-url", "origin", tmp.resolve("offline.git").toString());
        Freshness result = check(c.local(), null, false);
        assertFalse(result.ok());
        assertTrue(result.detail().startsWith("cannot fetch origin/master"));
    }

    @Test
    void aFetchTheRunAlreadyMadeIsNotRepeated() throws IOException {
        Current c = current();
        git(c.local(), "remote", "set-url", "origin", tmp.resolve("offline.git").toString());
        Freshness result = check(c.local(), "", false);
        assertTrue(result.ok(), result.detail());
    }

    @Test
    void noCheckoutIsEmpty() {
        assertTrue(Freshness.check(tmp.resolve("nothing"), "master", null, false).isEmpty());
    }

    @Test
    void behindOnTheBranchIsPulledAndTheDirtKept() throws IOException {
        Current c = current();
        String child = newer(c.remote(), c.sha(), files(
                CASE + "/PETROCORE-1/a.py", "a", CASE + "/PETROCORE-1/b.py", "b"));
        Freshness result = check(c.local(), null, true);
        assertTrue(result.ok(), result.detail());
        assertTrue(result.detail().contains("brought up to date"));
        assertEquals(child, git(c.local(), "rev-parse", "HEAD"));
        assertEquals("b", text(c.local().resolve(CASE).resolve("PETROCORE-1").resolve("b.py")));
        assertEquals("the operator's\n", text(c.local().resolve("untracked.txt")));
        assertEquals("modified, not committed\n", text(c.local().resolve("tracked.txt")));
    }

    @Test
    void anotherBranchIsSwitchedThenCurrent() throws IOException {
        Path[] pair = PetroappsFixture.remoteAndCheckout(tmp);
        commit(pair[0], "refs/heads/master", files(CASE + "/PETROCORE-1/a.py", "a"));
        git(pair[1], "checkout", "--", "tracked.txt"); // clean, bar the untracked file
        git(pair[1], "branch", "-m", "master056.00");
        Freshness result = check(pair[1], null, true);
        assertTrue(result.ok(), result.detail());
        assertEquals("master", git(pair[1], "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals(git(pair[1], "rev-parse", "origin/master"), git(pair[1], "rev-parse", "HEAD"));
        assertEquals("the operator's\n", text(pair[1].resolve("untracked.txt")));
    }

    @Test
    void aCheckoutThatWouldLoseChangesIsRefusedNotForced() throws IOException {
        Path[] pair = PetroappsFixture.remoteAndCheckout(tmp);
        commit(pair[0], "refs/heads/master", files(CASE + "/PETROCORE-1/a.py", "a"));
        git(pair[1], "branch", "-m", "master056.00"); // tracked.txt modified, absent on master
        Freshness result = check(pair[1], null, true);
        assertFalse(result.ok());
        assertTrue(result.detail().contains("git refused to bring it current"));
        assertEquals("master056.00", git(pair[1], "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals("modified, not committed\n", text(pair[1].resolve("tracked.txt")));
    }

    @Test
    void aBranchWithItsOwnCommitsIsNotMerged() throws IOException {
        Current c = current();
        git(c.local(), "add", "-A");
        git(c.local(), "-c", "user.name=f", "-c", "user.email=f@example.invalid", "commit", "-qm", "own");
        String mine = git(c.local(), "rev-parse", "HEAD");
        newer(c.remote(), c.sha(), files(CASE + "/PETROCORE-1/a.py", "a", CASE + "/PETROCORE-1/b.py", "b"));
        Freshness result = check(c.local(), null, true);
        assertFalse(result.ok());
        assertTrue(result.detail().contains("git refused"));
        assertEquals(mine, git(c.local(), "rev-parse", "HEAD"));
    }
}
