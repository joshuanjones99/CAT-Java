package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.ArtifactoryAuthError;
import com.verifone.cat.build.FakeArtifactory;
import com.verifone.cat.sims.base.JavaSets;

/**
 * {@link SimStaging}, {@link SimFetch#fetchMissing} and {@code cat-java fetch-sims}: the Python CAT's
 * {@code staging.missing_tools}/{@code stage_missing_tools}, {@code fetch.fetch_missing} and
 * {@code _run_fetch_sims_command}, against a {@code @TempDir} tools root and {@code FakeArtifactory}. Evidence about
 * this code, not about the real server.
 */
class SimStagingTest {

    private static final String ZIP = "install-isdApps.pcapps.simulators.app-V1.zip";

    @TempDir
    Path tools;

    private final List<String> lines = new CopyOnWriteArrayList<>();

    private static Map<String, Object> d(Object... keysAndValues) {
        return FakeArtifactory.dir("", keysAndValues);
    }

    private static FakeArtifactory master60() {
        return new FakeArtifactory(d("RMS_Nightly_CLW", d("Petro", d("master", d(
                "60", FakeArtifactory.dir("2026-10-07T14:18:00.000Z", "simulators", d(ZIP,
                        Zips.zip("Petro_Simulator_Suite/Petro_Simulator_Suite.jar", "jar",
                                "Petro_Simulator_Suite/config/sim.properties", "k=v"))))))));
    }

    @Test
    void everythingIsMissingUnderAnEmptyToolsRoot() {
        SimStaging.MissingTools missing = SimStaging.missingTools(tools);
        assertEquals(JavaSets.SETS, missing.java());
        assertEquals(Registry.all(), missing.simulators());
        assertEquals("Java17 (Java 17 v1.2)", missing.names().get(0));
        assertTrue(missing.names().contains("Terminal Connector"));
    }

    @Test
    void aMissingRowIsFetchedFromTheNewestBuildAndAStagedOneIsNeverTouched() throws IOException {
        Path staged = Files.createDirectories(tools.resolve("BUYPASS"));
        Files.writeString(staged.resolve("mine.txt"), "the lab's own");
        SimContext ctx = SimContext.of(tools, "");
        try (FakeArtifactory server = master60()) {
            List<String> problems = new SimFetch(server::client, lines::add).fetchMissing(
                    List.of(Registry.byName("BUYPASS Host").orElseThrow(),
                            Registry.byName("Petro Simulator Suite").orElseThrow()), ctx, false);
            assertEquals("", problems.get(0), "a staged row is staged");
            // Petro's SSLUtil setup needs the JDK's java.exe, which this tools root lacks: said, after placing it.
            assertEquals("Petro Simulator Suite: unpacked, but no java.exe to run its setup with", problems.get(1));
        }
        assertEquals("the lab's own", Files.readString(staged.resolve("mine.txt")));
        assertEquals("k=v", Files.readString(tools.resolve("Petro_Simulator_Suite/config/sim.properties")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("unpacked build 60's copy into")), lines.toString());
    }

    @Test
    void noLoginIsOneProblemAndNothingIsDownloaded() {
        List<String> problems = SimStaging.stageMissingTools(tools, () -> {
            throw new ArtifactoryAuthError("ARTIFACTORY_USER", "ARTIFACTORY_PASSWORD");
        }, lines::add);
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void fetchSimsSaysEverythingIsStagedAndExitsZero() throws IOException {
        Zips.stageJava(tools, "Java17", "jakarta");
        Zips.stageJava(tools, "Java17v1.1", "javax");
        SimContext ctx = SimContext.of(tools, "");
        for (SimEntry row : Registry.all()) {
            Files.createDirectories(ctx.cwdFor(row));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new FetchSimsCommand(tools, () -> {
            throw new AssertionError("nothing is missing, so no login is asked for");
        }, new PrintStream(out, true, StandardCharsets.UTF_8), System.err).run(new String[0]);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Java 17 and every simulator are staged under"));
    }

    @Test
    void fetchSimsWithNoLoginExitsOneAndSaysHowToSignIn() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new FetchSimsCommand(tools, () -> {
            throw new ArtifactoryAuthError("ARTIFACTORY_USER", "ARTIFACTORY_PASSWORD");
        }, new PrintStream(out, true, StandardCharsets.UTF_8), System.err).run(new String[0]);
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, text);
        assertTrue(text.contains("not staged under " + tools), text);
        assertTrue(text.contains("FAIL"), text);
    }

    @Test
    void fetchSimsTakesNoFlags() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FetchSimsCommand(tools, () -> null, System.out,
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(new String[] {"--force"});
        assertEquals(2, code);
    }
}
