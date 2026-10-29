package com.verifone.cat.build;

import static com.verifone.cat.build.CommanderBuildFetchTest.EXE;
import static com.verifone.cat.build.CommanderBuildFetchTest.installable;
import static com.verifone.cat.build.CommanderBuildFetchTest.petro;
import static com.verifone.cat.build.CommanderBuildFetchTest.viper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The nightly layout: {@link BuildContents}'s classification, {@link BuildCatalogue}'s newest build and default
 * foundation, and {@link FoundationPair}'s pairing. The Python's {@code test_build_nightly}, against
 * {@link FakeArtifactory} with the real numbers, dates and {@code .exe} names of 2026-10-07.
 */
class NightlyBuildsTest {

    private static Map<String, Object> dir(Object... keysAndValues) {
        return FakeArtifactory.dir("", keysAndValues);
    }

    private static Map<String, Object> nodate(String tag) {
        return installable(tag, "");
    }

    @Test
    void flavoursAreReadOffTheFolderNames() {
        Map<String, Object> branches = new LinkedHashMap<>();
        branches.put("Solutions", dir("94", dir("buypass", nodate("s94"), "chevron", nodate("c94"))));
        branches.put("master", dir(
                "60", dir("foundation-buypass", nodate("f60"), "foundation-base", dir("x.tgz", new byte[1]),
                        "simulators", dir(), "automation", dir(), "TopazERP", dir()),
                "268", dir("viper-buypass", viper("v268", "", EXE), "viper-base", dir()),
                "440", dir("foundation-buypass", nodate("f440"), "viper-buypass", viper("v440", "", EXE))));
        branches.put("master056.03", dir("23855", dir("sc-buypass", nodate("sc"), "sc-testFep", dir())));
        branches.put("PETROCORE-142198-nacs-retail", dir("203", dir("retail-generic", dir(), "retail-base", dir())));
        branches.put("null", dir("272", dir("viper.core.app-V1.jar", new byte[1])));
        try (FakeArtifactory fake = new FakeArtifactory(petro(branches))) {
            BuildCatalogue catalogue = new BuildCatalogue(fake.client());
            BuildContents solutions = catalogue.contents("Solutions", "94");
            BuildContents master60 = catalogue.contents("master", "60");
            BuildContents viperOnly = catalogue.contents("master", "268");
            BuildContents both = catalogue.contents("master", "440");
            BuildContents sc = catalogue.contents("master056.03", "23855");
            BuildContents retail = catalogue.contents("PETROCORE-142198-nacs-retail", "203");
            BuildContents loose = catalogue.contents("null", "272");

            assertEquals(List.of("buypass", "chevron"), solutions.dists());
            assertEquals("plain", solutions.sourceFor("buypass", null).flavour());
            assertFalse(solutions.simulators());

            assertEquals(List.of("buypass"), master60.dists(), "foundation-base is not a dist");
            assertEquals("foundation-buypass", master60.sourceFor("buypass", null).folder());
            assertTrue(master60.simulators() && master60.automation());
            assertEquals(Set.of("foundation-base", "TopazERP"), Set.copyOf(master60.other()));
            assertTrue(master60.has("simulators") && master60.has("TopazERP"));

            assertTrue(viperOnly.viperOnly("buypass"));
            assertTrue(viperOnly.describe("buypass").contains("viper-only (SOS)"));
            assertFalse(viperOnly.has("simulators"));

            assertEquals("foundation", both.sourceFor("buypass", null).flavour());
            assertEquals("viper-buypass", both.sourceFor("buypass", "viper").folder());
            assertTrue(both.describe("buypass").contains("published separately"));

            assertEquals("sc", sc.sourceFor("buypass", null).flavour());
            assertEquals("sc-testFep", sc.sourceFor("testfep", null).folder(), "the dist is matched case-blind");

            assertEquals(List.of("generic"), retail.dists());
            assertEquals("PETROCORE-142198-nacs-retail 203 has no buypass; it carries generic",
                    retail.describe("buypass"));
            assertTrue(loose.describe("buypass").contains("carries no Commander build"), loose.describe("buypass"));
        }
    }

    @Test
    void aMissingBuildOrABadNameIsANamedError() {
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("master", dir("60", dir()))))) {
            BuildCatalogue catalogue = new BuildCatalogue(fake.client());
            assertEquals("master has no build 61 on Artifactory",
                    assertThrows(BuildException.class, () -> catalogue.contents("master", "61")).getMessage());
            assertTrue(assertThrows(BuildException.class, () -> catalogue.contents("master", "latest"))
                    .getMessage().contains("not a build number"));
            assertTrue(assertThrows(BuildException.class, () -> catalogue.contents("../other", "60"))
                    .getMessage().contains("not a branch name"));
        }
    }

    @Test
    void theNewestBuildWithADistAndTheDefaultFoundationGoByDate() {
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("268", FakeArtifactory.dir("2026-10-07T14:27:00Z", "viper-buypass",
                viper("v", "2026-10-07T14:27:00Z", EXE)));
        master.put("60", FakeArtifactory.dir("2026-10-07T14:18:00Z", "foundation-buypass",
                installable("f", "2026-10-07T14:19:00Z")));
        master.put("264", FakeArtifactory.dir("2026-10-07T13:48:00Z", "foundation-buypass",
                installable("f", "2026-10-07T13:49:00Z")));
        master.put("19", FakeArtifactory.dir("2026-09-28T09:01:00Z", "viper-buypass",
                viper("v", "2026-09-28T09:01:00Z", EXE), "foundation-buypass", installable("f", "2026-10-08T00:00:00Z")));
        master.put("70", FakeArtifactory.dir("2026-10-09T00:00:00Z", "foundation-chevron", installable("c", "")));
        Map<String, Object> branches = new LinkedHashMap<>();
        branches.put("master", master);
        branches.put("PETROCORE-142214", dir(
                "231", FakeArtifactory.dir("2026-10-06T09:27:00Z", "viper-buypass", viper("v", "", EXE)),
                "140", FakeArtifactory.dir("2026-10-01T10:54:00Z", "foundation-buypass",
                        installable("f", "2026-10-01T10:54:00Z"))));
        branches.put("PETROCORE-1", dir("5", FakeArtifactory.dir("2026-10-01T00:00:00Z", "viper-buypass",
                viper("v", "", EXE))));
        try (FakeArtifactory fake = new FakeArtifactory(petro(branches))) {
            BuildCatalogue catalogue = new BuildCatalogue(fake.client());
            assertEquals("268", catalogue.newestBuildFor("master", "buypass"));
            assertEquals("70", catalogue.newestBuildFor("master", "chevron"));
            assertEquals(List.of("master", "19"), catalogue.defaultFoundation("master", "buypass"));
            assertEquals(List.of("PETROCORE-142214", "140"), catalogue.defaultFoundation("PETROCORE-142214", "buypass"));
            assertEquals(List.of("master", "19"), catalogue.defaultFoundation("PETROCORE-1", "buypass"));
            assertTrue(assertThrows(BuildException.class, () -> catalogue.defaultFoundation("PETROCORE-1", "nts"))
                    .getMessage().contains("no foundation-nts"));
            assertEquals("no build on master carries nts",
                    assertThrows(BuildException.class, () -> catalogue.newestBuildFor("master", "nts")).getMessage());
            List<BuildCatalogue.BuildWith> with = catalogue.buildsWith("master", "buypass");
            assertEquals(List.of("268", "60", "264", "19"), with.stream().map(w -> w.build().build()).toList());
        }
    }

    /** master057.01 as listed on 2026-10-07: real numbers, dates and {@code .exe} names. */
    private static Map<String, Object> releaseTree() {
        String v0100 = "buypass_V57.01.00_devSigned_CD.exe";
        String v0101 = "buypass_V57.01.01_devSigned_CD.exe";
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("397", FakeArtifactory.dir("2026-07-15T02:50:44.750Z", "viper-buypass",
                viper("v397", "2026-07-15T02:50:44.750Z", v0100)));
        line.put("454", FakeArtifactory.dir("2026-07-15T03:40:00.000Z", "foundation-buypass",
                installableWith("f454", "2026-07-15T03:44:52.658Z", v0100)));
        line.put("23111", FakeArtifactory.dir("2026-08-06T09:19:42.969Z", "viper-buypass",
                viper("v23111", "2026-08-06T09:19:42.969Z", v0101)));
        line.put("23110", FakeArtifactory.dir("2026-08-06T09:38:05.813Z", "foundation-buypass",
                installableWith("f23110", "2026-08-06T09:38:05.813Z", v0101)));
        line.put("29", FakeArtifactory.dir("2026-09-28T11:49:42.113Z", "viper-buypass",
                viper("v29", "2026-09-28T11:49:42.113Z", v0101)));
        line.put("28", FakeArtifactory.dir("2026-09-28T12:05:21.344Z", "foundation-buypass",
                installableWith("f28", "2026-09-28T12:05:21.344Z", v0101)));
        return petro(Map.of("master057.01", line));
    }

    private static Map<String, Object> installableWith(String tag, String date, String exe) {
        return FakeArtifactory.dir(date, "overlay.zip", tag.getBytes(), "Petro_Suite_Installer.jar", "psi".getBytes(),
                "Auto_Upgrade.jar", "au".getBytes(), exe, "e".getBytes());
    }

    @Test
    void anAdjacentPairIsFoundByExeAndTime() {
        try (FakeArtifactory fake = new FakeArtifactory(releaseTree())) {
            FoundationPair pair = FoundationPair.paired(fake.client(), "master057.01", "23111", "buypass");
            assertEquals(List.of("master057.01", "23110"), pair.foundation());
            assertEquals("foundation-buypass", pair.folder());
            assertEquals("buypass_V57.01.01_devSigned_CD.exe", pair.exe());
            assertEquals(18, pair.minutes());
            assertEquals("paired by exe name and build time: foundation-buypass in master057.01 23110, built 18 min "
                    + "after the viper", pair.describe());
            assertEquals(3, fake.requests.size(), fake.requests.toString());
            assertTrue(fake.requests.get(0).endsWith("master057.01?list&deep=1&depth=2&listFolders=1"),
                    fake.requests.get(0));
        }
    }

    @Test
    void aNonAdjacentPairAndOneAcrossTheNumberRestart() {
        try (FakeArtifactory fake = new FakeArtifactory(releaseTree())) {
            assertEquals("454", FoundationPair.paired(fake.client(), "master057.01", "397", "buypass").build());
            assertEquals("28", FoundationPair.paired(fake.client(), "master057.01", "29", "buypass").build());
        }
    }

    @Test
    void theNearestWithAnotherExeVersionIsPassedOver() {
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("440", FakeArtifactory.dir("2026-07-03T10:00:00Z",
                "viper-buypass", viper("v440", "2026-10-07T10:00:00Z", EXE),
                "foundation-buypass", installableWith("f440", "2026-10-07T10:05:00Z",
                        "buypass_V57.01.00_devSigned_CD.exe")));
        master.put("441", FakeArtifactory.dir("2026-10-07T10:30:00Z", "foundation-buypass",
                installable("f441", "2026-10-07T10:30:00Z")));
        master.put("999", FakeArtifactory.dir("2026-10-07T10:01:00Z", "foundation-buypass", installable("f999", "")));
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("master", master)))) {
            FoundationPair pair = FoundationPair.paired(fake.client(), "master", "440", "buypass");
            assertEquals("441", pair.build());
            assertEquals(30, pair.minutes());
            assertEquals(4, fake.requests.size(), "tree, viper folder, 440's foundation, 441's");
        }
    }

    @Test
    void noPairIsAnErrorNamingWhatWasLookedAt() {
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("22869", FakeArtifactory.dir("2026-07-23T06:11:00Z", "viper-buypass",
                viper("v", "2026-07-23T06:11:00Z", EXE)));
        master.put("468", FakeArtifactory.dir("2026-07-23T06:40:00Z", "foundation-buypass",
                installableWith("f", "2026-07-23T06:40:00Z", "buypass_V57.01.00_devSigned_CD.exe")));
        master.put("469", FakeArtifactory.dir("2026-07-23T11:13:00Z", "foundation-buypass",
                installable("f", "2026-07-23T11:13:00Z")));
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("master", master)))) {
            String message = assertThrows(BuildException.class,
                    () -> FoundationPair.paired(fake.client(), "master", "22869", "buypass")).getMessage();
            assertTrue(message.contains("none of the 1 buypass folders published within 2 h of it holds its "),
                    message);
            assertTrue(message.contains("468 foundation-buypass (+29 min: buypass_V57.01.00_devSigned_CD.exe)"),
                    message);
            assertTrue(message.contains("choose the foundation explicitly"));
            assertTrue(assertThrows(BuildException.class,
                    () -> FoundationPair.paired(fake.client(), "master", "22869", "chevron")).getMessage()
                    .contains("no viper-chevron folder"));
            assertTrue(assertThrows(BuildException.class,
                    () -> FoundationPair.paired(fake.client(), "master", "1", "buypass")).getMessage()
                    .contains("master has no build 1"));
        }
        assertTrue(Duration.ofMinutes(302).compareTo(FoundationPair.PAIR_WINDOW) > 0);
    }
}
