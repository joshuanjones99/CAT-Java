package com.verifone.cat.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Overlay zips and staged build folders for the build tests. */
final class BuildFixtures {

    static final String EXE = "buypass_V57.02.00_devSigned_CD.exe";

    private BuildFixtures() {}

    /** An overlay whose one entry is dated {@code built}. Use even seconds: a zip's time is 2-second precision. */
    static byte[] overlay(LocalDateTime built) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("install/fcc.tgz");
            entry.setTime(built.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            zip.putNextEntry(entry);
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Stages {@code <root>/<build>/<dist>/} holding {@code files}. An {@code overlay.zip} is dated
     * {@code built}; every other file is a small placeholder.
     */
    static Path stage(Path root, String build, String dist, LocalDateTime built, String... files)
            throws IOException {
        Path distDir = root.resolve(build).resolve(dist);
        Files.createDirectories(distDir);
        for (String name : files) {
            byte[] content = name.equals(BuildDiscovery.OVERLAY) ? overlay(built) : name.getBytes(StandardCharsets.UTF_8);
            Files.write(distDir.resolve(name), content);
        }
        return distDir;
    }

    static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute, 0);
    }
}
