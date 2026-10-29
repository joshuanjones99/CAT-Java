package com.verifone.cat.publish;

import static com.verifone.cat.collect.PetroappsFixture.BDD;
import static com.verifone.cat.collect.PetroappsFixture.CASE;
import static com.verifone.cat.collect.PetroappsFixture.commit;
import static com.verifone.cat.collect.PetroappsFixture.files;
import static com.verifone.cat.collect.PetroappsFixture.git;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.collect.CaseResult;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.CollectOptions;
import com.verifone.cat.collect.CollectResult;
import com.verifone.cat.collect.PetroappsFixture;

/**
 * {@code cat-java publish} against throwaway git repositories built here: a bare "remote" and a dirty petroapps
 * checkout ({@link PetroappsFixture}), and a small committed CAT checkout. Port of the Python's
 * {@code tests/unit/test_publish.py}. Evidence about this repository's logic, not about petroapps: no real petroapps is
 * read or written, and nothing is pushed anywhere but the throwaway remote.
 */
class PublisherTest {

    static final String SUITE = "tests/suites/charity";
    static final String TEST_1 = "def test_one(snapshot):\n    pass\n";
    static final String FEATURE_1 = "@PETROCORE-1\nFeature: one\n\n  Scenario: one\n    Given the register is idle\n";

    @TempDir
    Path tmp;

    private Path remote;
    private Path petroapps;
    private Path catRoot;

    @BeforeEach
    void repos() throws Exception {
        assumeTrue(PetroappsFixture.gitAvailable(), "git is not on PATH");
        Path[] pair = PetroappsFixture.remoteAndCheckout(tmp);
        remote = pair[0];
        petroapps = pair[1];
        commit(remote, "refs/heads/master", files(
                CASE + "/PETROCORE-1/PETROCORE-1-TestCase.xml", "<case/>",
                BDD + "/PETROCORE-2/PETROCORE-2-TestCase.scenario", "Feature: two",
                CASE + "/PETROCORE-4/PETROCORE-4-TestCase.xml", "<case/>"));
        git(petroapps, "config", "user.name", "fixture");
        git(petroapps, "config", "user.email", "fixture@example.invalid");
        catRoot = tmp.resolve("cat");
        Path suite = catRoot.resolve(SUITE);
        Files.createDirectories(suite.resolve("configurations"));
        Files.writeString(suite.resolve("PETROCORE-1-TestCase.py"), TEST_1);
        Files.writeString(suite.resolve("PETROCORE-1-CATReceipt.txt"), "R\n");
        Files.writeString(suite.resolve("PETROCORE-2-TestCase.py"), "def test_two():\n    pass\n");
        Files.writeString(suite.resolve("PETROCORE-3-TestCase.py"), "# petroapps has no PETROCORE-3\n");
        Files.writeString(suite.resolve("PETROCORE-4-TestCase.py"), "HERE = __file__\n");
        Files.writeString(suite.resolve("configurations").resolve("a.xml"), "<a/>");
        commitAll(catRoot, "suite", true);
    }

    private static void commitAll(Path root, String message, boolean init) {
        if (init) {
            git(root.getParent(), "init", "-q", root.toString());
            git(root, "config", "core.autocrlf", "false");
        }
        git(root, "add", ".");
        git(root, "-c", "user.name=f", "-c", "user.email=f@example.invalid", "commit", "-qm", message);
    }

    private void recommit(String name, String data, String message) throws Exception {
        Files.writeString(catRoot.resolve(SUITE).resolve(name), data);
        commitAll(catRoot, message, false);
    }

    private Publisher.Options options(String base, String branch, boolean prune, boolean replace, boolean push,
            Set<String> only, boolean dryRun) {
        return new Publisher.Options(base, branch, "", catRoot, true, dryRun, prune, replace, push, only,
                tmp.resolve("tmp"));
    }

    private Publication publish(String base, String branch) {
        return Publisher.publish(SUITE, petroapps, options(base, branch, false, false, false, null, false));
    }

    private List<String> state(Path repo) {
        return List.of(git(repo, "status", "--porcelain"), git(repo, "rev-parse", "HEAD"),
                git(repo, "symbolic-ref", "HEAD"));
    }

    private List<String> tree(String ref) {
        return List.of(git(petroapps, "ls-tree", "-r", "--name-only", ref).split("\n"));
    }

    private static String name(String path) {
        return path == null ? null : Path.of(path).getFileName().toString();
    }

    @Test
    void publishCommitsBesideTheCaseAndCollectPullsItBack() throws Exception {
        List<String> before = state(petroapps);
        Publication result = publish("master", "cat/tests");

        assertEquals(before, state(petroapps), "working tree, index and HEAD untouched");
        List<String> tree = tree("cat/tests");
        assertTrue(tree.contains(CASE + "/PETROCORE-1/PETROCORE-1-TestCase.py"), tree.toString());
        assertTrue(tree.contains(CASE + "/PETROCORE-1/PETROCORE-1-CATReceipt.txt"), tree.toString());
        assertTrue(tree.contains(BDD + "/PETROCORE-2/PETROCORE-2-TestCase.py"), "the tree the case lives in");
        assertTrue(tree.contains(CASE + "/PETROCORE-1/PETROCORE-1-TestCase.xml"), "built on origin/master");
        assertFalse(tree.stream().anyMatch(p -> p.contains("configurations") || p.contains("PETROCORE-3")
                || p.contains("PETROCORE-4/PETROCORE-4-TestCase.py")), tree.toString());
        assertEquals(List.of("PETROCORE-3-TestCase.py petroapps", "PETROCORE-4-TestCase.py reads"),
                result.refused.stream().map(p -> name(p.source()) + " " + p.reason().split(" ")[0]).toList());
        assertTrue(git(petroapps, "log", "-1", "--format=%B", "cat/tests").contains(git(catRoot, "rev-parse", "HEAD")));
        assertFalse(git(petroapps, "ls-remote", "--heads", "origin").contains("cat/tests"), "never pushed");
        assertTrue(String.join("\n", result.lines(petroapps.toString()))
                .contains("git -C " + petroapps + " push origin cat/tests"));

        Path out = tmp.resolve("pulled");
        CollectResult pulled = Collect.collect(List.of("PETROCORE-1"), petroapps,
                new CollectOptions("cat/tests", "", out, tmp, false));
        assertEquals(TEST_1, Files.readString(out.resolve("PETROCORE-1-TestCase.py")));
        assertEquals("R\n", Files.readString(out.resolve("PETROCORE-1-CATReceipt.txt")));
        assertTrue(pulled.cases.get(0).hasTest());
    }

    @Test
    void publishRefusesToOverwritePatsOwnTlogReferenceUnlessReconciled() throws Exception {
        String tlog = CASE + "/PETROCORE-1/PETROCORE-1-Tlog.xml";
        commit(remote, "refs/heads/withtlog", files(
                CASE + "/PETROCORE-1/PETROCORE-1-TestCase.xml", "<case/>",
                BDD + "/PETROCORE-2/PETROCORE-2-TestCase.scenario", "Feature: two",
                CASE + "/PETROCORE-4/PETROCORE-4-TestCase.xml", "<case/>",
                tlog, "<pats/>\n"));
        git(petroapps, "fetch", "-q", "origin");

        recommit("PETROCORE-1-Tlog.xml", "<pats/>\n", "same tlog");
        Publication same = publish("withtlog", "cat/same");
        assertTrue(same.unchanged.stream().anyMatch(p -> "PETROCORE-1-Tlog.xml".equals(name(p.dest()))));
        assertFalse(same.refused.stream().anyMatch(r -> r.source().contains("Tlog")));

        recommit("PETROCORE-1-Tlog.xml", "<cat-rebaselined/>\n", "changed tlog");
        Publication changed = publish("withtlog", "cat/changed");
        List<CaseResult.Pulled> refused = changed.refused.stream().filter(r -> r.source().contains("Tlog")).toList();
        assertEquals(1, refused.size());
        assertTrue(refused.get(0).reason().contains("PATS's reference too"));
        assertFalse(changed.written.stream().anyMatch(w -> w.dest().contains("Tlog")));

        Publication replaced = Publisher.publish(SUITE, petroapps,
                options("withtlog", "cat/replaced", false, true, false, null, false));
        assertEquals(List.of("PETROCORE-1-Tlog.xml"), replaced.replaced.stream().map(r -> name(r.dest())).toList());
        assertEquals("<cat-rebaselined/>", git(petroapps, "show", "cat/replaced:" + tlog));
    }

    @Test
    void aTlogThatDiffersOnlyInLineEndingsIsTheSameReference() throws Exception {
        String tlog = CASE + "/PETROCORE-1/PETROCORE-1-Tlog.xml";
        commit(remote, "refs/heads/crlf", files(CASE + "/PETROCORE-1/PETROCORE-1-TestCase.xml", "<case/>",
                tlog, "<tlog>\r\n  <a/>\r\n</tlog>\r\n"));
        git(petroapps, "fetch", "-q", "origin");
        recommit("PETROCORE-1-Tlog.xml", "<tlog>\n  <a/>\n</tlog>\n", "lf tlog");
        Publication same = publish("crlf", "cat/crlf");
        assertTrue(same.unchanged.stream().anyMatch(p -> "PETROCORE-1-Tlog.xml".equals(name(p.dest()))));
        assertFalse(same.refused.stream().anyMatch(r -> r.source().contains("Tlog")));
        recommit("PETROCORE-1-Tlog.xml", "<tlog>\n  <b/>\n</tlog>\n", "real change");
        Publication changed = publish("crlf", "cat/crlf2");
        assertTrue(changed.refused.stream().anyMatch(r -> r.source().contains("Tlog")
                && r.reason().contains("PATS's reference too")));
    }

    @Test
    void pruneRemovesTheLayoutCatNoLongerProducesAndNothingOfPats() throws Exception {
        String c = CASE + "/PETROCORE-1";
        commit(remote, "refs/heads/old", files(
                c + "/PETROCORE-1-TestCase.xml", "<case/>",
                c + "/PETROCORE-1-Receipt.txt", "pats receipt",
                c + "/PETROCORE-1-Rule.txt", "12,13",
                c + "/PETROCORE-1-Tlog.xml", "<tlog/>",
                c + "/PETROCORE-1-FDCRequest.xml", "<fdc/>",
                c + "/test_petrocore_1.py", "# old name\n",
                c + "/__snapshots__/test_petrocore_1/test_one.receipt.txt", "old\n",
                c + "/__snapshots__/test_petrocore_1/test_one.tlog.xml", "<old/>\n",
                CASE + "/PETROCORE-9/test_petrocore_9.py", "# not ours today\n",
                CASE + "/PETROCORE-9/PETROCORE-9-TestCase.xml", "<case/>"));
        git(petroapps, "fetch", "-q", "origin");

        Publication result = Publisher.publish(SUITE, petroapps,
                options("old", "cat/pruned", true, false, false, null, false));

        assertEquals(List.of("test_one.receipt.txt", "test_one.tlog.xml", "test_petrocore_1.py"),
                result.removed.stream().map(PublisherTest::name).sorted().toList());
        List<String> tree = tree("cat/pruned");
        for (String kept : List.of("TestCase.xml", "Receipt.txt", "Rule.txt", "Tlog.xml", "FDCRequest.xml")) {
            assertTrue(tree.contains(c + "/PETROCORE-1-" + kept), kept);
        }
        assertFalse(tree.stream().anyMatch(t -> t.contains("__snapshots__") || t.contains("test_petrocore_1.py")));
        assertTrue(tree.contains(c + "/PETROCORE-1-TestCase.py"));
        assertTrue(tree.contains(CASE + "/PETROCORE-9/test_petrocore_9.py"), "a folder not written into is left alone");
        assertEquals(List.of(), publish("master", "cat/default").removed, "prune is off by default");
    }

    @Test
    void aSecondPublishBuildsOnTheFirstAndWritesOnlyWhatChanged() throws Exception {
        Publication first = publish("master", "cat/tests");
        Publication again = publish("master", "cat/tests");
        assertEquals("", again.commit);
        assertEquals(List.of(), again.written);
        assertEquals(3, again.unchanged.size());

        Files.writeString(catRoot.resolve(SUITE).resolve("PETROCORE-2-TestCase.py"),
                "def test_two():\n    assert True\n");
        PublishException dirty = assertThrows(PublishException.class, () -> publish("master", "cat/tests"));
        assertTrue(dirty.getMessage().contains("uncommitted"));
        git(catRoot, "-c", "user.name=f", "-c", "user.email=f@example.invalid", "commit", "-qam", "2");
        Publication third = publish("master", "cat/tests");
        assertEquals(List.of("PETROCORE-2-TestCase.py"), third.written.stream().map(p -> name(p.dest())).toList());
        assertEquals(first.commit, git(petroapps, "rev-parse", "cat/tests^"));
    }

    @Test
    void anAuthoredFeaturePublishesPrunesItsPyAndPullsBackAsTheCasesTest() throws Exception {
        Path root = tmp.resolve("cat-features");
        Path suite = root.resolve(SUITE);
        Files.createDirectories(suite);
        Files.writeString(suite.resolve("PETROCORE-1-TestCase.feature"), FEATURE_1);
        Files.writeString(suite.resolve("PETROCORE-1-CATReceipt.txt"), "R\n");
        Files.writeString(suite.resolve("PETROCORE-4-TestCase.feature"),
                FEATURE_1.replace("-1", "-4") + "    # __file__\n");
        commitAll(root, "suite", true);
        String c = CASE + "/PETROCORE-1";
        commit(remote, "refs/heads/old", files(c + "/PETROCORE-1-TestCase.xml", "<case/>",
                c + "/PETROCORE-1-TestCase.py", "# published before the .feature\n",
                CASE + "/PETROCORE-4/PETROCORE-4-TestCase.xml", "<case/>"));
        git(petroapps, "fetch", "-q", "origin");

        Publication result = Publisher.publish(SUITE, petroapps, new Publisher.Options("old", "cat/features", "", root,
                true, false, true, false, false, null, tmp.resolve("tmp")));

        assertEquals(List.of(), result.refused);
        assertEquals(List.of("PETROCORE-1-TestCase.py"), result.removed.stream().map(PublisherTest::name).toList());
        List<String> tree = tree("cat/features");
        assertTrue(tree.contains(c + "/PETROCORE-1-TestCase.feature"));
        assertTrue(tree.contains(c + "/PETROCORE-1-CATReceipt.txt"));
        assertTrue(tree.contains(c + "/PETROCORE-1-TestCase.xml"));
        assertTrue(tree.contains(CASE + "/PETROCORE-4/PETROCORE-4-TestCase.feature"));
        Path out = tmp.resolve("pulled");
        CollectResult pulled = Collect.collect(List.of("PETROCORE-1"), petroapps,
                new CollectOptions("cat/features", "", out, tmp, false));
        assertTrue(pulled.cases.get(0).node().endsWith("PETROCORE-1-TestCase.feature"));
        assertEquals(FEATURE_1, Files.readString(out.resolve("PETROCORE-1-TestCase.feature")));
    }

    @Test
    void pushLandsOneCommitOnTheExistingBranchAndWritesNoOther() throws Exception {
        String beforeRemote = git(remote, "rev-parse", "refs/heads/master");
        String beforeLocal = git(petroapps, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads");
        List<String> beforeState = state(petroapps);

        Publication result = Publisher.publish(SUITE, petroapps,
                options("master", "master", false, false, true, null, false));

        assertTrue(result.pushed && !result.commit.isEmpty());
        assertEquals(result.commit, git(remote, "rev-parse", "refs/heads/master"));
        assertEquals(beforeRemote, git(remote, "rev-parse", result.commit + "^"), "a fast-forward");
        assertEquals("refs/heads/master", git(remote, "for-each-ref", "--format=%(refname)", "refs/heads"));
        assertEquals(beforeLocal, git(petroapps, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads"));
        assertEquals(beforeState, state(petroapps));
        assertTrue(String.join("\n", result.lines()).contains("pushed onto origin/master"));
    }

    @Test
    void pushNeverCreatesABranchNorPushesOverAMovedOne() {
        PublishException missing = assertThrows(PublishException.class, () -> Publisher.publish(SUITE, petroapps,
                options("PETROCORE-9", "PETROCORE-9", false, false, true, null, false)));
        assertTrue(missing.getMessage().matches("(?s)cannot push.*PETROCORE-9.*"), missing.getMessage());
        assertEquals("refs/heads/master", git(remote, "for-each-ref", "--format=%(refname)", "refs/heads"));

        String head = git(remote, "rev-parse", "refs/heads/master");
        git(petroapps, "fetch", "-q", "origin", "+refs/heads/master:refs/remotes/origin/master");
        PublishException moved = assertThrows(PublishException.class,
                () -> Publisher.pushOnto(petroapps, "master", "0".repeat(40), head));
        assertTrue(moved.getMessage().contains("moved"));
        assertEquals(head, git(remote, "rev-parse", "refs/heads/master"));

        PublishException other = assertThrows(PublishException.class, () -> Publisher.publish(SUITE, petroapps,
                options("master", "cat/tests", false, false, true, null, false)));
        assertTrue(other.getMessage().contains("writes no branch cat/tests"));
    }

    @Test
    void onlyPublishesOnePlansCasesAndAFeatureBesideAPyGoesAlone() throws Exception {
        recommit("PETROCORE-1-TestCase.feature", FEATURE_1, "feature beside the stub");
        Publication result = Publisher.publish(SUITE, petroapps,
                options("master", "cat/tests", false, false, false, Set.of("PETROCORE-1", "PETROCORE-3"), false));
        assertEquals(List.of("PETROCORE-1-CATReceipt.txt", "PETROCORE-1-TestCase.feature"),
                result.written.stream().map(p -> name(p.dest())).sorted().toList());
        assertEquals(List.of("PETROCORE-3-TestCase.py"), result.refused.stream().map(p -> name(p.source())).toList());
    }

    // -- the command ---------------------------------------------------------------------

    private record Ran(int code, String out, String err) {}

    private Ran command(Map<String, List<String>> plans, String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new PublishCommand(plan -> Optional.ofNullable(plans.get(plan)), tmp.resolve("tmp"))
                .run(argv, Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private List<String> args(String... more) {
        List<String> out = new ArrayList<>(List.of("publish", "--suite", SUITE, "--petroapps", petroapps.toString(),
                "--cat-root", catRoot.toString()));
        out.addAll(List.of(more));
        return out;
    }

    @Test
    void theCommandsDryRunWritesNothingAndACheckedOutBranchIsRefused() {
        Ran dry = command(Map.of(), args("--dry-run").toArray(String[]::new));
        assertEquals(0, dry.code(), dry.err());
        assertTrue(dry.out().contains("would write"), dry.out());
        assertEquals("", git(petroapps, "branch", "--list", "cat/tests"));
        String checkedOut = git(petroapps, "symbolic-ref", "--short", "HEAD");
        Ran refused = command(Map.of(), args("--branch", checkedOut).toArray(String[]::new));
        assertEquals(2, refused.code());
        assertTrue(refused.err().contains("checked out"), refused.err());
        assertEquals(2, command(Map.of(), args("--base", "master", "--branch", "other", "--push")
                .toArray(String[]::new)).code());
    }

    @Test
    void theCommandsPruneAndPlanReachThePublish() throws Exception {
        String c = CASE + "/PETROCORE-1";
        commit(remote, "refs/heads/old", files(c + "/PETROCORE-1-TestCase.xml", "<case/>",
                c + "/test_petrocore_1.py", "# the pre-2026-09-22 name\n"));
        git(petroapps, "fetch", "-q", "origin");
        assertFalse(command(Map.of(), args("--base", "old", "--dry-run").toArray(String[]::new)).out()
                .contains("would remove"));
        Ran pruned = command(Map.of(), args("--base", "old", "--dry-run", "--prune").toArray(String[]::new));
        assertTrue(pruned.out().contains("would remove " + c + "/test_petrocore_1.py"), pruned.out());
        Ran planned = command(Map.of("PETROCORE-79667", List.of("PETROCORE-2")),
                args("--dry-run", "--plan", "PETROCORE-79667").toArray(String[]::new));
        assertTrue(planned.out().contains("1 file(s) to write"), planned.out());
        Ran unknown = command(Map.of(), args("--plan", "PETROCORE-1").toArray(String[]::new));
        assertEquals(2, unknown.code());
        assertTrue(unknown.err().contains("no cached case list for PETROCORE-1"), unknown.err());
    }

    @Test
    void aGitFailureInsideThePublishLoopIsExitTwoNotATrace() throws Exception {
        String sha = git(catRoot, "rev-parse", "HEAD:" + SUITE + "/PETROCORE-1-TestCase.py");
        Path loose = catRoot.resolve(".git").resolve("objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
        loose.toFile().setWritable(true);
        Files.delete(loose);
        Ran ran = command(Map.of(), args("--dry-run").toArray(String[]::new));
        assertEquals(2, ran.code());
        assertTrue(ran.err().contains("cat-file"), ran.err());
    }
}
