package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.BuildDiscovery.BuildName;

class BuildDiscoveryTest {

    private static final List<String> PSI = List.of("Petro_Suite_Installer.jar", "Auto_Upgrade.jar");

    @TempDir
    Path root;

    @Test
    void theRootIsTheDocumentedDefaultUnlessTheEnvironmentMovesIt() {
        assertEquals(Path.of("C:/CICD/Builds"), BuildDiscovery.buildsRoot(Map.of()));
        assertEquals(Path.of("C:/CICD/Builds"), BuildDiscovery.buildsRoot(Map.of("CAT_BUILDS_ROOT", "  ")));
        assertEquals(root, BuildDiscovery.buildsRoot(Map.of("CAT_BUILDS_ROOT", " " + root + " ")));
    }

    @Test
    void theLocalFolderNamesFollowPatsLayout() {
        assertEquals("Solutions65", BuildDiscovery.buildLabel("Solutions", "65"));
        assertEquals("master-268", BuildDiscovery.buildLabel("master", "268"));
        assertEquals("refs_tags_Base056.03.01-23116", BuildDiscovery.buildLabel("refs/tags/Base056.03.01", "23116"));
        assertEquals(root.resolve("Solutions65").resolve("buypass"),
                BuildDiscovery.buildDir("Solutions", "65", "buypass", root));
        assertEquals(root.resolve("master-268").resolve("buypass"),
                BuildDiscovery.buildDir("master", "268", "buypass", root));
    }

    @Test
    void aBuildFolderNameIsSplitIntoBranchAndNumber() {
        assertEquals(Optional.of(new BuildName("Solutions", "1082")), BuildDiscovery.parseBuildName("Solutions1082"));
        assertEquals(Optional.of(new BuildName("master", "268")), BuildDiscovery.parseBuildName("master-268"));
        assertEquals(Optional.of(new BuildName("PETROCORE-142198-nacs-retail", "42")),
                BuildDiscovery.parseBuildName("PETROCORE-142198-nacs-retail-42"));
        assertEquals(Optional.empty(), BuildDiscovery.parseBuildName("Base55.02.11"));
        assertEquals("Solutions1082", BuildDiscovery.buildName("1082"));
        assertEquals("Solutions1082", BuildDiscovery.buildName("Solutions1082"));
    }

    @Test
    void aNonNumericBuildIsRefusedByName() {
        BuildException error = assertThrows(BuildException.class, () -> BuildDiscovery.buildNumber("v1"));
        assertTrue(error.getMessage().contains("not a build number"));
    }

    @Test
    void theNewestBuildIsByOverlayDateNotFolderNumber() throws IOException {
        // Solutions 65 was built later than 1097 (the restart), so it is the newer build.
        BuildFixtures.stage(root, "Solutions1097", "buypass", BuildFixtures.at(2026, 9, 26, 7, 55),
                BuildDiscovery.OVERLAY, PSI.get(0), PSI.get(1));
        BuildFixtures.stage(root, "Solutions65", "buypass", BuildFixtures.at(2026, 10, 1, 7, 38),
                BuildDiscovery.OVERLAY, PSI.get(0), PSI.get(1));

        assertEquals(Optional.of(root.resolve("Solutions65").resolve("buypass")),
                BuildDiscovery.latestBuildDir(root, "buypass", PSI));
    }

    @Test
    void aBuildMissingTheFilesTheUpgradeNeedsIsPassedOver() throws IOException {
        BuildFixtures.stage(root, "Solutions1052", "Buypass", BuildFixtures.at(2026, 10, 2, 9, 0),
                BuildDiscovery.OVERLAY, "Auto_Upgrade.jar");
        BuildFixtures.stage(root, "Solutions900", "Buypass", BuildFixtures.at(2026, 9, 1, 9, 0),
                BuildDiscovery.OVERLAY, PSI.get(0), PSI.get(1));

        assertEquals(Optional.of(root.resolve("Solutions900").resolve("Buypass")),
                BuildDiscovery.latestBuildDir(root, null, PSI));
        // Without the PSI jar the higher build qualifies.
        assertEquals(Optional.of(root.resolve("Solutions1052").resolve("Buypass")),
                BuildDiscovery.latestBuildDir(root, null, List.of("Auto_Upgrade.jar")));
    }

    @Test
    void theDistMatchesCaseInsensitivelyAndNarrowsTheSearch() throws IOException {
        BuildFixtures.stage(root, "Solutions1052", "Buypass", BuildFixtures.at(2026, 10, 2, 9, 0),
                BuildDiscovery.OVERLAY, PSI.get(0), PSI.get(1));
        BuildFixtures.stage(root, "Solutions900", "othersdist", BuildFixtures.at(2026, 10, 3, 9, 0),
                BuildDiscovery.OVERLAY, PSI.get(0), PSI.get(1));

        assertEquals(Optional.of(root.resolve("Solutions1052").resolve("Buypass")),
                BuildDiscovery.latestBuildDir(root, "buypass", PSI));
        assertEquals(Optional.empty(), BuildDiscovery.latestBuildDir(root, "nosuchdist", PSI));
    }

    @Test
    void anAbsentOrEmptyRootHasNoBuildAndNoDist() {
        assertEquals(Optional.empty(), BuildDiscovery.latestBuildDir(root.resolve("absent"), null, PSI));
        assertEquals(List.of(), BuildDiscovery.localDists(root.resolve("absent")));
        assertEquals(List.of(), BuildDiscovery.localBuildDirs(root.resolve("absent"), null, List.of()));
    }

    @Test
    void eachDistWithAnOverlayIsListedOnceWhateverItsCase() throws IOException {
        BuildFixtures.stage(root, "Solutions90", "Buypass", BuildFixtures.at(2026, 10, 6, 9, 0),
                BuildDiscovery.OVERLAY, "Auto_Upgrade.jar");
        BuildFixtures.stage(root, "master-268", "buypass", BuildFixtures.at(2026, 10, 6, 9, 0), BuildDiscovery.OVERLAY);
        BuildFixtures.stage(root, "Solutions92", "chevron", BuildFixtures.at(2026, 10, 6, 9, 0), BuildDiscovery.OVERLAY);
        BuildFixtures.stage(root, "Solutions875", "hps", BuildFixtures.at(2026, 10, 6, 9, 0), "Auto_Upgrade.jar");
        // PATS's older layout: an overlay, but its name is not one word, so it is not a dist.
        BuildFixtures.stage(root, "Base55.02.11", "buypass_V55.02.11_devSigned_CD",
                BuildFixtures.at(2026, 10, 6, 9, 0), BuildDiscovery.OVERLAY);

        List<String> dists = BuildDiscovery.localDists(root);

        assertEquals(2, dists.size());
        assertTrue(dists.contains("chevron"));
        assertTrue(dists.stream().anyMatch(d -> d.equalsIgnoreCase("buypass")));
        assertFalse(dists.contains("hps"));
    }

    @Test
    void aBuildDateIsTheOverlaysNewestEntryAndBlankWithNoOverlay() throws IOException {
        Path dist = BuildFixtures.stage(root, "Solutions65", "buypass", BuildFixtures.at(2026, 10, 1, 7, 38));
        assertEquals("", BuildDiscovery.buildDate(dist));

        Files.write(dist.resolve(BuildDiscovery.OVERLAY), BuildFixtures.overlay(BuildFixtures.at(2026, 10, 1, 7, 38)));
        assertEquals("2026-10-01 07:38", BuildDiscovery.buildDate(dist));
    }
}
