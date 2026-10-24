package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.verifone.cat.build.BuildCatalogue.NightlyBuild;

/** The branch and build catalogue, against {@link FakeArtifactory} laid out as the real server was listed on 2026-10-07. */
class BuildCatalogueTest {

    private static final String DATE = "2026-07-01T00:00:00.000Z";

    private static Map<String, Object> petro(Map<String, Object> branches) {
        return Map.of("RMS_Nightly_CLW", Map.of("Petro", branches));
    }

    @Test
    void branchesAreSolutionsThenMasterThenReleaseLinesThenTickets() {
        List<String> names = List.of("PCS", "PDEVOPS-2490", "PETROCORE-111172-055.02",
                "PETROCORE-142198-nacs-retail", "PETROCORE-142198-dbport-merge-fix", "PETROCORE-94624",
                "PETROCORE-142365-nacs-bugfix-branch", "PeripheralApps", "SalesAssistanceApps", "Solutions",
                "c76d47c57b75f1229e44e8b26d542b5cc30a2f36", "e452f31f6ff", "master", "master054.02",
                "master056.03", "master057.01", "master057.00", "null", "pcs-image-scans", "refs",
                "zz-something-new");
        Map<String, Object> branches = new LinkedHashMap<>();
        for (String name : names) {
            branches.put(name, FakeArtifactory.dir(DATE));
        }
        try (FakeArtifactory fake = new FakeArtifactory(petro(branches))) {
            BuildCatalogue catalogue = new BuildCatalogue(fake.client());

            assertEquals(List.of("Solutions", "master", "master057.01", "master057.00", "master056.03",
                    "master054.02", "PETROCORE-142365-nacs-bugfix-branch", "PETROCORE-142198-dbport-merge-fix",
                    "PETROCORE-142198-nacs-retail", "PETROCORE-111172-055.02", "PETROCORE-94624",
                    "PDEVOPS-2490", "zz-something-new"), catalogue.listBranches());
            assertEquals(1, fake.requests.size());
        }
    }

    @Test
    void buildsAreOrderedByDateAcrossTheSolutionsNumberingRestart() {
        Map<String, Object> solutions = new LinkedHashMap<>();
        solutions.put("1097", FakeArtifactory.dir("2026-09-26T12:08:27.288Z"));
        solutions.put("1096", FakeArtifactory.dir("2026-09-25T12:00:00.000Z"));
        solutions.put("65", FakeArtifactory.dir("2026-10-01T11:38:49.402Z"));
        solutions.put("8", FakeArtifactory.dir("2026-10-06T16:02:00.000Z"));
        solutions.put("90", FakeArtifactory.dir("2026-10-06T11:15:00.000Z"));
        solutions.put("94", FakeArtifactory.dir("2026-10-07T15:18:00.000Z"));
        solutions.put("not-a-build", FakeArtifactory.dir("2026-10-08T00:00:00.000Z"));
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("Solutions", solutions)))) {
            BuildCatalogue catalogue = new BuildCatalogue(fake.client());

            assertEquals(List.of("94", "8", "90", "65", "1097", "1096"), catalogue.listBuilds("Solutions"));
            List<NightlyBuild> entries = catalogue.listBuildEntries("Solutions");
            assertEquals("2026-10-07T15:18:00.000Z", entries.get(0).created());
            assertEquals(2, fake.requests.size());
        }
    }

    @Test
    void aBuildWithNoDateSortsLastAndEqualDatesFallBackToTheNumber() {
        String same = "2026-10-01T00:00:00.000Z";
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("5", FakeArtifactory.dir(same));
        master.put("40", FakeArtifactory.dir(same));
        master.put("7", FakeArtifactory.dir(""));
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("master", master)))) {
            assertEquals(List.of("40", "5", "7"), new BuildCatalogue(fake.client()).listBuilds("master"));
        }
    }

    @Test
    void aMissingBuildAndABadNameAreNamedErrors() {
        try (FakeArtifactory fake = new FakeArtifactory(petro(Map.of("master", Map.of("60", FakeArtifactory.dir(""))))))
        {
            BuildCatalogue catalogue = new BuildCatalogue(fake.client());
            assertEquals(List.of("60"), catalogue.listBuilds("master"));
            assertThrows(BuildException.class, () -> catalogue.listBuilds("no-such-branch-name/../x"));
            assertThrows(BuildException.class, () -> BuildCatalogue.branchPath("../other"));
            assertThrows(BuildException.class, () -> BuildCatalogue.branchPath("a\\b"));
            assertThrows(BuildException.class, () -> BuildCatalogue.branchPath("  "));
            assertThrows(BuildException.class, () -> BuildDiscovery.buildNumber("latest"));
        }
    }

    @Test
    void theBranchPathIsUnderTheNightlyRepo() {
        assertEquals("RMS_Nightly_CLW/Petro/master", BuildCatalogue.branchPath("/master/"));
        assertEquals("RMS_Nightly_CLW/Petro/refs/tags/Base056.03.01",
                BuildCatalogue.branchPath("refs/tags/Base056.03.01"));
    }

    @Test
    void theNonBranchesAreNotOfferedAndCommitHashesAreLeftOut() {
        assertFalse(BuildCatalogue.isBranch("refs"));
        assertFalse(BuildCatalogue.isBranch("null"));
        assertFalse(BuildCatalogue.isBranch("e452f31f6ff"));
        assertTrue(BuildCatalogue.isBranch("zz-something-new"));
        assertTrue(BuildCatalogue.isBranch("master057.01"));
    }
}
