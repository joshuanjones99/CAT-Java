package com.verifone.cat.sims.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.verifone.cat.build.ArtifactoryAuthError;
import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.FakeArtifactory;
import com.verifone.cat.sims.Zips;

/**
 * The Commander base, the Java 17 sets and the staged simulators' bases: the Python CAT's
 * {@code tests/unit/test_cat_sims_java.py} and {@code test_cat_sims_suite_base.py}, against {@code FakeArtifactory}
 * and a {@code @TempDir} tools root. Evidence about this code, not about the real server or a Commander.
 */
class SimsBaseTest {

    @TempDir
    Path dir;

    /** A client supplier that fails the test if asked. */
    private static final Supplier<ArtifactoryClient> NEVER = () -> {
        throw new AssertionError("nothing should have been downloaded");
    };

    // -- which base, which set ----------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"055.02.09,55", "057.02.00,57", "master055.02,55", "master057.01,57",
            "PETROCORE-142310-055.02.13,55", "master,", "Solutions,"})
    void aBaseIsReadFromABaseLevelOrABranch(String text, Integer major) {
        assertEquals(Optional.ofNullable(major), JavaSets.baseMajor(text));
    }

    @ParameterizedTest
    @CsvSource({"55,Java17v1.1", "56,Java17v1.1", "57,Java17", "58,Java17"})
    void belowBase57IsV11AndFrom57OnIsV12(int major, String target) {
        assertEquals(target, JavaSets.javaForBase(major).target());
    }

    @Test
    void anUnreadableBaseIsV12AndTheNewerRows() {
        assertEquals("Java17", JavaSets.javaForRun("").target());
        assertFalse(JavaSets.olderBase(""));
        assertTrue(JavaSets.olderBase("055.02.09"));
        assertFalse(JavaSets.olderBase("057.02.00"));
    }

    @Test
    void theBranchARunInstallsDecidesWithoutAskingTheCommander() {
        CommanderBase.Base base = CommanderBase.of("master055.02", () -> {
            throw new AssertionError("the Commander was asked");
        });
        assertEquals("master055.02", base.base());
        assertEquals("the run installs master055.02", base.why());
    }

    @Test
    void theCommandersBaseLevelDecidesWhenTheBranchNamesNone() {
        CommanderBase.Base base = CommanderBase.of("Solutions", () -> Map.of("Base_Level", "055.02.09"));
        assertEquals("055.02.09", base.base());
        assertEquals("the Commander is on Base_Level 055.02.09", base.why());
        assertEquals("Java17v1.1", JavaSets.javaForRun(base.base()).target());
    }

    @Test
    void anUnreadableBaseSaysWhy() {
        CommanderBase.Base base = CommanderBase.of("", () -> {
            throw new IllegalStateException("no route");
        });
        assertFalse(base.known());
        assertTrue(base.why().contains("could not be read") && base.why().contains("no route"), base.why());

        CommanderBase.Base odd = CommanderBase.of("", () -> Map.of("Base_Level", "unknown"));
        assertEquals("the Commander's Base_Level 'unknown' names no base", odd.why());
    }

    /** A folder node with no date. */
    private static Map<String, Object> d(Object... keysAndValues) {
        return FakeArtifactory.dir("", keysAndValues);
    }

    // -- the Java 17 sets ------------------------------------------------------------------------

    private static FakeArtifactory javaServer(byte[] v12, byte[] v11) {
        return new FakeArtifactory(d("RMS_Release_CLW", d("Petro", d("ToolsInternal", d("Java17_automation",
                d("v1.2", d("Java17.zip", v12), "v1.1", d("Java17.zip", v11)))))));
    }

    private static byte[] javaZip(String top, String namespace) {
        return Zips.zip(top + "/jdk-17.0.5+8/bin/javaw.exe", "jvm",
                top + "/jaxb-ri/mod_/api.jar", "jar",
                top + "/jaxb-ri/mod/jakarta.xml.bind-api.jar", Zips.jaxbApi(namespace));
    }

    @Test
    void bothSetsGoWhereCatReadsThem() {
        try (FakeArtifactory server = javaServer(javaZip("Java17/Java17", "jakarta"), javaZip("Java17", "javax"))) {
            List<String> log = new ArrayList<>();
            assertEquals("", JavaSets.ensureJava(JavaSets.V12, dir, server::client, log::add));
            assertEquals("", JavaSets.ensureJava(JavaSets.V11, dir, server::client, log::add));
        }
        assertTrue(Files.isRegularFile(dir.resolve("Java17/jdk-17.0.5+8/bin/javaw.exe")));
        assertTrue(Files.isRegularFile(dir.resolve("Java17v1.1/jaxb-ri/mod_/api.jar")));
        assertEquals(List.of(), JavaSets.missing(dir));
    }

    @Test
    void aStagedSetIsNeverReplacedOrDownloaded() throws IOException {
        Zips.stageJava(dir, "Java17", "jakarta");
        Files.writeString(dir.resolve("Java17/mine.txt"), "keep");
        assertEquals("", JavaSets.ensureJava(JavaSets.V12, dir, NEVER, line -> { }));
        assertEquals("keep", Files.readString(dir.resolve("Java17/mine.txt")));
    }

    @Test
    void aFolderHoldingTheOtherSetIsMovedAsideAndTheRealOneFetched() throws IOException {
        Zips.stageJava(dir, "Java17", "javax"); // TPA-ProdCloud, 2026-10-09: Java17 was a copy of v1.1
        Files.writeString(dir.resolve("Java17/mine.txt"), "keep");
        assertFalse(JavaSets.holdsSet(JavaSets.V12, dir));
        try (FakeArtifactory server = javaServer(javaZip("Java17/Java17", "jakarta"), javaZip("Java17", "javax"))) {
            assertEquals("", JavaSets.ensureJava(JavaSets.V12, dir, server::client, line -> { }));
            assertEquals(1, server.downloads().size());
        }
        assertTrue(JavaSets.holdsSet(JavaSets.V12, dir));
        try (var children = Files.list(dir)) {
            Path aside = children.filter(p -> p.getFileName().toString().startsWith("Java17.not-v1.2-"))
                    .findFirst().orElseThrow();
            assertEquals("keep", Files.readString(aside.resolve("mine.txt")));
        }
    }

    @Test
    void noLoginIsSaidInWordsAndNothingIsStaged() {
        String problem = JavaSets.ensureJava(JavaSets.V11, dir, () -> {
            throw new ArtifactoryAuthError("ARTIFACTORY_USER", "ARTIFACTORY_PASSWORD");
        }, line -> { });
        assertTrue(problem.startsWith("Java 17 v1.1: fetching from Artifactory failed: no Artifactory login saved"),
                problem);
        assertFalse(Files.exists(dir.resolve("Java17v1.1")));
    }

    @Test
    void aZipWithoutTheExpectedFolderIsRefused() {
        try (FakeArtifactory server = javaServer(javaZip("Other", "jakarta"), javaZip("Other", "javax"))) {
            String problem = JavaSets.ensureJava(JavaSets.V12, dir, server::client, line -> { });
            assertEquals("Java 17 v1.2: Java17.zip holds no Java17/Java17/", problem);
        }
        assertFalse(Files.exists(dir.resolve("Java17")));
    }

    // -- a staged simulator's base ---------------------------------------------------------------

    private Path jar(String folder, String namespace) throws IOException {
        Path at = Files.createDirectories(dir.resolve(folder)).resolve("s.jar");
        Files.write(at, namespace == null ? Zips.zip("com/verifone/isd/Analyser.class", "")
                : Zips.suiteJar(namespace));
        return at;
    }

    @Test
    void aJarsJaxbNamespaceIsReadFromItsClasses() throws IOException {
        assertEquals(Optional.of("jakarta"), SuiteBase.jarNamespace(jar("a", "jakarta")));
        assertEquals(Optional.of("javax"), SuiteBase.jarNamespace(jar("b", "javax")));
        assertEquals(Optional.empty(), SuiteBase.jarNamespace(jar("c", null)));
        assertEquals(Optional.empty(), SuiteBase.jarNamespace(dir.resolve("absent.jar")));
    }

    @Test
    void withNoInstallRecordTheJarSaysWhichSideOfBase57() throws IOException {
        SuiteBase.Installed master = SuiteBase.installedBase(dir, jar("s", "jakarta"));
        assertNull(master.base());
        assertEquals(Boolean.TRUE, master.v12());
        assertTrue(master.how().contains("jakarta"));
    }

    @Test
    void catsInstallRecordNamesTheBaseExactlyAndItsBuild() throws IOException {
        SuiteBase.writeMarker(dir, "RMS_Nightly_CLW/Petro/master055.02", "41");
        assertEquals("master055.02", SuiteBase.readMarker(dir).orElseThrow().branch());
        assertEquals("41", SuiteBase.readMarker(dir).orElseThrow().build());
        SuiteBase.Installed found = SuiteBase.installedBase(dir, jar("s", "jakarta")); // the record wins
        assertEquals(new JavaSets.Base(55, 2), found.base());
        assertEquals(Boolean.FALSE, found.v12());
        assertTrue(found.how().contains("master055.02 build 41"));
    }

    @Test
    void thePythonsInstallRecordReadsTheSame() throws IOException {
        // As cat.sims.suite_base.write_marker writes it (json.dumps, indent=1).
        Files.writeString(dir.resolve(SuiteBase.MARKER), "{\n \"repo\": \"RMS_Nightly_CLW/Petro/master\",\n"
                + " \"branch\": \"master\",\n \"build\": \"129\",\n \"installed\": \"2026-10-27T10:00:00+00:00\"\n}\n",
                StandardCharsets.UTF_8);
        SuiteBase.Marker marker = SuiteBase.readMarker(dir).orElseThrow();
        assertEquals("master", marker.branch());
        assertEquals("129", marker.build());
        assertEquals(Boolean.TRUE, SuiteBase.installedBase(dir, null).v12());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "-,-,true,055.02.09,true", // master suite, 55.02 Commander: the crash
            "-,-,false,055.02.09,false",
            "-,-,false,057.02.00,true",
            "-,-,true,057.02.00,false",
            "55,1,false,055.02.09,true", // lower base, same side
            "55,2,false,055.02.09,false",
            "57,2,true,055.02.09,true", // higher
            "-,-,-,055.02.09,false", // unknown: never fetched on a guess
            "-,-,true,'',false"})
    void aHigherOrLowerBaseIsAMismatch(Integer major, Integer minor, Boolean v12, String commander, boolean wrong) {
        JavaSets.Base base = major == null ? null : new JavaSets.Base(major, minor);
        SuiteBase.Installed installed = new SuiteBase.Installed(base, v12, "");
        assertEquals(wrong, !SuiteBase.mismatch(installed, commander == null ? "" : commander).isEmpty());
    }

    @Test
    void theMatchingBranchIsMasterAndTheBase() {
        assertEquals(List.of("master055.02"), SuiteBase.branchesFor("055.02.09"));
        assertEquals(List.of("master055.02"), SuiteBase.branchesFor("master055.02"));
        assertEquals(List.of("master057.02", "master"), SuiteBase.branchesFor("057.02.00"));
        assertEquals(List.of(), SuiteBase.branchesFor(""));
    }

    @Test
    void theNewestBuildOfTheBasesBranchIsTheMatch() {
        String zip = "install-isdApps.pcapps.simulators.app-V1.zip";
        Map<String, Object> tree = d("RMS_Nightly_CLW", d("Petro", d(
                "master055.02", FakeArtifactory.dir("2026-01-01T00:00:00.000Z",
                        "41", FakeArtifactory.dir("2026-09-28T18:23:48.785Z", "simulators", d(zip, new byte[1])),
                        "1750", FakeArtifactory.dir("2026-09-26T01:53:19.272Z", "simulators", d(zip, new byte[1])),
                        "1800", FakeArtifactory.dir("2026-09-29T01:53:19.272Z", "TopazERP", d())),
                "master", FakeArtifactory.dir("2026-01-01T00:00:00.000Z",
                        "60", FakeArtifactory.dir("2026-10-07T14:18:00.000Z", "simulators", d(zip, new byte[1]))))));
        try (FakeArtifactory server = new FakeArtifactory(tree)) {
            ArtifactoryClient client = server.client();
            assertEquals(new SuiteBase.BranchBuild("master055.02", "41"), SuiteBase.matchingBuild(client, "055.02.09"));
            // No master057.02 branch: master's newest.
            assertEquals(new SuiteBase.BranchBuild("master", "60"), SuiteBase.matchingBuild(client, "057.02.00"));
            NightlyBuilds.NotFound none = assertThrows(NightlyBuilds.NotFound.class,
                    () -> SuiteBase.matchingBuild(client, "056.00.01"));
            assertTrue(none.getMessage().contains("master056.00"), none.getMessage());
        }
    }

    @Test
    void aBranchNameThatWouldLeaveTheRepoIsRefused() {
        assertEquals("RMS_Nightly_CLW/Petro/master", NightlyBuilds.branchPath(" /master/ "));
        assertThrows(IllegalArgumentException.class, () -> NightlyBuilds.branchPath("../x"));
        assertThrows(IllegalArgumentException.class, () -> NightlyBuilds.branchPath(""));
        assertThrows(IllegalArgumentException.class, () -> NightlyBuilds.buildNumber("6a"));
    }
}
