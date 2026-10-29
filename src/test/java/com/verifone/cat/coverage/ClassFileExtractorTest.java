package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Class-file extraction from an overlay: the Python's {@code TestClassFiles}, against the same fixture overlay, plus
 * the tar reader's corners (symlinks, long names, a corrupt package).
 */
class ClassFileExtractorTest {

    @TempDir
    Path tmp;

    private ClassFiles extract(String dist) {
        return ClassFileExtractor.extract(TarFixtures.overlay(tmp.resolve("b").resolve("overlay.zip")), dist,
                line -> { });
    }

    private static Map<String, String> names(ClassFiles found, String component) {
        return found.found().get(component).stream().collect(Collectors.toMap(p -> p.getFileName().toString(),
                ClassFileExtractorTest::read, (a, b) -> b, LinkedHashMap::new));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void eachJvmGetsItsOwnJarsFromItsSideOfTheOverlay() {
        ClassFiles found = extract("buypass");

        assertEquals(Map.of("isdApps.psc.fcc.app-V1.jar", "fcc-jar"), names(found, "fcc"));
        // The jaxb jar ships on both sides; each Tomcat gets its own device's copy.
        assertEquals("pos-jaxb", names(found, "pos-tomcat").get("isdApps.psc.fcc.fdc.jaxb-V1.jar"));
        assertEquals("psc-jaxb", names(found, "commander-tomcat").get("isdApps.psc.fcc.fdc.jaxb-V1.jar"));
        assertEquals("buypass", names(found, "pmc").get("viper.custom.buypass.app-V1.jar"));
        assertTrue(found.missing().get("stc").contains("isdApps.psc.stc.app-V1.jar"));
    }

    @Test
    void mobileAndPmcEachGetTheirOwnViperCore() {
        // Run ad24510d66f5: mobile reported against pmc's copy failed in jacococli.
        ClassFiles found = extract("buypass");
        assertEquals("mobile's own viper-core", names(found, "mobile").get("viper.core.app-V1.jar"));
        assertEquals("viper-core", names(found, "pmc").get("viper.core.app-V1.jar"));
    }

    @Test
    void aCapitalisedDistributionStillFindsItsJars() {
        ClassFiles found = extract("Buypass");
        assertTrue(names(found, "pmc").containsKey("viper.custom.buypass.app-V1.jar"));
        assertFalse(found.missing().getOrDefault("pmc", List.of()).contains("viper.custom.buypass.app-V1.jar"));
        assertTrue(found.root().endsWith(Path.of("jacoco-classes", "v2", "buypass")), found.root().toString());
    }

    @Test
    void theCacheIsReusedUntilTheOverlayChanges() throws IOException {
        Path overlay = TarFixtures.overlay(tmp.resolve("b").resolve("overlay.zip"));
        ClassFiles first = ClassFileExtractor.extract(overlay, "buypass", line -> { });
        Path marker = first.found().get("fcc").get(0);
        Files.writeString(marker, "cached");

        assertEquals("cached", read(ClassFileExtractor.extract(overlay, "buypass", line -> { }).found().get("fcc")
                .get(0)));
        assertEquals("fcc-jar", read(ClassFileExtractor.extract(overlay, "buypass", true, line -> { }).found()
                .get("fcc").get(0)));
    }

    @Test
    void theStampHasThePythonsKeysAndTheFoundPathsAreRelative() throws IOException {
        ClassFiles found = extract("buypass");
        String stamp = Files.readString(found.root().resolve(ClassFileExtractor.STAMP));
        assertTrue(stamp.contains("\"overlay\"") && stamp.contains("\"size\"") && stamp.contains("\"mtime\""), stamp);
        assertTrue(stamp.contains("\"found\"") && stamp.contains("\"missing\""), stamp);
        assertFalse(stamp.contains(found.root().toString().replace("\\", "\\\\")), "found paths are relative");
    }

    @Test
    void symlinksAreSkippedOnlyRegularFilesAreClassFiles() {
        // The fcc vpk links usr/tomcat/lib/log4j.jar to a common jar; it is not a regular file, and not wanted.
        ClassFiles found = extract("buypass");
        assertEquals(1, found.found().get("fcc").size());
    }

    @Test
    void anotherBrandsPackagesAreNotRead() {
        List<ClassFileExtractor.Wanted> wanted = ClassFileExtractor.wantedPackages(List.of(
                "install/update_viper.exxon.psc.pmc.V57.02.00.dev.tgz",
                "install/update_viper.buypass.psc.pmc.V57.02.00.dev.tgz",
                "install/update_viper.base.psc.pmc.V57.02.00.dev.tgz",
                "install/update_viper.generic_buypass.psc.pmc.V57.02.00.dev.tgz",
                "install/readme.txt"), "buypass");
        assertEquals(List.of("install/update_viper.base.psc.pmc.V57.02.00.dev.tgz",
                "install/update_viper.buypass.psc.pmc.V57.02.00.dev.tgz",
                "install/update_viper.generic_buypass.psc.pmc.V57.02.00.dev.tgz"),
                wanted.stream().map(ClassFileExtractor.Wanted::member).toList(), "base first, then the overlay's order");
        assertEquals("psc", wanted.get(0).side());
        assertEquals("pmc", wanted.get(0).pkg());
    }

    @Test
    void anUnreadablePackageIsAWarningAndTheOthersStillCount() {
        Map<String, byte[]> packages = new LinkedHashMap<>();
        packages.put("update_foundation.base.psc.fcc.V57.02.00.dev.tgz", TarFixtures.bytes("not a tar at all"));
        packages.put("update_foundation.base.psc.stc.V57.02.00.dev.tgz",
                TarFixtures.pkg("stc", "stc", Map.of("lib/isdApps.psc.stc.app-V1.jar", TarFixtures.bytes("stc"))));
        Path overlay = TarFixtures.zip(tmp.resolve("c").resolve("overlay.zip"), packages);
        List<String> log = new ArrayList<>();

        ClassFiles found = ClassFileExtractor.extract(overlay, "buypass", log::add);

        assertEquals(1, found.found().get("stc").size());
        assertTrue(found.missing().containsKey("fcc"));
        assertTrue(log.stream().anyMatch(l -> l.contains("update_foundation.base.psc.fcc")
                && l.contains("is not a readable package")), log.toString());
    }

    @Test
    void theTarReaderFollowsGnuLongNamesAndEndsCleanly() throws IOException {
        String longName = "lib/" + "a".repeat(120) + "/isdApps.psc.fcc.app-V1.jar";
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put(longName, TarFixtures.bytes("deep"));
        members.put("short.txt", new byte[0]);
        try (TarStream tar = new TarStream(new ByteArrayInputStream(TarFixtures.tarGz(members, Map.of())))) {
            TarStream.Entry first = tar.next();
            assertEquals(longName, first.name());
            assertTrue(first.isFile());
            assertArrayEquals(TarFixtures.bytes("deep"), tar.data().readAllBytes());
            TarStream.Entry second = tar.next();
            assertEquals("short.txt", second.name());
            assertEquals(0, second.size());
            assertNull(tar.next());
            assertNull(tar.next());
        }
    }

    @Test
    void aPlainTarIsReadAsWellAsAGzippedOne() throws IOException {
        byte[] plain = TarFixtures.tar(Map.of("x.jar", TarFixtures.bytes("x")), Map.of("y.jar", "/elsewhere"));
        try (TarStream tar = new TarStream(new ByteArrayInputStream(plain))) {
            TarStream.Entry x = tar.next();
            assertEquals("x.jar", x.name());
            TarStream.Entry y = tar.next();
            assertTrue(y.isSymlink());
            assertFalse(y.isFile());
            assertEquals("/elsewhere", y.linkName());
            assertNull(tar.next());
        }
    }
}
