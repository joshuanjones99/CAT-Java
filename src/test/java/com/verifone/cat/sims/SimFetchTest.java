package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.ArtifactoryAuthError;
import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.FakeArtifactory;
import com.verifone.cat.sims.base.NightlyBuilds;
import com.verifone.cat.sims.base.SuiteBase;

/**
 * A chosen Simulator Build replacing the staged simulators: the Python CAT's {@code tests/unit/test_cat_sims_fetch.py}
 * ({@code fetch_build}, {@code build_rows}, {@code running_rows}, {@code latest_build_with}), against
 * {@code FakeArtifactory} and a {@code @TempDir} tools root, with the registry's real rows. Windows' rename refusals and
 * the setup's {@code java.exe} are fakes. Evidence about this code, not about the real server or simulators.
 */
class SimFetchTest {

    private static final String REPO = "RMS_Nightly_CLW/Petro/master";
    private static final String ZIP = "install-isdApps.pcapps.simulators.app-V1.zip";
    private static final String LOYSIM_ZIP = "LoySim_V9.1.zip";
    private static final String CONNECTOR_ZIP = "terminalConnector-V4.zip";

    @TempDir
    Path tools;

    private SimContext ctx;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<List<String>> setups = new CopyOnWriteArrayList<>();

    private static final SimFetch.Running NOT_RUNNING = (entries, sims) -> Map.of();

    @BeforeEach
    void stageAJdk() throws IOException {
        // The setup (Petro's SSLUtil step) runs the JDK's java.exe; the exec seam stands in for it.
        Path bin = Files.createDirectories(tools.resolve("Java17").resolve("jdk-17.0.5+8").resolve("bin"));
        Files.write(bin.resolve("javaw.exe"), new byte[0]);
        Files.write(bin.resolve("java.exe"), new byte[0]);
        ctx = SimContext.of(tools, "");
    }

    private static SimEntry row(String name) {
        return Registry.byName(name).orElseThrow();
    }

    private static byte[] petroZip(String top) {
        return Zips.zip(top + "/Petro_Simulator_Suite.jar", "jar", top + "/config/sim.properties", "k=v");
    }

    private static byte[] loysimZip() {
        return Zips.zip("LoySim_V9.1/PCATS01/LoySim9.1.jar", "pcats01", "LoySim_V9.1/PCATS01/LoyaltySim.prop", "k=v",
                "LoySim_V9.1/PCATS02/LoySim9.1.jar", "pcats02", "LoySim_V9.1/PCATS02/LoyaltySim.prop", "k=v",
                "LoySim_V9.1/PCATS03/LoySim9.1.jar", "pcats03", "LoySim_V9.1/PCATS03/LoyaltySim.prop", "k=v");
    }

    private static Map<String, Object> d(Object... keysAndValues) {
        return FakeArtifactory.dir("", keysAndValues);
    }

    /** master 60 with simulators/ and automation/; viper-only 268; Solutions 94. */
    private static FakeArtifactory master60(byte[] petro) {
        return new FakeArtifactory(d("RMS_Nightly_CLW", d("Petro", d(
                "master", d(
                        "60", FakeArtifactory.dir("2026-10-07T14:18:00.000Z",
                                "simulators", d(ZIP, petro, LOYSIM_ZIP, loysimZip()),
                                "automation", d(CONNECTOR_ZIP, Zips.zip("app/main.py", "print()",
                                        "python-3.9.11-embed-amd64/python.exe", "py"))),
                        "268", d("viper-buypass", d("overlay.zip", new byte[] {1}))),
                "Solutions", d("94", d("buypass", d("overlay.zip", new byte[] {1})))))));
    }

    private static FakeArtifactory master60() {
        return master60(petroZip("Petro_Simulator_Suite"));
    }

    private SimFetch fetch(FakeArtifactory server) {
        SimFetch fetch = new SimFetch(server::client, lines::add);
        fetch.exec = (argv, cwd, timeout, err) -> {
            setups.add(argv);
            return 0;
        };
        fetch.renameInterval = Duration.ofMillis(10);
        fetch.downloadRetryPause = Duration.ofMillis(10);
        return fetch;
    }

    private Path stage(String folder, String file, String text) throws IOException {
        Path staged = Files.createDirectories(tools.resolve(folder));
        Files.writeString(staged.resolve(file), text);
        return staged;
    }

    private List<String> toolsChildren() throws IOException {
        try (Stream<Path> list = Files.list(tools)) {
            List<String> names = new ArrayList<>(list.map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("Java17")).toList());
            Collections.sort(names);
            return names;
        }
    }

    private static long count(List<String> downloads, String path) {
        return downloads.stream().filter(path::equals).count();
    }

    @Test
    void aChosenBuildReplacesTheStagedCopyConfigAndAll() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "the lab's own");
        Files.createDirectories(staged.resolve("config"));
        Files.writeString(staged.resolve("config/sim.properties"), "k=lab");
        try (FakeArtifactory server = master60()) {
            List<SimFetch.FetchResult> results = fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                    "master", "60", false, NOT_RUNNING);

            assertTrue(results.get(0).ok(), results.get(0).detail());
            assertTrue(results.get(0).detail().contains("replaced with master build 60's copy"));
            assertEquals(1, count(server.downloads(), "/" + REPO + "/60/simulators/" + ZIP));
        }
        assertFalse(Files.exists(staged.resolve("mine.txt"))); // completely replaced
        assertEquals("k=v", Files.readString(staged.resolve("config/sim.properties"))); // config too
        assertEquals(List.of("Petro_Simulator_Suite"), toolsChildren()); // no leftovers
        assertTrue(lines.stream().anyMatch(l -> l.contains("downloading " + REPO + "/60/simulators")), lines.toString());
        // The install record, and Petro's SSLUtil setup with the JDK's java.exe in the row's folder.
        assertEquals("60", SuiteBase.readMarker(staged).orElseThrow().build());
        assertEquals("master", SuiteBase.readMarker(staged).orElseThrow().branch());
        assertEquals(1, setups.size());
        assertTrue(setups.get(0).get(0).endsWith("java.exe"));
        assertEquals("com.verifone.isd.vsms2.sys.util.channel.ssl.SSLUtil", setups.get(0).get(setups.get(0).size() - 1));
    }

    @Test
    void aCopyAlreadyThatBuildIsNotDownloadedAgainAndNoLoginIsAsked() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "the lab's own");
        SuiteBase.writeMarker(staged, REPO, "129");
        Supplier<ArtifactoryClient> never = () -> {
            throw new AssertionError("no client should be made");
        };
        SimFetch.Running running = (entries, sims) -> {
            throw new AssertionError("a copy already that build is not checked for running");
        };

        List<SimFetch.FetchResult> results = new SimFetch(never, lines::add)
                .fetchBuild(List.of(row("Petro Simulator Suite")), ctx, "master", "129", false, running);

        assertTrue(results.get(0).ok());
        assertTrue(results.get(0).detail().contains("already master build 129"), results.get(0).detail());
        assertTrue(Files.exists(staged.resolve("mine.txt")));
    }

    @Test
    void anotherBuildOfTheSameBranchIsDownloaded() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "x");
        SuiteBase.writeMarker(staged, REPO, "59");
        try (FakeArtifactory server = master60()) {
            assertTrue(fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx, "master", "60", false,
                    NOT_RUNNING).get(0).ok());
        }
        assertEquals("60", SuiteBase.readMarker(staged).orElseThrow().build());
    }

    @Test
    void aFileThatIsNotAZipLeavesTheStagedCopyIntact() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "the lab's own");
        try (FakeArtifactory server = master60("not a zip".getBytes())) {
            SimFetch.FetchResult result = fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                    "master", "60", false, NOT_RUNNING).get(0);
            assertFalse(result.ok());
            assertTrue(result.detail().contains("ZipException"), result.detail());
        }
        assertEquals("the lab's own", Files.readString(staged.resolve("mine.txt")));
        assertEquals(List.of("Petro_Simulator_Suite"), toolsChildren());
    }

    @Test
    void aZipWithoutTheRowsDirectoryLeavesTheStagedCopyIntact() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "the lab's own");
        try (FakeArtifactory server = master60(petroZip("Other"))) {
            SimFetch.FetchResult result = fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                    "master", "60", false, NOT_RUNNING).get(0);
            assertTrue(result.detail().contains("holds no Petro_Simulator_Suite/"), result.detail());
        }
        assertTrue(Files.exists(staged.resolve("mine.txt")));
    }

    @Test
    void aRunningSimulatorIsRefusedAndNothingIsDownloaded() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "the lab's own");
        List<List<String>> seen = new ArrayList<>();
        SimFetch.Running running = (entries, sims) -> {
            seen.add(entries.stream().map(SimEntry::name).toList());
            return Map.of("Petro Simulator Suite", "something is listening on its port 9001");
        };
        try (FakeArtifactory server = master60()) {
            SimFetch.FetchResult result = fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                    "master", "60", false, running).get(0);
            assertFalse(result.ok());
            assertEquals("Petro Simulator Suite: running (something is listening on its port 9001); stop it before "
                    + "replacing its files", result.detail());
            assertEquals(List.of(), server.downloads());
        }
        assertEquals(List.of(List.of("Petro Simulator Suite")), seen);
        assertTrue(Files.exists(staged.resolve("mine.txt")));
    }

    @Test
    void aRenameRefusedOnceIsRetriedAndTheSwapCompletes() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "the lab's own");
        List<String> attempts = new ArrayList<>();
        List<IOException> refusals = new ArrayList<>(List.of(
                new FileSystemException("a", "b", "The process cannot access the file because it is being used by "
                        + "another process"),
                new AccessDeniedException("a")));
        try (FakeArtifactory server = master60()) {
            SimFetch fetch = fetch(server);
            fetch.mover = (src, dst) -> {
                attempts.add(dst.getFileName().toString());
                if (!refusals.isEmpty()) {
                    throw refusals.remove(0);
                }
                Files.move(src, dst);
            };
            assertTrue(fetch.fetchBuild(List.of(row("Petro Simulator Suite")), ctx, "master", "60", false,
                    NOT_RUNNING).get(0).ok());
        }
        assertFalse(Files.exists(staged.resolve("mine.txt")));
        assertEquals(List.of("Petro_Simulator_Suite"), toolsChildren());
        assertEquals(4, attempts.size()); // two renames, one retry each
    }

    @Test
    void aFolderHeldForTheWholeWindowStaysAndSaysItsFilesAreInUse() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "Petro_Simulator_Suite.jar", "old");
        try (FakeArtifactory server = master60()) {
            SimFetch fetch = fetch(server);
            fetch.renameRetry = Duration.ofMillis(50);
            fetch.mover = (src, dst) -> {
                if (src.equals(staged)) {
                    throw new AccessDeniedException(src.toString());
                }
                Files.move(src, dst);
            };
            SimFetch.FetchResult result = fetch.fetchBuild(List.of(row("Petro Simulator Suite")), ctx, "master",
                    "60", false, NOT_RUNNING).get(0);
            assertFalse(result.ok());
            assertTrue(result.detail().contains("its files are in use, so the staged copy stays"), result.detail());
        }
        assertEquals("old", Files.readString(staged.resolve("Petro_Simulator_Suite.jar")));
        assertEquals(List.of("Petro_Simulator_Suite"), toolsChildren());
    }

    @Test
    void aRenameRefusedForAnotherReasonIsNotRetried() throws IOException {
        stage("Petro_Simulator_Suite", "mine.txt", "x");
        List<String> attempts = new ArrayList<>();
        try (FakeArtifactory server = master60()) {
            SimFetch fetch = fetch(server);
            fetch.mover = (src, dst) -> {
                attempts.add(dst.getFileName().toString());
                throw new java.nio.file.NoSuchFileException(src.toString());
            };
            assertFalse(fetch.fetchBuild(List.of(row("Petro Simulator Suite")), ctx, "master", "60", false,
                    NOT_RUNNING).get(0).ok());
        }
        assertEquals(1, attempts.size());
    }

    @Test
    void aFolderAProcessSitsInHasItsContentsReplacedInstead() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "the lab's own");
        Files.createDirectories(staged.resolve("sub"));
        Files.writeString(staged.resolve("sub/deep.txt"), "old");
        try (FakeArtifactory server = master60()) {
            SimFetch fetch = fetch(server);
            fetch.renameRetry = Duration.ofMillis(50);
            fetch.mover = (src, dst) -> {
                if (src.equals(staged)) { // a console's cwd: the folder's own rename is a sharing violation
                    throw new FileSystemException(src.toString(), dst.toString(),
                            "The process cannot access the file because it is being used by another process");
                }
                Files.move(src, dst);
            };
            SimFetch.FetchResult result = fetch.fetchBuild(List.of(row("Petro Simulator Suite")), ctx, "master",
                    "60", false, NOT_RUNNING).get(0);
            assertTrue(result.ok(), result.detail());
        }
        assertFalse(Files.exists(staged.resolve("mine.txt")));
        assertFalse(Files.exists(staged.resolve("sub")));
        assertTrue(Files.exists(staged.resolve("Petro_Simulator_Suite.jar")));
        assertEquals(List.of("Petro_Simulator_Suite"), toolsChildren());
        assertTrue(lines.stream().anyMatch(l -> l.contains("contents were replaced in place")), lines.toString());
    }

    @Test
    void rowsSharingAZipDownloadItOnce() throws IOException {
        List<SimEntry> rows = List.of(row("PCATS01 Loyalty"), row("PCATS02 Loyalty"), row("PCATS03 Loyalty"));
        for (SimEntry r : rows) {
            stage(r.cwd(), "LoyaltySim.prop", "lab");
        }
        try (FakeArtifactory server = master60()) {
            List<SimFetch.FetchResult> results = fetch(server).fetchBuild(rows, ctx, "master", "60", false,
                    NOT_RUNNING);
            assertEquals(List.of(true, true, true), results.stream().map(SimFetch.FetchResult::ok).toList());
            assertEquals(List.of("/" + REPO + "/60/simulators/" + LOYSIM_ZIP), server.downloads());
        }
        for (SimEntry r : rows) {
            assertEquals(r.cwd().toLowerCase(), Files.readString(tools.resolve(r.cwd()).resolve("LoySim9.1.jar")));
            assertEquals("k=v", Files.readString(tools.resolve(r.cwd()).resolve("LoyaltySim.prop")));
        }
    }

    @Test
    void aBuildWithoutSimulatorsSaysSo() {
        try (FakeArtifactory server = master60()) {
            List<SimFetch.FetchResult> solutions = fetch(server).fetchBuild(
                    List.of(row("Petro Simulator Suite"), row("Terminal Connector")), ctx, "Solutions", "94", false,
                    NOT_RUNNING);
            assertTrue(solutions.stream().allMatch(r -> r.detail().contains("Solutions builds carry no simulators")));
            SimFetch.FetchResult viper = fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                    "master", "268", false, NOT_RUNNING).get(0);
            assertTrue(viper.detail().contains("master 268 has no simulators/"), viper.detail());
            SimFetch.FetchResult missing = fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                    "master", "61", false, NOT_RUNNING).get(0);
            assertTrue(missing.detail().contains("master has no build 61"), missing.detail());
            assertEquals(List.of(), server.downloads());
        }
    }

    @Test
    void aStagedOnlyRowIsReplacedLikeAnyOther() throws IOException {
        List<SimEntry> rows = SimFetch.buildRows(Registry.all(), Set.of("Petro Simulator Suite"));
        assertEquals(List.of("Petro Simulator Suite", "Terminal Connector"), rows.stream().map(SimEntry::name).toList());

        stage("terminalConnector-V4", "old.txt", "x");
        try (FakeArtifactory server = master60()) {
            SimFetch.FetchResult connector = fetch(server).fetchBuild(List.of(row("Terminal Connector")), ctx,
                    "master", "60", false, NOT_RUNNING).get(0);
            assertTrue(connector.ok(), connector.detail());
        }
        assertTrue(Files.isRegularFile(tools.resolve("terminalConnector-V4/app/main.py")));
        assertFalse(Files.exists(tools.resolve("terminalConnector-V4/old.txt")));
    }

    @Test
    void noLoginThrowsBeforeAnythingChanges() throws IOException {
        Path staged = stage("Petro_Simulator_Suite", "mine.txt", "x");
        SimFetch fetch = new SimFetch(() -> {
            throw new ArtifactoryAuthError("ARTIFACTORY_USER", "ARTIFACTORY_PASSWORD");
        }, lines::add);
        assertThrows(ArtifactoryAuthError.class, () -> fetch.fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                "master", "60", false, NOT_RUNNING));
        assertTrue(Files.exists(staged.resolve("mine.txt")));
    }

    @Test
    void aSetupWithNoJavaExeIsTheRowsProblem() throws IOException {
        Files.delete(tools.resolve("Java17/jdk-17.0.5+8/bin/java.exe"));
        stage("Petro_Simulator_Suite", "mine.txt", "x");
        try (FakeArtifactory server = master60()) {
            SimFetch.FetchResult result = fetch(server).fetchBuild(List.of(row("Petro Simulator Suite")), ctx,
                    "master", "60", false, NOT_RUNNING).get(0);
            assertFalse(result.ok());
            assertEquals("Petro Simulator Suite: unpacked, but no java.exe to run its setup with", result.detail());
        }
    }

    @Test
    void theOlderBaseFormIsFetchedFromItsOwnZip() throws IOException {
        SimEntry older = Registry.forBase(true).stream().filter(e -> e.name().equals("PCATS01 Loyalty"))
                .findFirst().orElseThrow();
        stage("PCATS01", "LoySim9.1.jar", "9.1");
        byte[] v7 = Zips.zip("LoySim_V7/PCATS01/LoySim7.jar", "7", "LoySim_V7/PCATS01/LoyaltySim.prop", "k=v");
        try (FakeArtifactory server = new FakeArtifactory(d("RMS_Nightly_CLW", d("Petro", d("master055.02",
                d("41", FakeArtifactory.dir("2026-09-28T18:23:48.785Z", "simulators", d("LoySim_V7.zip", v7)))))))) {
            SimFetch.FetchResult result = fetch(server).replaceStale(List.of(older), ctx, "master055.02", "41", true,
                    NOT_RUNNING).get(0);
            assertTrue(result.ok(), result.detail());
        }
        assertEquals("7", Files.readString(tools.resolve("PCATS01/LoySim7.jar")));
        assertFalse(Files.exists(tools.resolve("PCATS01/LoySim9.1.jar")));
        assertEquals("master055.02", SuiteBase.readMarker(tools.resolve("PCATS01")).orElseThrow().branch());
    }

    @Test
    void theNewestBuildWithTheFolderWinsByDateNotNumber() {
        try (FakeArtifactory server = new FakeArtifactory(d("RMS_Nightly_CLW", d("Petro", d("master", d(
                "24216", FakeArtifactory.dir("2026-09-21T10:00:00.000Z", "simulators", d()),
                "268", FakeArtifactory.dir("2026-10-07T14:27:00.000Z", "viper-buypass", d()),
                "60", FakeArtifactory.dir("2026-10-07T14:18:00.000Z", "simulators", d()))))))) {
            assertEquals("60", NightlyBuilds.latestBuildWith(server.client(), REPO, "simulators"));
        }
    }

    @Test
    void runningRowsReadsTheSupervisorsProbe() {
        SimEntry connector = row("Terminal Connector");
        SimEntry petro = row("Petro Simulator Suite");
        String blob = tools + "\\terminalConnector-V4\\python-3.9.11-embed-amd64\\python.exe agent.py";
        FakeOps ops = new FakeOps(ProcessOps.Snapshot.ok(blob));

        assertEquals(Map.of("Terminal Connector",
                "its process is running; CAT's own VATS connector runs from this copy"),
                SimRunning.runningRows(List.of(connector, petro), ctx, ops, (hosts, port) -> ""));
        assertEquals(Map.of("Petro Simulator Suite", "something is listening on its port 9001"),
                SimRunning.runningRows(List.of(petro), ctx, ops, (hosts, port) -> "localhost"));
        assertEquals(Map.of("Petro Simulator Suite", "could not list processes to tell (CIM timed out)"),
                SimRunning.runningRows(List.of(petro), ctx, new FakeOps(ProcessOps.Snapshot.failed("CIM timed out")),
                        (hosts, port) -> ""));
    }

    @Test
    void everyRegistryRowHasASource() {
        assertEquals(List.of(), SimSources.check());
        assertEquals("Petro_Simulator_Suite.jar",
                SimSources.jarFor(row("Petro Simulator Suite"), ctx).orElseThrow().getFileName().toString());
    }

    /** Answers the command-line query only; nothing here may list or kill a real process. */
    private record FakeOps(ProcessOps.Snapshot cmdlines) implements ProcessOps {
        @Override
        public Snapshot commandLines(List<String> names, Duration timeout) {
            return cmdlines;
        }

        @Override
        public Snapshot listenerCommandLine(int port, Duration timeout) {
            throw new AssertionError("not asked");
        }

        @Override
        public Snapshot listing(List<String> names, List<Integer> ports, Duration timeout) {
            throw new AssertionError("not asked");
        }

        @Override
        public Snapshot stillAlive(List<Long> pids, Duration timeout) {
            throw new AssertionError("not asked");
        }

        @Override
        public String taskkill(List<Long> pids, Duration timeout) {
            throw new AssertionError("nothing is killed");
        }
    }
}
