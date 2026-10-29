package com.verifone.cat.build;

import static com.verifone.cat.build.CommanderBuildFetchTest.EXE;
import static com.verifone.cat.build.CommanderBuildFetchTest.installable;
import static com.verifone.cat.build.CommanderBuildFetchTest.petro;
import static com.verifone.cat.build.CommanderBuildFetchTest.viper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code cat-java build} and {@code cat-java builds} through their command lines, against {@link FakeArtifactory}: the
 * Python's {@code test_build_cli}. {@code CAT_BUILDS_ROOT} is a temp folder; no test reaches the real server.
 */
class BuildCommandTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private static Map<String, Object> tree() {
        Map<String, Object> solutions = new LinkedHashMap<>();
        solutions.put("1097", FakeArtifactory.dir("2026-09-26T12:08:27.288Z", "buypass", installable("s1097", "")));
        solutions.put("94", FakeArtifactory.dir("2026-10-07T15:18:12.345Z", "buypass", installable("s94", "")));
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("60", FakeArtifactory.dir("2026-10-07T14:18:00Z", "foundation-buypass",
                installable("f60", "2026-10-07T14:19:00Z"), "simulators", FakeArtifactory.dir("")));
        master.put("58", FakeArtifactory.dir("2026-10-06T14:18:00Z", "foundation-chevron",
                installable("c58", "2026-10-06T14:19:00Z")));
        master.put("241", FakeArtifactory.dir("2026-10-06T14:06:14Z", "viper-buypass",
                viper("v", "2026-10-06T14:06:14Z", EXE)));
        Map<String, Object> branches = new LinkedHashMap<>();
        branches.put("master", master);
        branches.put("Solutions", solutions);
        branches.put("PETROCORE-142214", FakeArtifactory.dir(""));
        branches.put("null", FakeArtifactory.dir(""));
        return petro(branches);
    }

    private Map<String, String> env() {
        return Map.of("CAT_BUILDS_ROOT", root.toString());
    }

    private int build(FakeArtifactory fake, String... argv) {
        return new BuildCommand((env, timeout) -> fake.client(), new CommanderBuildFetchTest.FakeMerger())
                .run(argv, env(), out, err);
    }

    private int builds(FakeArtifactory fake, String... argv) {
        return new BuildsCommand((env, timeout) -> fake.client()).run(argv, env(), out, err);
    }

    private String takeOut() {
        String text = outBytes.toString(StandardCharsets.UTF_8);
        outBytes.reset();
        return text;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void solutionsIsBranchSolutionsBuildN() throws IOException {
        try (FakeArtifactory fake = new FakeArtifactory(tree())) {
            assertEquals(0, build(fake, "build", "--solutions", "94", "--dist", "buypass"));
            Path dest = root.resolve("Solutions94").resolve("buypass");
            assertEquals(List.of("Auto_Upgrade.jar", "Petro_Suite_Installer.jar", "overlay.zip"), names(dest));
            assertTrue(takeOut().contains("build ready: " + dest));
        }
    }

    @Test
    void branchAndBuildDownloadThatBuildForTheMode() throws IOException {
        try (FakeArtifactory fake = new FakeArtifactory(tree())) {
            assertEquals(0, build(fake, "--branch", "master", "--build", "60", "--dist", "buypass", "--mode",
                    "classic"));
            assertEquals(List.of("Auto_Upgrade.jar", "overlay.zip"), names(root.resolve("master-60").resolve("buypass")));
        }
    }

    @Test
    void noBuildMeansTheNewestOnTheBranchWithTheDist() {
        try (FakeArtifactory fake = new FakeArtifactory(tree())) {
            assertEquals(0, build(fake, "--branch", "Solutions", "--dist", "buypass"));
            assertTrue(Files.isRegularFile(root.resolve("Solutions94").resolve("buypass").resolve("overlay.zip")),
                    "94 is newer than 1097");
            assertTrue(takeOut().contains("no --build given: Solutions's newest build with buypass is 94"));
        }
    }

    @Test
    void contradictoryOrMissingFlagsAreUsageErrorsThatAskNothing() {
        try (FakeArtifactory fake = new FakeArtifactory(tree())) {
            for (String[] argv : List.of(new String[] {"--dist", "buypass"},
                    new String[] {"--solutions", "94", "--branch", "master", "--dist", "buypass"},
                    new String[] {"--solutions", "94", "--build", "95", "--dist", "buypass"},
                    new String[] {"--branch", "master", "--foundation-branch", "master", "--dist", "buypass"},
                    new String[] {"--branch", "master"},
                    new String[] {"--branch", "master", "--dist", "buypass", "--mode", "vhq"})) {
                errBytes.reset();
                assertEquals(2, build(fake, argv), String.join(" ", argv));
                assertFalse(errBytes.toString(StandardCharsets.UTF_8).isEmpty());
            }
            assertEquals(2, builds(fake, "--build", "60"));
            assertTrue(fake.requests.isEmpty(), fake.requests.toString());
        }
    }

    @Test
    void aMissingBuildIsOneErrorLineNotAStackTrace() {
        try (FakeArtifactory fake = new FakeArtifactory(tree())) {
            assertEquals(1, build(fake, "--branch", "master", "--build", "61", "--dist", "buypass"));
            assertTrue(takeOut().contains("ERROR cat-java build master 61: master has no build 61 on Artifactory"));
        }
    }

    @Test
    void noLoginIsReportedNotRaised() {
        BuildCommand.Clients none = (env, timeout) -> {
            throw new ArtifactoryAuthError("ARTIFACTORY_USER", "ARTIFACTORY_PASSWORD");
        };
        assertEquals(1, new BuildCommand(none, null).run(new String[] {"--solutions", "94", "--dist", "buypass"},
                env(), out, err));
        assertEquals(1, new BuildsCommand(none).run(new String[0], env(), out, err));
        String text = takeOut();
        assertTrue(text.contains("artifactory-login"), text);
        assertTrue(text.startsWith("ERROR cat-java build Solutions 94: "), text);
    }

    @Test
    void buildsListsBranchesThenBuildsThenContents() {
        try (FakeArtifactory fake = new FakeArtifactory(tree())) {
            assertEquals(0, builds(fake));
            assertEquals(List.of("Solutions", "master", "PETROCORE-142214"), List.of(takeOut().split("\\s+")));

            assertEquals(0, builds(fake, "--branch", "Solutions"));
            List<String> lines = List.of(takeOut().split("\\R"));
            assertEquals(List.of("94", "1097"), lines.stream().map(l -> l.strip().split("\\s+")[0]).toList());
            assertTrue(lines.get(0).contains("2026-10-07 15:18 UTC"), lines.get(0));

            assertEquals(0, builds(fake, "--branch", "master", "--dist", "chevron"));
            List<String> only = List.of(takeOut().split("\\R"));
            assertEquals(1, only.size(), only.toString());
            assertTrue(only.get(0).strip().startsWith("58") && only.get(0).endsWith("foundation-chevron"));

            assertEquals(0, builds(fake, "--branch", "master", "--build", "60"));
            String contents = takeOut();
            assertTrue(contents.contains("master 60 foundation-buypass: overlay and installer jars"), contents);
            assertTrue(contents.contains("simulators/: yes") && contents.contains("automation/: no"), contents);
        }
    }

    @Test
    void aViperBuildWithNoPairIsOneErrorLineAskingForAFoundation() {
        try (FakeArtifactory fake = new FakeArtifactory(tree())) {
            assertEquals(1, build(fake, "--branch", "master", "--build", "241", "--dist", "buypass"));
            List<String> lines = List.of(takeOut().strip().split("\\R"));
            assertEquals(1, lines.size(), lines.toString());
            assertTrue(lines.get(0).startsWith("ERROR cat-java build master 241: master 241 viper-buypass has no "),
                    lines.get(0));
            assertTrue(lines.get(0).contains("--foundation-branch <branch> --foundation-build <n>"));
            assertTrue(fake.downloads().isEmpty());
            assertFalse(Files.exists(root.resolve("master-241")));
        }
    }
}
