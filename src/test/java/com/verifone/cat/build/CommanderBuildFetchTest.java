package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.launch.UpgradeMode;

/**
 * {@link CommanderBuildFetch} against the loopback Artifactory fake, laid out as the Python's
 * {@code test_build_nightly} lays it out (real numbers and dates from 2026-10-07): evidence about this code, not the
 * server. The merger here is a stand-in by contract; {@link OverlayMergeTest} tests the real one.
 */
class CommanderBuildFetchTest {

    static final String EXE = "buypass_V57.02.00_devSigned_CD.exe";
    private static final UpgradeMode PSI = UpgradeMode.NEW_INSTALL;

    @TempDir
    Path root;

    static Map<String, Object> installable(String tag, String date) {
        return FakeArtifactory.dir(date, "overlay.zip", (tag + "-overlay").getBytes(),
                "Petro_Suite_Installer.jar", "psi".getBytes(), "Auto_Upgrade.jar", "au".getBytes(),
                "Send_License_Package.jar", "x".getBytes(), EXE, "e".getBytes());
    }

    static Map<String, Object> viper(String tag, String date, String exe) {
        return FakeArtifactory.dir(date, "overlay.zip", (tag + "-overlay").getBytes(), exe, "e".getBytes());
    }

    static Map<String, Object> petro(Map<String, Object> branches) {
        return Map.of("RMS_Nightly_CLW", Map.of("Petro", branches));
    }

    /** master 60 (foundation, 14:19), 268 (viper, 14:27) and 171 (foundation, days before). */
    static Map<String, Object> viperTree() {
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("60", FakeArtifactory.dir("2026-10-07T14:18:00Z", "foundation-buypass",
                installable("f60", "2026-10-07T14:19:00Z")));
        master.put("268", FakeArtifactory.dir("2026-10-07T14:27:00Z", "viper-buypass",
                viper("v268", "2026-10-07T14:27:00Z", EXE)));
        master.put("171", FakeArtifactory.dir("2026-10-02T20:32:00Z", "foundation-buypass",
                installable("f171", "2026-10-02T20:32:00Z")));
        return petro(Map.of("master", master));
    }

    /** Stands in for {@link OverlayMerge#merge}: records its inputs and writes a marker overlay. */
    static final class FakeMerger implements OverlayMerge.Merger {
        final List<String> calls = new ArrayList<>();
        final List<OverlayMerge.PackageType> types = new ArrayList<>();
        boolean fail;

        @Override
        public Path merge(Path foundationZip, Path viperZip, Path destZip, OverlayMerge.PackageType type,
                java.util.function.Consumer<String> log) throws IOException {
            calls.add(Files.readString(foundationZip) + " + " + Files.readString(viperZip));
            types.add(type);
            log.accept("fake merge");
            if (fail) {
                Files.writeString(destZip, "half");
                throw new IllegalStateException("merge blew up");
            }
            Files.writeString(destZip, "merged:" + Files.readString(viperZip));
            return destZip;
        }
    }

    private Path fetch(FakeArtifactory fake, String branch, String build, String dist, UpgradeMode mode,
            List<String> foundation, String flavour, List<String> log, OverlayMerge.Merger merge) {
        return CommanderBuildFetch.fetchCommanderBuild(fake.client(), branch, build, dist, mode, foundation, flavour,
                root, log::add, merge);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aSolutionsBuildIsFetchedFromItsFolderAsListedCaseInsensitively() throws IOException {
        byte[] overlay = BuildFixtures.overlay(BuildFixtures.at(2026, 10, 7, 9, 0));
        Map<String, Object> dist = Map.of("overlay.zip", overlay, "Petro_Suite_Installer.jar", "psi".getBytes(),
                "Auto_Upgrade.jar", "au".getBytes());
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("Solutions", Map.of("134",
                Map.of("buypass", dist)))))) {
            List<String> log = new ArrayList<>();
            Path dir = CommanderBuildFetch.fetch(fake.client(), "Solutions", "134", "Buypass",
                    CommanderBuildFetch.PSI_FILES, root, log::add);

            assertEquals(root.resolve("Solutions134").resolve("Buypass"), dir);
            assertArrayEquals(overlay, Files.readAllBytes(dir.resolve("overlay.zip")));
            assertArrayEquals("psi".getBytes(), Files.readAllBytes(dir.resolve("Petro_Suite_Installer.jar")));
            assertEquals("Solutions 134 buypass: overlay and installer jars", log.get(0));
            assertEquals("  overlay.zip: downloading " + String.format("%,d", overlay.length) + " bytes", log.get(1));
        }
    }

    @Test
    void aFoundationBuildInstallsAsItIsWithTheModesJars() throws IOException {
        try (FakeArtifactory fake = new FakeArtifactory(viperTree())) {
            Path dest = fetch(fake, "master", "60", "buypass", UpgradeMode.CLASSIC, null, null, new ArrayList<>(),
                    new FakeMerger());
            assertEquals(root.resolve("master-60").resolve("buypass"), dest);
            assertEquals(List.of("Auto_Upgrade.jar", "overlay.zip"), names(dest));
            assertEquals(dest, BuildDiscovery.latestBuildDir(root, "buypass", InstallerJars.CLASSIC).orElseThrow());
        }
    }

    @Test
    void aMissingDistBuildOrModeIsNamed() {
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of(
                "Solutions", Map.of("92", Map.of("chevron", Map.of("overlay.zip", new byte[1]))))))) {
            BuildException e = assertThrows(BuildException.class, () -> fetch(fake, "Solutions", "92", "buypass",
                    PSI, null, null, new ArrayList<>(), new FakeMerger()));
            assertEquals("Solutions 92 has no buypass; it carries chevron", e.getMessage());
            BuildException gone = assertThrows(BuildException.class, () -> fetch(fake, "Solutions", "1", "buypass",
                    PSI, null, null, new ArrayList<>(), new FakeMerger()));
            assertEquals("Solutions has no build 1 on Artifactory", gone.getMessage());
            BuildException jar = assertThrows(BuildException.class, () -> fetch(fake, "Solutions", "92", "chevron",
                    PSI, null, null, new ArrayList<>(), new FakeMerger()));
            assertTrue(jar.getMessage().endsWith("chevron/Petro_Suite_Installer.jar is not on Artifactory"),
                    jar.getMessage());
            IllegalArgumentException skip = assertThrows(IllegalArgumentException.class, () -> fetch(fake,
                    "Solutions", "92", "chevron", UpgradeMode.SKIP, null, null, new ArrayList<>(), new FakeMerger()));
            assertTrue(skip.getMessage().contains("installs no build"));
        }
    }

    @Test
    void aViperOnlyBuildIsMergedOntoTheChosenFoundationThenReused() throws IOException {
        try (FakeArtifactory fake = new FakeArtifactory(viperTree())) {
            FakeMerger merger = new FakeMerger();
            List<String> log = new ArrayList<>();
            Path dest = fetch(fake, "master", "268", "buypass", PSI, List.of("master", "171"), null, log, merger);

            assertEquals(root.resolve("master-268").resolve("buypass"), dest);
            assertEquals(List.of("f171-overlay + v268-overlay"), merger.calls);
            assertEquals(List.of(CommanderBuildFetch.MERGE_STAMP, "Auto_Upgrade.jar", "Petro_Suite_Installer.jar",
                    "overlay.zip"), names(dest));
            assertEquals("merged:v268-overlay", Files.readString(dest.resolve("overlay.zip")));
            JsonNode stamp = new ObjectMapper().readTree(dest.resolve(CommanderBuildFetch.MERGE_STAMP).toFile());
            assertTrue(stamp.get("foundation").get("path").asText()
                    .endsWith("master/171/foundation-buypass/overlay.zip"));
            assertEquals("PETRO_SUITE_INSTALLER", stamp.get("package_type").asText());
            assertFalse(Files.exists(root.resolve(".inputs")), "the two input overlays are not left behind");
            assertTrue(log.contains("fake merge"), log.toString());
            assertTrue(log.contains("merging master 268 viper-buypass onto master 171 foundation-buypass, for "
                    + "PETRO_SUITE_INSTALLER"), log.toString());

            fake.requests.clear();
            List<String> again = new ArrayList<>();
            fetch(fake, "master", "268", "buypass", PSI, List.of("master", "171"), null, again, merger);
            assertEquals(1, merger.calls.size(), "the same pick does not merge again");
            assertTrue(fake.downloads().stream().noneMatch(r -> r.endsWith("overlay.zip")), fake.downloads().toString());
            assertTrue(again.contains("  overlay.zip: already merged from these two overlays, for "
                    + "PETRO_SUITE_INSTALLER"), again.toString());
        }
    }

    @Test
    void withNoFoundationThePairedOneIsUsedAndSaid() {
        try (FakeArtifactory fake = new FakeArtifactory(viperTree())) {
            FakeMerger merger = new FakeMerger();
            List<String> log = new ArrayList<>();
            fetch(fake, "master", "268", "buypass", PSI, null, null, log, merger);
            assertEquals("f60-overlay + v268-overlay", merger.calls.get(0));
            assertTrue(log.contains("master 268 viper-buypass needs a foundation and none was chosen; paired by exe "
                    + "name and build time: foundation-buypass in master 60, built 8 min before the viper"),
                    log.toString());
        }
    }

    @Test
    void aViperWithNoPairIsRefusedBeforeAnyDownload() {
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("241", FakeArtifactory.dir("2026-10-06T14:06:14Z", "viper-buypass",
                viper("v", "2026-10-06T14:06:14Z", EXE)));
        master.put("56", FakeArtifactory.dir("2026-10-06T10:29:15Z", "foundation-buypass",
                installable("f56", "2026-10-06T10:29:15Z")));
        master.put("60", FakeArtifactory.dir("2026-10-07T14:19:16Z", "foundation-buypass",
                installable("f60", "2026-10-07T14:19:16Z")));
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("master", master)))) {
            FakeMerger merger = new FakeMerger();
            BuildException e = assertThrows(BuildException.class,
                    () -> fetch(fake, "master", "241", "buypass", PSI, null, null, new ArrayList<>(), merger));
            assertTrue(e.getMessage().startsWith("master 241 viper-buypass has no foundation to pair with: no "
                    + "installable buypass folder on master was published within 2 h of it (2026-10-06 14:06 UTC);"),
                    e.getMessage());
            assertTrue(e.getMessage().contains("--foundation-branch <branch> --foundation-build <n>"));
            assertTrue(merger.calls.isEmpty());
            assertTrue(fake.downloads().isEmpty());
            assertFalse(Files.exists(root.resolve("master-241")));
        }
    }

    @Test
    void aModeChangeMergesAgainRatherThanReuseTheStamp() throws IOException {
        try (FakeArtifactory fake = new FakeArtifactory(viperTree())) {
            FakeMerger merger = new FakeMerger();
            List<String> log = new ArrayList<>();
            for (UpgradeMode mode : List.of(PSI, UpgradeMode.CLASSIC, UpgradeMode.CLASSIC)) {
                fetch(fake, "master", "268", "buypass", mode, List.of("master", "60"), null, log, merger);
            }
            assertEquals(List.of(OverlayMerge.PackageType.PETRO_SUITE_INSTALLER,
                    OverlayMerge.PackageType.AUTO_UPGRADE), merger.types);
            assertTrue(log.contains("  overlay.zip: was merged for PETRO_SUITE_INSTALLER; merging again"),
                    log.toString());
            assertTrue(log.contains("  overlay.zip: already merged from these two overlays, for AUTO_UPGRADE"),
                    log.toString());
        }
    }

    @Test
    void aStampWithNoPackageTypeMergesAgain() throws IOException {
        try (FakeArtifactory fake = new FakeArtifactory(viperTree())) {
            FakeMerger merger = new FakeMerger();
            fetch(fake, "master", "268", "buypass", PSI, List.of("master", "60"), null, new ArrayList<>(), merger);
            Path stamp = root.resolve("master-268").resolve("buypass").resolve(CommanderBuildFetch.MERGE_STAMP);
            ObjectMapper json = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode old =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(stamp.toFile());
            old.remove("package_type");
            Files.writeString(stamp, json.writeValueAsString(old));
            fetch(fake, "master", "268", "buypass", PSI, List.of("master", "60"), null, new ArrayList<>(), merger);
            assertEquals(2, merger.calls.size());
        }
    }

    @Test
    void aFailedMergeLeavesNoOverlayToInstall() throws IOException {
        try (FakeArtifactory fake = new FakeArtifactory(viperTree())) {
            FakeMerger good = new FakeMerger();
            fetch(fake, "master", "268", "buypass", PSI, List.of("master", "60"), null, new ArrayList<>(), good);
            FakeMerger bad = new FakeMerger();
            bad.fail = true;
            assertThrows(IllegalStateException.class, () -> fetch(fake, "master", "268", "buypass", PSI,
                    List.of("master", "171"), null, new ArrayList<>(), bad));
            Path dest = root.resolve("master-268").resolve("buypass");
            assertFalse(Files.exists(dest.resolve("overlay.zip")), "the other pair's overlay is gone too");
            assertFalse(Files.exists(dest.resolve(CommanderBuildFetch.MERGE_STAMP)));
            assertFalse(Files.exists(dest.resolve("overlay.merging.zip")));
            assertTrue(BuildDiscovery.latestBuildDir(root, "buypass", InstallerJars.NEW_INSTALL).isEmpty());
        }
    }

    @Test
    void anExplicitFoundationThatCannotBeOneIsRefused() {
        try (FakeArtifactory fake = new FakeArtifactory(viperTree())) {
            BuildException e = assertThrows(BuildException.class, () -> fetch(fake, "master", "268", "buypass", PSI,
                    List.of("master", "268"), null, new ArrayList<>(), new FakeMerger()));
            assertTrue(e.getMessage().startsWith("master 268 cannot be the foundation: master 268 viper-buypass: "
                    + "viper-only"), e.getMessage());
        }
    }

    @Test
    void aBuildHoldingBothInstallsTheFoundationUnlessToldTheFlavour() throws IOException {
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("440", FakeArtifactory.dir("2026-07-03T00:00:00Z",
                "foundation-buypass", installable("f440", "2026-07-03T00:00:00Z"),
                "viper-buypass", viper("v440", "2026-07-03T00:30:00Z", EXE)));
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("master", master)))) {
            FakeMerger merger = new FakeMerger();
            List<String> log = new ArrayList<>();
            Path dest = fetch(fake, "master", "440", "buypass", PSI, null, null, log, merger);
            assertEquals("f440-overlay", Files.readString(dest.resolve("overlay.zip")));
            assertEquals("master 440 foundation-buypass: overlay and installer jars; its viper-buypass was "
                    + "published separately and is not used", log.get(0));
            assertTrue(merger.calls.isEmpty());

            fetch(fake, "master", "440", "buypass", PSI, null, "viper", new ArrayList<>(), merger);
            assertEquals(List.of("f440-overlay + v440-overlay"), merger.calls, "paired with its own build's foundation");
            BuildException flavour = assertThrows(BuildException.class, () -> fetch(fake, "master", "440", "buypass",
                    PSI, null, "nbs", new ArrayList<>(), merger));
            assertEquals("unknown flavour 'nbs'; one of plain, foundation, sc, retail, viper", flavour.getMessage());
        }
    }
}
