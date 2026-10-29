package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.launch.UpgradeMode;

/**
 * {@link OverlayMerge} against the merger jar's own output. {@code build/overlay_merge/} holds the Python CAT's
 * synthetic {@code foundation.zip} and {@code viper.zip} ({@code tests/fixtures/overlay_merge/make_inputs.py}) and
 * {@code VerifoneOverlayMergerAppV1.00.01.jar}'s PSI merge of them, so the first test compares this port with the jar,
 * not with itself. The Auto Upgrade output carries the jar's proprietary transition packages, so it is not copied here:
 * its test reads the Python CAT's checkout and is skipped without it. Evidence about this port of the merge only.
 */
class OverlayMergeTest {

    /** Entries the merge writes itself (the lists, then the transition TGZs): only their times may differ. */
    private static final Map<OverlayMerge.PackageType, Integer> WRITTEN = Map.of(
            OverlayMerge.PackageType.AUTO_UPGRADE, 8, OverlayMerge.PackageType.PETRO_SUITE_INSTALLER, 2);

    private static final Path PYTHON_FIXTURES = Path.of(System.getenv().getOrDefault("CAT_PYTHON_ROOT",
            "C:\\gitrepos\\CAT"), "tests", "fixtures", "overlay_merge");

    @TempDir
    Path dir;

    private static Path fixture(String name) {
        try {
            return Path.of(OverlayMergeTest.class.getResource("/build/overlay_merge/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Synthetic transition resources: two packages, one of whose names the foundation already has. */
    private Path transitions() throws IOException {
        Path folder = dir.resolve("transition");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("transition-tgzs.txt"), "# the order\nupdate_a_dev.tgz\n"
                + "update_upg_python64_base_dev.tgz\n");
        Files.writeString(folder.resolve("filelist.txt"), "install/update_a_dev.tgz\ninstall/update_never_added.tgz");
        Files.writeString(folder.resolve("update_a_dev.tgz"), "transition a");
        Files.writeString(folder.resolve("update_upg_python64_base_dev.tgz"), "transition python64");
        return folder;
    }

    private Path merge(OverlayMerge merge, OverlayMerge.PackageType type, List<String> log) throws IOException {
        return merge.merge(fixture("foundation.zip"), fixture("viper.zip"), dir.resolve(type.name())
                .resolve("overlay.zip"), type, log::add);
    }

    private static Map<String, byte[]> read(Path zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile file = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : Collections.list(file.entries())) {
                try (InputStream in = file.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void assertSameAsTheJar(Path expected, Path port, OverlayMerge.PackageType type)
            throws IOException {
        try (ZipFile jz = new ZipFile(expected.toFile(), StandardCharsets.UTF_8);
                ZipFile pz = new ZipFile(port.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> java = Collections.list(jz.entries());
            List<? extends ZipEntry> mine = Collections.list(pz.entries());
            assertEquals(java.stream().map(ZipEntry::getName).toList(), mine.stream().map(ZipEntry::getName).toList());
            int copied = java.size() - WRITTEN.get(type);
            for (int k = 0; k < java.size(); k++) {
                ZipEntry j = java.get(k);
                ZipEntry p = mine.get(k);
                String name = j.getName();
                assertEquals(j.getMethod(), p.getMethod(), name);
                assertEquals(j.getCrc(), p.getCrc(), name);
                assertEquals(j.getSize(), p.getSize(), name);
                try (InputStream a = jz.getInputStream(j); InputStream b = pz.getInputStream(p)) {
                    assertArrayEquals(a.readAllBytes(), b.readAllBytes(), name);
                }
                assertEquals(j.getComment(), p.getComment(), name);
                if (k < copied) {
                    assertEquals(j.getTime(), p.getTime(), name);
                    assertArrayEquals(j.getExtra(), p.getExtra(), name);
                }
            }
        }
    }

    @Test
    void thePsiOutputMatchesTheJars() throws IOException {
        Path out = merge(new OverlayMerge(dir.resolve("absent")), OverlayMerge.PackageType.PETRO_SUITE_INSTALLER,
                new ArrayList<>());
        assertSameAsTheJar(fixture("expected-PETRO_SUITE_INSTALLER.zip"), out,
                OverlayMerge.PackageType.PETRO_SUITE_INSTALLER);
    }

    @Test
    void theAutoUpgradeOutputMatchesTheJarsWithThePythonCatsTransitions() throws IOException {
        Path expected = PYTHON_FIXTURES.resolve("expected-AUTO_UPGRADE.zip");
        Path transitions = OverlayMerge.transitionDir(System.getenv());
        Assumptions.assumeTrue(Files.isRegularFile(expected) && Files.isDirectory(transitions),
                "the Python CAT's overlay_merge fixture and transition resources are not here");
        Path out = merge(new OverlayMerge(transitions), OverlayMerge.PackageType.AUTO_UPGRADE, new ArrayList<>());
        assertSameAsTheJar(expected, out, OverlayMerge.PackageType.AUTO_UPGRADE);
    }

    @Test
    void pearlPackagesTakeTheirFoundationNames() throws IOException {
        List<String> log = new ArrayList<>();
        Map<String, byte[]> entries = read(merge(new OverlayMerge(transitions()),
                OverlayMerge.PackageType.AUTO_UPGRADE, log));
        assertArrayEquals("appdkfs payload".getBytes(),
                entries.get("install/update_foundation.base.common.appdkfs.V6.02.00.dev.tgz"),
                "the renamed package replaces the foundation entry that already had its name");
        assertTrue(entries.containsKey("install/update_foundation.base.common.installer.V6.02.01.dev.tgz"));
        assertTrue(entries.containsKey("install/update_foundation.base.psc.appfs.V1.00.00.dev.tgz"));
        assertTrue(entries.containsKey("update_foundation.base.pos.appfs.V6.02.00.dev.tgz"));
        assertTrue(entries.containsKey("install/update_pearl-pos_appfs_v6.02.00_prod.tgz"));
        assertTrue(entries.keySet().stream().noneMatch(n -> n.contains("pearl-common") || n.contains("_v _dev")));
        assertTrue(log.contains("Skipping existing Foundation entry (will be replaced by rename): "
                + "install/update_foundation.base.common.appdkfs.V6.02.00.dev.tgz"), log.toString());
    }

    @Test
    void theRenamePlanFollowsTheJarsPatterns() {
        OverlayMerge.RenamePlan plan = OverlayMerge.planFoundationRenames(List.of(
                "a\\update_pearl-pos_appfs_v 2.0 _dev.tgz",
                "Update_Pearl-Controller_Appfs_V3_Dev.Tgz",
                "update_pearl-common_installer_v\t_dev.tgz",
                "dir/update_pearl-common_appdkfs_v1_dev.tgz/",
                "update_pearl-pos_appfs_v1\n2_dev.tgz",
                "xupdate_pearl-pos_appfs_v1_dev.tgz"), line -> { });
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a/update_pearl-pos_appfs_v 2.0 _dev.tgz", "a/update_foundation.base.pos.appfs.V2.0.dev.tgz");
        expected.put("Update_Pearl-Controller_Appfs_V3_Dev.Tgz", "update_foundation.base.psc.appfs.V3.dev.tgz");
        expected.put("update_pearl-common_installer_v\t_dev.tgz",
                "update_foundation.base.common.installer.V1.00.00.dev.tgz");
        assertEquals(expected, plan.entries());
        assertTrue(plan.replacedByRename("update_foundation.base.psc.appfs.V3.dev.tgz"));
        assertFalse(plan.replacedByRename("Update_Pearl-Controller_Appfs_V3_Dev.Tgz"));
    }

    @Test
    void aViperEntryNeverReplacesAFoundationOne() throws IOException {
        List<String> log = new ArrayList<>();
        Map<String, byte[]> entries = read(merge(new OverlayMerge(transitions()),
                OverlayMerge.PackageType.AUTO_UPGRADE, log));
        assertEquals("foundation instructions\r\n", new String(entries.get("install/instr.txt")));
        assertEquals("foundation site.cfg", new String(entries.get("config/site.cfg")));
        assertEquals("pos appfs payload, at the root",
                new String(entries.get("update_foundation.base.pos.appfs.V6.02.00.dev.tgz")));
        assertEquals("first copy wins", new String(entries.get("install/dup.txt")));
        assertEquals(4, log.stream().filter(l -> l.startsWith("COLLISION: ")).count());
    }

    @Test
    void listFilesAreMergedAtOnePath() throws IOException {
        List<String> log = new ArrayList<>();
        Map<String, byte[]> entries = read(merge(new OverlayMerge(dir), OverlayMerge.PackageType.PETRO_SUITE_INSTALLER,
                log));
        assertFalse(entries.containsKey("Zz/filelist.txt"), "a case-insensitive sort puts install/ first");
        assertFalse(entries.containsKey("install/filelist.txt"));
        assertEquals("pmc\r\npinpad\r\n", new String(entries.get("install/ModuleList.txt"), StandardCharsets.UTF_8));
        assertEquals("install/\r\n"
                + "install/update_foundation.base.common.appdkfs.V6.02.00.dev.tgz\r\n"
                + "install/update_foundation.base.common.installer.V6.02.01.dev.tgz\r\n"
                + "install/update_pearl-common_installer_v6.02.01_dev.tgz\r\n"
                + "install/update_foundation.base.psc.appfs.V1.00.00.dev.tgz\r\n"
                + "update_foundation.base.pos.appfs.V6.02.00.dev.tgz\r\n"
                + "install/update_pearl-pos_appfs_v6.02.00_prod.tgz\r\n"
                + "install/instr.txt\r\n"
                + "\uFEFFinstall/\r\n"
                + "install/update_viper.base.psc.pmc.V1.00.00.dev.tgz\r\n"
                + "install/update_viper.buypass.psc.pinpad.V1.00.00.dev.tgz\r\n"
                + "install/instr.txt\r\n", new String(entries.get("install/FileList.txt"), StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>(entries.keySet());
        assertEquals(List.of("install/FileList.txt", "install/ModuleList.txt"), names.subList(names.size() - 2,
                names.size()));
        assertTrue(log.contains("WARNING: Multiple FileList.txt entries found in one zip. Using: install/FileList.txt"),
                log.toString());
    }

    @Test
    void transitionPackagesComeOnlyWithAutoUpgrade() throws IOException {
        List<String> log = new ArrayList<>();
        OverlayMerge merge = new OverlayMerge(transitions());
        Map<String, byte[]> au = read(merge(merge, OverlayMerge.PackageType.AUTO_UPGRADE, log));
        Map<String, byte[]> psi = read(merge(merge, OverlayMerge.PackageType.PETRO_SUITE_INSTALLER, new ArrayList<>()));
        List<String> auNames = new ArrayList<>(au.keySet());
        assertEquals(List.of("install/update_a_dev.tgz", "install/update_upg_python64_base_dev__extra2.tgz"),
                auNames.subList(auNames.size() - 2, auNames.size()));
        assertEquals(new ArrayList<>(psi.keySet()), auNames.subList(0, auNames.size() - 2));
        assertEquals("transition python64", new String(au.get("install/update_upg_python64_base_dev__extra2.tgz")));
        assertEquals("foundation's own python64", new String(au.get("install/update_upg_python64_base_dev.tgz")));
        assertTrue(new String(au.get("install/FileList.txt"), StandardCharsets.UTF_8)
                .endsWith("install/update_a_dev.tgz\r\ninstall/update_never_added.tgz\r\n"));
        assertFalse(new String(psi.get("install/FileList.txt"), StandardCharsets.UTF_8).contains("update_a_dev"));
        assertTrue(log.contains("Transition TGZ collision at install/update_upg_python64_base_dev.tgz -> renaming to "
                + "install/update_upg_python64_base_dev__extra2.tgz"), log.toString());
        assertEquals(List.of("update_a_dev.tgz", "update_upg_python64_base_dev.tgz"), merge.transitionTgzNames());
    }

    @Test
    void anAutoUpgradeMergeWithoutTheTransitionsIsRefusedBeforeWriting() {
        Path dest = dir.resolve("out").resolve("overlay.zip");
        OverlayMerge.OverlayMergeError e = assertThrows(OverlayMerge.OverlayMergeError.class,
                () -> new OverlayMerge(dir.resolve("absent")).merge(fixture("foundation.zip"), fixture("viper.zip"),
                        dest, OverlayMerge.PackageType.AUTO_UPGRADE, line -> { }));
        assertTrue(e.getMessage().contains("CAT-Java does not ship"), e.getMessage());
        assertFalse(Files.exists(dest.getParent()));
    }

    @Test
    void compressionMethodsAreKept() throws IOException {
        Path out = merge(new OverlayMerge(transitions()), OverlayMerge.PackageType.AUTO_UPGRADE, new ArrayList<>());
        Map<String, Integer> method = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(out.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                method.put(entry.getName(), entry.getMethod());
            }
        }
        assertEquals(ZipEntry.STORED, method.get("install/stored.bin"));
        assertEquals(ZipEntry.STORED, method.get("install/update_viper.buypass.psc.pinpad.V1.00.00.dev.tgz"));
        assertEquals(ZipEntry.STORED, method.get("install/"));
        assertEquals(ZipEntry.DEFLATED, method.get("install/deflated-dir/"));
        assertEquals(ZipEntry.DEFLATED, method.get("install/FileList.txt"));
    }

    @Test
    void anExistingOutputIsReplacedOnlyByAFinishedMerge() throws IOException {
        Path dest = dir.resolve("overlay.zip");
        Files.writeString(dest, "previous merge");
        Path bad = dir.resolve("bad.zip");
        writeBzip2Entry(bad);
        OverlayMerge merge = new OverlayMerge(transitions());
        // java.util.zip refuses the entry when the zip is opened (as it would for the jar); the Python names it later.
        IOException e = assertThrows(IOException.class,
                () -> merge.merge(bad, fixture("viper.zip"), dest, OverlayMerge.PackageType.AUTO_UPGRADE, l -> { }));
        assertTrue(e.getMessage().contains("compression method"), e.getMessage());
        assertEquals("previous merge", Files.readString(dest));
        assertFalse(Files.exists(dir.resolve("overlay.zip.part")));

        merge.merge(fixture("foundation.zip"), fixture("viper.zip"), dest, OverlayMerge.PackageType.AUTO_UPGRADE,
                l -> { });
        assertTrue(read(dest).containsKey("install/dup.txt"));
        assertFalse(Files.exists(dir.resolve("overlay.zip.part")));
    }

    @Test
    void inputsAreCheckedBeforeAnythingIsWritten() throws IOException {
        OverlayMerge merge = new OverlayMerge(dir);
        FileNotFoundException missing = assertThrows(FileNotFoundException.class, () -> merge.merge(
                fixture("foundation.zip"), dir.resolve("missing.zip"), dir.resolve("o.zip"),
                OverlayMerge.PackageType.PETRO_SUITE_INSTALLER, l -> { }));
        assertTrue(missing.getMessage().startsWith("Viper ZIP not found"));
        Path copy = dir.resolve("foundation.zip");
        Files.copy(fixture("foundation.zip"), copy);
        IllegalArgumentException same = assertThrows(IllegalArgumentException.class, () -> merge.merge(copy,
                fixture("viper.zip"), copy, OverlayMerge.PackageType.PETRO_SUITE_INSTALLER, l -> { }));
        assertTrue(same.getMessage().contains("would overwrite input"));
        assertArrayEquals(Files.readAllBytes(fixture("foundation.zip")), Files.readAllBytes(copy));
    }

    @Test
    void thePackageTypeFollowsTheInstaller() {
        assertEquals(OverlayMerge.PackageType.PETRO_SUITE_INSTALLER,
                OverlayMerge.PackageType.forMode(UpgradeMode.NEW_INSTALL));
        assertEquals(OverlayMerge.PackageType.AUTO_UPGRADE, OverlayMerge.PackageType.forMode(UpgradeMode.CLASSIC));
        assertEquals(OverlayMerge.PackageType.AUTO_UPGRADE, OverlayMerge.PackageType.forMode(UpgradeMode.VHQ));
    }

    @Test
    void listTextLineEndingsAndExtraSuffixes() {
        assertEquals("\r\n", OverlayMerge.toCrlf(null));
        assertEquals("\r\n", OverlayMerge.toCrlf(""));
        assertEquals("a\r\n", OverlayMerge.toCrlf("a"));
        assertEquals("a\r\nb\r\nc\r\nd\r\n", OverlayMerge.toCrlf("a\nb\rc\r\nd"));
        assertEquals("a\r\n\r\n", OverlayMerge.toCrlf("a\r\n\r\n"));
        assertEquals("x__extra1.tgz", OverlayMerge.addExtraSuffix("x.tgz", 1));
        assertEquals("x__extra2.TGZ", OverlayMerge.addExtraSuffix("x.TGZ", 2));
        assertEquals(".tgz__extra1", OverlayMerge.addExtraSuffix(".tgz", 1));
        assertEquals("x.zip__extra3", OverlayMerge.addExtraSuffix("x.zip", 3));
    }

    /**
     * A zip whose one entry claims method 12 (bzip2), which neither java.util.zip nor the jar can read: written stored
     * and its method field patched in both headers.
     */
    private static void writeBzip2Entry(Path path) throws IOException {
        byte[] data = "x".repeat(64).getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        try (OutputStream file = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(file)) {
            ZipEntry entry = new ZipEntry("install/x.tgz");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(path);
        for (int i = 0; i + 4 <= bytes.length; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 3 && bytes[i + 3] == 4) {
                bytes[i + 8] = 12; // local header: method at offset 8
            } else if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                bytes[i + 10] = 12; // central header: method at offset 10
            }
        }
        Files.write(path, bytes);
    }
}
