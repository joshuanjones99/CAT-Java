package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.DeviceError;

/**
 * {@link Installed}: the build on the Commander named by commit hash against downloaded overlays built here, the
 * same nesting as a real overlay (zip, tgz, vpk tgz, {@code suiteinfo.dat}). Evidence about this code only.
 */
class InstalledTest {

    @TempDir
    Path root;

    /** One ustar member, then the two end blocks. */
    static byte[] tgz(String name, byte[] content) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        byte[] size = String.format("%011o", content.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = '0';
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        tar.write(header);
        tar.write(content);
        tar.write(new byte[(512 - content.length % 512) % 512]);
        tar.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    static void overlay(Path file, String dist, String commit) throws IOException {
        byte[] dat = ("Suite_Name =Foundation\nBase_Level =057.02.00\nCommit_Hash =" + commit
                + "\nTimestamp =2026-10-07\n").getBytes(StandardCharsets.UTF_8);
        byte[] vpk = tgz("config/suiteinfo.dat", dat);
        byte[] outer = tgz("coresvcs/suiteinfo.vpk", vpk);
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("install/other.tgz"));
            zip.write(new byte[3]);
            zip.putNextEntry(new ZipEntry("install/update_foundation." + dist + ".psc.suiteinfo.1.tgz"));
            zip.write(outer);
        }
    }

    @Test
    void theOverlayCarryingTheCommandersHashNamesTheBuild() throws IOException {
        overlay(root.resolve("Solutions1082").resolve("overlay.zip"), "buypass", "aaaa");
        overlay(root.resolve("Solutions134").resolve("Buypass").resolve("overlay.zip"), "buypass", "BBBBccccdddd1234");
        overlay(root.resolve("master-268").resolve("buypass").resolve("overlay.zip"), "buypass", "cccc");
        Files.createDirectories(root.resolve("notabuild"));

        assertEquals(3, Installed.downloadedOverlays("buypass", root).size());
        Installed.Identification found = Installed.installedBuildName(
                () -> Map.of("Commit_Hash", "bbbbccccdddd1234ffff", "Timestamp", "t"), "buypass", root);
        assertEquals("", found.name(), "a prefix is not a match");

        found = Installed.installedBuildName(
                () -> Map.of("Commit_Hash", "bbbbccccdddd1234", "Timestamp", "t"), "buypass", root);
        assertEquals("Solutions134", found.name());
        assertEquals("Solutions134 (Commit_Hash bbbbccccdddd, built t)", found.why());
    }

    @Test
    void anUnknownHashOrAnUnreadableCommanderIsUnknownNeverAGuess() throws IOException {
        overlay(root.resolve("Solutions1082").resolve("overlay.zip"), "buypass", "aaaa");

        Installed.Identification unknown = Installed.installedBuildName(() -> Map.of("Commit_Hash", "ffff"),
                "buypass", root);
        assertEquals("", unknown.name());
        assertTrue(unknown.why().startsWith("not one of the downloaded builds under " + root), unknown.why());

        Installed.Identification unread = Installed.installedBuildName(() -> {
            throw new DeviceError("no route");
        }, "buypass", root);
        assertEquals("", unread.name());
        assertEquals("could not read the Commander's suiteinfo.dat (DeviceError: no route)", unread.why());
    }

    @Test
    void anOverlayForAnotherDistOrNoZipAtAllHasNoHash() throws IOException {
        Path overlay = root.resolve("o.zip");
        overlay(overlay, "testFep", "aaaa");
        assertEquals("", Installed.overlayCommitHash(overlay, "buypass"));
        assertEquals("aaaa", Installed.overlayCommitHash(overlay, "TESTFEP"));
        Files.writeString(root.resolve("bad.zip"), "not a zip");
        assertEquals("", Installed.overlayCommitHash(root.resolve("bad.zip"), "buypass"));
    }
}
