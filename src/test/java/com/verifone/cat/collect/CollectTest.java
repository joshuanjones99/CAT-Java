package com.verifone.cat.collect;

import static com.verifone.cat.collect.PetroappsFixture.CASE;
import static com.verifone.cat.collect.PetroappsFixture.BDD;
import static com.verifone.cat.collect.PetroappsFixture.commit;
import static com.verifone.cat.collect.PetroappsFixture.files;
import static com.verifone.cat.collect.PetroappsFixture.git;
import static com.verifone.cat.collect.PetroappsFixture.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Collect} against fixture petroapps repositories built from git objects. The Python CAT's
 * {@code tests/unit/test_collect.py}, minus the CLI and the snapshot-plugin tests (the CLI is not ported, and the
 * snapshot fixture is the validate package's, not this one's). Evidence about this repository's selection logic, not
 * about petroapps.
 */
class CollectTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** PETROCORE-1: a dist copy and a case-folder copy of everything. */
    private static final Map<String, byte[]> BASE = base();

    @TempDir
    static Path shared;

    private static Path petroapps;

    @BeforeAll
    static void buildPetroapps() {
        Assumptions.assumeTrue(PetroappsFixture.gitAvailable(), "git is not on PATH");
        petroapps = shared.resolve("petroapps");
        git(shared, "init", "-q", petroapps.toString());
        commit(petroapps, "refs/heads/master", BASE);
    }

    @TempDir
    Path tmp;

    private static Map<String, byte[]> base() {
        return files(
                CASE + "/PETROCORE-1/PETROCORE-1-TestCase.xml", "<case/>",
                CASE + "/PETROCORE-1/PETROCORE-1-Receipt.txt", "case receipt",
                CASE + "/PETROCORE-1/buypass/PETROCORE-1-Receipt.txt", "buypass receipt",
                CASE + "/PETROCORE-1/hps/PETROCORE-1-Receipt.txt", "hps receipt",
                CASE + "/PETROCORE-1/PETROCORE-1-Config-Reference.xml", "case ref",
                CASE + "/PETROCORE-1/buypass/PETROCORE-1-Config-Reference.xml", "buypass ref",
                CASE + "/PETROCORE-1/Packets/BUYPASS/deep/PETROCORE-1-0100.txt", "packet",
                CASE + "/PETROCORE-1/PETROCORE-1-TestCase.py", "# case folder\n",
                CASE + "/PETROCORE-1/buypass/PETROCORE-1-TestCase.py", "# buypass\n",
                CASE + "/PETROCORE-1/conftest.py", "# never pulled\n",
                // PETROCORE-2: XML in testcases/, .scenario in TestCases/, one name in both trees with different content.
                CASE + "/PETROCORE-2/PETROCORE-2-TestCase.xml", "<topaz101/>",
                BDD + "/PETROCORE-2/PETROCORE-2-TestCase.scenario", "Feature: topaz102",
                CASE + "/PETROCORE-2/PETROCORE-2-Rule.txt", "rule A",
                BDD + "/PETROCORE-2/PETROCORE-2-Rule.txt", "rule B",
                CASE + "/PETROCORE-2/PETROCORE-2-TestCase.py", "# tree one\n",
                BDD + "/PETROCORE-2/PETROCORE-2-TestCase.py", "# tree two\n",
                // PETROCORE-3: only in TestCases/, same-content duplicate across trees, one test.
                BDD + "/PETROCORE-3/PETROCORE-3-Tlog.xml", "<tlog/>",
                CASE + "/PETROCORE-3/PETROCORE-3-TLOG.xml", "<tlog/>",
                BDD + "/PETROCORE-3/PETROCORE-3-TestCase.py", "# three\n",
                // Shared assets: one top-level-only copy, one recursive.
                "AutomationTests/rulefiles/tlog/TLOG-RULE.txt", "r",
                "AutomationTests/rulefiles/tlog/nested/ignored.txt", "r",
                "AutomationTests/configurations/base/PLUs.xml", "<p/>",
                "AutomationTests/configurations/Collis_Images/a.png", "png",
                "isdApps/xml/schemas/src/schemas/tlog.xsd", "<xsd/>",
                "unrelated/file.txt", "not collected");
    }

    /** A stand-in for this checkout: {@code tests/} holds one hand-migrated test. */
    private Path catRoot() throws IOException {
        Path root = tmp.resolve("cat");
        Path charity = root.resolve("tests").resolve("suites").resolve("charity");
        Files.createDirectories(charity);
        Files.writeString(charity.resolve("PETROCORE-3-TestCase.py"), "# hand\n", StandardCharsets.UTF_8);
        return root;
    }

    private static CollectOptions options(Path root, Path out) {
        return new CollectOptions("master", "buypass", out, root, false);
    }

    private static Map<String, CaseResult> byKey(CollectResult result) {
        Map<String, CaseResult> map = new HashMap<>();
        for (CaseResult c : result.cases) {
            map.put(c.key, c);
        }
        return map;
    }

    private static Map<String, String> sourcesOf(CaseResult c, String... rules) {
        List<String> wanted = List.of(rules);
        Map<String, String> sources = new HashMap<>();
        for (CaseResult.Copy copy : c.copies) {
            if (wanted.contains(copy.rule())) {
                sources.put(copy.rule(), copy.source());
            }
        }
        return sources;
    }

    private static List<String> filesIn(Path dir) throws IOException {
        try (Stream<Path> names = Files.list(dir)) {
            return names.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void distPreferenceFollowsCicdscript() {
        CaseResult c = byKey(Collect.collect(List.of("PETROCORE-1"), petroapps,
                CollectOptions.defaults(tmp))).get("PETROCORE-1");
        Map<String, String> sources = sourcesOf(c, "Receipt", "Config-Reference");
        // Receipt looks in <id>/<dist>/ first; Config-Reference in <id>/ first (lines 494, 559).
        assertEquals(CASE + "/PETROCORE-1/buypass/PETROCORE-1-Receipt.txt", sources.get("Receipt"));
        assertEquals(CASE + "/PETROCORE-1/PETROCORE-1-Config-Reference.xml", sources.get("Config-Reference"));
        CaseResult hps = byKey(Collect.collect(List.of("PETROCORE-1"), petroapps,
                new CollectOptions("master", "hps", null, tmp, false))).get("PETROCORE-1");
        assertTrue(hps.copies.stream().anyMatch(
                copy -> copy.source().equals(CASE + "/PETROCORE-1/hps/PETROCORE-1-Receipt.txt")));
        // `if exist Packets\BUYPASS\` then `xcopy /s`: the nested packet is copied.
        assertTrue(c.copies.stream().anyMatch(
                copy -> copy.rule().equals("Packets/BUYPASS") && copy.source().endsWith("0100.txt")));
    }

    @Test
    void missingFilesAreEchoedTheWayPatsEchoesThem() {
        Map<String, CaseResult> result = byKey(Collect.collect(List.of("PETROCORE-1", "PETROCORE-404"), petroapps,
                CollectOptions.defaults(tmp)));
        assertEquals(List.of(
                "PETROCORE-1-Tlog.xml",
                "PETROCORE-1REPORT-RULE.txt",
                "PETROCORE-1-Report.xml",
                "PETROCORE-1-Rule.txt",
                "PETROCORE-1-Config-Input.xml",
                "PETROCORE-1-httpSimPayLoad.txt",
                "PETROCORE-1-httpSimControl.prop.txt",
                "PETROCORE-1-Response.xml"), result.get("PETROCORE-1").missing);
        CaseResult absent = result.get("PETROCORE-404");
        assertTrue(absent.trees.isEmpty() && absent.copies.isEmpty());
        assertTrue(absent.missing.contains("PETROCORE-404-TestCase.xml"));
    }

    @Test
    void theTwoTreesAreReadAsOneWindowsFolder() {
        Map<String, CaseResult> result = byKey(Collect.collect(List.of("PETROCORE-2", "PETROCORE-3"), petroapps,
                CollectOptions.defaults(tmp)));
        CaseResult two = result.get("PETROCORE-2");
        assertEquals(List.of("TestCases", "testcases"), two.trees);
        // `*-TestCase.*` matches both bodies, as PATS's xcopy does on the collapsed folder.
        assertEquals(java.util.Set.of(
                CASE + "/PETROCORE-2/PETROCORE-2-TestCase.xml",
                BDD + "/PETROCORE-2/PETROCORE-2-TestCase.scenario"),
                two.copies.stream().filter(c -> c.rule().equals("TestCase")).map(CaseResult.Copy::source)
                        .collect(java.util.stream.Collectors.toSet()));
        CaseResult.Collision rule = two.collisions.stream()
                .filter(c -> c.name().equals("PETROCORE-2-Rule.txt")).findFirst().orElseThrow();
        assertFalse(rule.sameContent());
        assertEquals(java.util.Set.of(
                CASE + "/PETROCORE-2/PETROCORE-2-Rule.txt",
                BDD + "/PETROCORE-2/PETROCORE-2-Rule.txt"), java.util.Set.copyOf(rule.paths()));
        // Case-insensitive name match across trees; identical content is still reported.
        CaseResult three = result.get("PETROCORE-3");
        assertEquals(List.of(true), three.collisions.stream().map(CaseResult.Collision::sameContent).toList());
        assertFalse(three.missing.contains("PETROCORE-3-Tlog.xml"));
    }

    @Test
    void dryRunWritesNothingAndReportsNotRun() {
        CollectResult result = Collect.collect(List.of("PETROCORE-1", "PETROCORE-404"), petroapps,
                CollectOptions.defaults(tmp));
        assertNull(result.cases.get(0).node());
        assertNull(result.cases.get(1).node());
        assertNull(result.outDir);
        List<String> lines = result.lines();
        assertTrue(lines.contains("PETROCORE-404-TestCase.xml does not exist"));
        assertTrue(lines.contains("  PETROCORE-404 has no translated .py test: Not Run"));
    }

    @Test
    void pullWritesPyTestsAndRefusesTheUnsafeOnes() throws IOException {
        Path root = catRoot();
        Path out = root.resolve(Collect.PULLED_DIR);
        Map<String, CaseResult> result = byKey(Collect.collect(
                List.of("PETROCORE-1", "PETROCORE-2", "PETROCORE-3"), petroapps, options(root, out)));
        CaseResult one = result.get("PETROCORE-1");
        assertEquals("tests/suites/pulled/PETROCORE-1-TestCase.py", one.node());
        assertEquals("# buypass\n", read(out.resolve("PETROCORE-1-TestCase.py"))); // dist first (A2)
        assertEquals(List.of(), one.pulled.stream().filter(p -> p.dest() == null).toList());
        assertFalse(filesIn(out).contains("conftest.py"));
        // Different content in the two trees: refused, not guessed (A1).
        assertNull(result.get("PETROCORE-2").node());
        assertTrue(result.get("PETROCORE-2").pulled.get(0).reason().contains("different contents"));
        // Already hand-migrated under tests/: refused (A5).
        assertNull(result.get("PETROCORE-3").node());
        assertTrue(result.get("PETROCORE-3").pulled.get(0).reason().contains("already exists under tests/"));
        JsonNode manifest = JSON.readTree(read(out.resolve(Collect.MANIFEST)));
        assertEquals(List.of("PETROCORE-1-TestCase.py"), textsOf(manifest.get("written")));
        assertEquals(40, manifest.get("commit").asText().length());
        assertEquals("buypass", manifest.get("dist").asText());
    }

    @Test
    void aCaseShadowedByACopyUnderTestsIsNotReportedNotRun() throws IOException {
        Path root = catRoot();
        CollectResult result = Collect.collect(List.of("PETROCORE-3", "PETROCORE-404"), petroapps,
                options(root, root.resolve(Collect.PULLED_DIR)));
        List<String> lines = result.lines();
        assertTrue(lines.contains("  PETROCORE-3 runs the copy already under tests/, not petroapps'"));
        assertFalse(lines.contains("  PETROCORE-3 has no translated .py test: Not Run"));
        assertTrue(lines.contains("  PETROCORE-404 has no translated .py test: Not Run"));
        assertTrue(lines.get(lines.size() - 1)
                .contains("0 with a pulled .py test, 1 running the copy under tests/, 1 Not Run"));
    }

    @Test
    void conftestInTheChosenFolderIsRefused() throws IOException {
        Map<String, byte[]> files = with(BASE,
                CASE + "/PETROCORE-4/conftest.py", "# no\n",
                CASE + "/PETROCORE-4/PETROCORE-4-TestCase.py", "# four\n");
        commit(petroapps, "refs/heads/four", files);
        Path root = catRoot();
        Path out = root.resolve(Collect.PULLED_DIR);
        CollectResult result = Collect.collect(List.of("PETROCORE-4"), petroapps,
                new CollectOptions("four", "buypass", out, root, false));
        CaseResult c = result.cases.get(0);
        assertEquals("tests/suites/pulled/PETROCORE-4-TestCase.py", c.node());
        assertEquals(List.of("conftest.py is never pulled (one conftest.py, no packages)"),
                c.pulled.stream().filter(p -> p.dest() == null).map(CaseResult.Pulled::reason).toList());
        assertFalse(Files.exists(out.resolve("conftest.py")));
    }

    @Test
    void recollectingRemovesOnlyWhatTheLastCollectionWrote() throws IOException {
        Path root = catRoot();
        Path out = root.resolve(Collect.PULLED_DIR);
        Collect.collect(List.of("PETROCORE-1"), petroapps, options(root, out));
        Files.writeString(out.resolve("operator-notes.txt"), "keep me", StandardCharsets.UTF_8);
        Collect.collect(List.of("PETROCORE-404"), petroapps, options(root, out));
        assertEquals(List.of(Collect.MANIFEST, "operator-notes.txt"), filesIn(out));
    }

    @Test
    void aFailedBlobReadLeavesTheLastCollectionWhole() throws IOException {
        Path root = catRoot();
        Path out = root.resolve(Collect.PULLED_DIR);
        CollectResult collection = Collect.collect(List.of("PETROCORE-1"), petroapps, options(root, out));
        String before = read(out.resolve(Collect.MANIFEST));
        String good = git(petroapps, "rev-parse", collection.commit + ":" + CASE + "/PETROCORE-1/PETROCORE-1-TestCase.py");
        List<Collect.Pending> queued = List.of(
                new Collect.Pending("new-TestCase.py", out.resolve("new-TestCase.py"), good),
                new Collect.Pending("bad-TestCase.py", out.resolve("bad-TestCase.py"), "0".repeat(40)));
        CollectException e = assertThrows(CollectException.class,
                () -> Collect.write(petroapps, out, queued, collection));
        assertTrue(e.getMessage().contains("cat-file"));
        assertEquals("# buypass\n", read(out.resolve("PETROCORE-1-TestCase.py")));
        assertEquals(before, read(out.resolve(Collect.MANIFEST)));
        assertFalse(Files.exists(out.resolve("new-TestCase.py")));
    }

    @Test
    void revisionIsReadWithoutTouchingTheCheckout() {
        String branchCommit = commit(petroapps, "refs/remotes/origin/cr1234",
                with(BASE, CASE + "/PETROCORE-1/PETROCORE-1-Tlog.xml", "<t/>"));
        // No origin/ for this fixture, so the local ref is read: origin/cr1234 is found first.
        CollectResult result = Collect.collect(List.of("PETROCORE-1"), petroapps,
                new CollectOptions("cr1234", "buypass", null, petroapps, false));
        assertEquals(branchCommit, result.commit);
        assertFalse(result.cases.get(0).missing.contains("PETROCORE-1-Tlog.xml"));
        CollectResult master = Collect.collect(List.of("PETROCORE-1"), petroapps, CollectOptions.defaults(tmp));
        assertNotEquals(branchCommit, master.commit);
        CollectException e = assertThrows(CollectException.class, () -> Collect.collect(List.of("PETROCORE-1"),
                petroapps, new CollectOptions("no-such-branch", "buypass", null, tmp, false)));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void sharedAssetsFollowXcopyRecursion() {
        Map<String, Integer> assets = new HashMap<>();
        for (CollectResult.Asset a : Collect.collect(List.of(), petroapps, CollectOptions.defaults(tmp)).assets) {
            assets.put(a.source(), a.files());
        }
        assertEquals(1, assets.get("AutomationTests/rulefiles/tlog")); // no /s: nested/ is not copied
        assertEquals(2, assets.get("AutomationTests/configurations")); // /s
        assertEquals(1, assets.get("isdApps/xml/schemas/src/schemas"));
        assertEquals(0, assets.get("AutomationTests/setup")); // reported as "does not exist"
    }

    @Test
    void baselinesArePulledBesideTheTestAndRemovedByARecollect() throws IOException {
        Map<String, byte[]> files = with(BASE,
                CASE + "/PETROCORE-8/PETROCORE-8-TestCase.py", "# case folder: not the dist copy\n",
                CASE + "/PETROCORE-8/PETROCORE-8-CATReceipt.txt", "no",
                CASE + "/PETROCORE-8/buypass/PETROCORE-8-TestCase.py",
                "from petrotest.validate import assert_receipt\n",
                CASE + "/PETROCORE-8/buypass/PETROCORE-8-CATReceipt.txt", "THANK YOU FOR SHOPPING\n",
                BDD + "/PETROCORE-8/buypass/PETROCORE-8-Tlog.xml", "<tlog/>\n",
                // PATS's own files in the same folder are never mistaken for baselines.
                CASE + "/PETROCORE-8/buypass/PETROCORE-8-Receipt.txt", "pats, not pulled",
                CASE + "/PETROCORE-8/buypass/PETROCORE-8-Rule.txt", "1,2",
                BDD + "/PETROCORE-3/PETROCORE-3-CATReceipt.txt", "r");
        String rev = commit(petroapps, "refs/heads/baselines", files);
        Path root = catRoot();
        Path out = root.resolve(Collect.PULLED_DIR);
        CollectOptions options = new CollectOptions(rev, "buypass", out, root, false);
        Map<String, CaseResult> result = byKey(Collect.collect(List.of("PETROCORE-8", "PETROCORE-3"), petroapps,
                options));
        String pulled = "tests/suites/pulled";
        // Dist-first, from the folder the .py came from; PATS's own -Receipt.txt and -Rule.txt are not baselines.
        assertEquals(List.of(pulled + "/PETROCORE-8-CATReceipt.txt", pulled + "/PETROCORE-8-Tlog.xml"),
                result.get("PETROCORE-8").baselines.stream().map(CaseResult.Pulled::dest).toList());
        assertEquals("THANK YOU FOR SHOPPING\n", read(out.resolve("PETROCORE-8-CATReceipt.txt")));
        assertFalse(Files.exists(out.resolve("PETROCORE-8-Receipt.txt")));
        assertFalse(Files.exists(out.resolve("PETROCORE-8-Rule.txt")));
        // A test that is not pulled does not bring its baselines.
        List<CaseResult.Pulled> refused = result.get("PETROCORE-3").baselines;
        assertEquals(2, refused.size());
        assertTrue(refused.stream().allMatch(
                r -> r.dest() == null && r.reason().startsWith("its test was not pulled")));
        assertEquals(List.of("PETROCORE-8-TestCase.py", "PETROCORE-8-CATReceipt.txt", "PETROCORE-8-Tlog.xml"),
                textsOf(JSON.readTree(read(out.resolve(Collect.MANIFEST))).get("written")));
        Collect.collect(List.of("PETROCORE-404"), petroapps, options);
        assertEquals(List.of(Collect.MANIFEST), filesIn(out));
    }

    @Test
    void fetchReadsTheBranchFromOriginAndLeavesTheCheckoutAlone() throws IOException {
        Path tmpRepo = Files.createTempDirectory(tmp, "fetch");
        Path[] pair = PetroappsFixture.remoteAndCheckout(tmpRepo);
        Path remote = pair[0];
        Path local = pair[1];
        Map<String, byte[]> files = files(CASE + "/PETROCORE-5/PETROCORE-5-TestCase.xml", "<case/>");
        String first = commit(remote, "refs/heads/cr1", files);
        String before = checkoutState(local);

        CollectResult result = Collect.collect(List.of("PETROCORE-5"), local,
                new CollectOptions("cr1", "buypass", null, tmpRepo, true));
        assertEquals(Boolean.TRUE, result.fetched);
        assertEquals("", result.fetchError);
        assertEquals("origin/cr1", result.ref);
        assertEquals(first, result.commit);
        assertTrue(result.lines().get(0).contains("fetched from origin"));
        assertEquals(before, checkoutState(local)); // working tree, index and HEAD untouched

        // The remote moves on; the next collection sees it without a checkout or pull.
        Map<String, byte[]> next = with(files, CASE + "/PETROCORE-5/PETROCORE-5-Tlog.xml", "<tlog/>");
        String second = commit(remote, "refs/heads/cr1-next", next);
        git(remote, "update-ref", "refs/heads/cr1", second); // the + refspec: a forced move is taken
        CollectResult again = Collect.collect(List.of("PETROCORE-5"), local,
                new CollectOptions("cr1", "buypass", null, tmpRepo, true));
        assertEquals(second, again.commit);
        assertFalse(again.cases.get(0).missing.contains("PETROCORE-5-Tlog.xml"));
        assertEquals(before, checkoutState(local));
    }

    @Test
    void aFailedFetchWarnsAndNamesTheLocalRefItUsed() throws IOException {
        Path tmpRepo = Files.createTempDirectory(tmp, "failed");
        Path[] pair = PetroappsFixture.remoteAndCheckout(tmpRepo);
        Path remote = pair[0];
        Path local = pair[1];
        String fetched = commit(remote, "refs/heads/cr1", files(CASE + "/PETROCORE-5/x.txt", "x"));
        Collect.collect(List.of(), local, new CollectOptions("cr1", "buypass", null, tmpRepo, true));
        git(local, "remote", "set-url", "origin", tmpRepo.resolve("gone.git").toString()); // offline

        CollectResult result = Collect.collect(List.of("PETROCORE-5"), local,
                new CollectOptions("cr1", "buypass", null, tmpRepo, true));
        assertEquals(Boolean.FALSE, result.fetched);
        assertFalse(result.fetchError.isEmpty());
        assertEquals("origin/cr1", result.ref);
        assertEquals(fetched, result.commit);
        String warning = result.lines().get(0);
        assertTrue(warning.startsWith("WARNING: git fetch origin cr1 failed"));
        assertTrue(warning.contains("Using the local origin/cr1 (" + fetched.substring(0, 11) + ")"));
        // No origin/<branch> at all: the local branch, named as such.
        CollectResult localMaster = Collect.collect(List.of(), local,
                new CollectOptions("master", "buypass", null, tmpRepo, true));
        assertEquals(Boolean.FALSE, localMaster.fetched);
        assertEquals("master", localMaster.ref);
    }

    private static String checkoutState(Path local) {
        return String.join("|",
                git(local, "rev-parse", "HEAD"),
                git(local, "symbolic-ref", "HEAD"),
                git(local, "status", "--porcelain=v1", "-uall"),
                git(local, "diff", "--cached"),
                readUnchecked(local.resolve("tracked.txt")),
                readUnchecked(local.resolve("untracked.txt")));
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> textsOf(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            out.add(node.asText());
        }
        return out;
    }
}
