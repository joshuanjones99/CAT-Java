package com.verifone.cat.coverage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the overlay layout the Python's {@code test_coverage._overlay} builds with {@code tarfile}: a zip of
 * {@code install/update_*.tgz}, each a gzipped tar of {@code <dir>/<x>.vpk}, itself a gzipped tar of the component's
 * files. The tar writer is a small ustar one (with a GNU long-name record for names over 100 bytes), written here so
 * the reader is checked against bytes it did not produce. No product material: every file is a few made-up bytes.
 */
final class TarFixtures {

    private TarFixtures() {}

    /** A gzipped tar of {@code members} (name to bytes), then {@code symlinks} (name to target). */
    static byte[] tarGz(Map<String, byte[]> members, Map<String, String> symlinks) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            gz.write(tar(members, symlinks));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return raw.toByteArray();
    }

    static byte[] tar(Map<String, byte[]> members, Map<String, String> symlinks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        members.forEach((name, data) -> {
            if (name.getBytes(StandardCharsets.UTF_8).length > 100) {
                byte[] longName = (name + "\0").getBytes(StandardCharsets.UTF_8);
                write(out, header("././@LongLink", longName.length, 'L', ""));
                write(out, padded(longName));
            }
            write(out, header(name, data.length, '0', ""));
            write(out, padded(data));
        });
        symlinks.forEach((name, target) -> write(out, header(name, 0, '2', target)));
        write(out, new byte[1024]);
        return out.toByteArray();
    }

    /** A package: a gzipped tar holding {@code <directory>/<vpk>.vpk}, the gzipped tar of {@code members}. */
    static byte[] pkg(String directory, String vpk, Map<String, byte[]> members, Map<String, String> symlinks) {
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put(directory + "/" + vpk + ".vpk", tarGz(members, symlinks));
        return tarGz(outer, Map.of());
    }

    static byte[] pkg(String directory, String vpk, Map<String, byte[]> members) {
        return pkg(directory, vpk, members, Map.of());
    }

    /** The Python test's overlay, at {@code path}. */
    static Path overlay(Path path) {
        Map<String, byte[]> packages = new LinkedHashMap<>();
        Map<String, byte[]> fcc = new LinkedHashMap<>();
        fcc.put("lib/isdApps.psc.fcc.app-V1.jar", bytes("fcc-jar"));
        fcc.put("lib/log4j.jar", bytes("third-party"));
        packages.put("update_foundation.base.psc.fcc.V57.02.00.dev.tgz",
                pkg("fcc", "fcc", fcc, Map.of("usr/tomcat/lib/log4j.jar", "/home/common/lib/log4j.jar")));
        Map<String, byte[]> topaz = new LinkedHashMap<>();
        topaz.put("lib/isdApps.topaz.pos.app-V1.jar", bytes("pos-jar"));
        topaz.put("lib/isdApps.psc.fcc.fdc.jaxb-V1.jar", bytes("pos-jaxb"));
        topaz.put("webapps/appselectorserver.war", bytes("war"));
        packages.put("update_foundation.base.pos.topaz.V57.02.00.dev.tgz", pkg("topaz", "topaz", topaz));
        packages.put("update_foundation.base.psc.common.V57.02.00.dev.tgz",
                pkg("coresvcs", "common", Map.of("lib/isdApps.psc.fcc.fdc.jaxb-V1.jar", bytes("psc-jaxb"))));
        Map<String, byte[]> mobile = new LinkedHashMap<>();
        mobile.put("lib/isdApps.psc.mobile.app-V1.jar", bytes("mobile-app"));
        mobile.put("lib/viper.core.app-V1.jar", bytes("mobile's own viper-core"));
        packages.put("update_foundation.base.psc.mobile.V57.02.00.dev.tgz", pkg("mobile", "mobile", mobile));
        packages.put("update_viper.base.psc.pmc.V57.02.00.dev.tgz",
                pkg("pmc", "pmc", Map.of("lib/viper.core.app-V1.jar", bytes("viper-core"))));
        packages.put("update_viper.buypass.psc.pmc.V57.02.00.dev.tgz",
                pkg("pmc", "pmc", Map.of("lib/viper.custom.buypass.app-V1.jar", bytes("buypass"))));
        // Another brand's copy, which must not be read for a buypass run.
        packages.put("update_viper.exxon.psc.pmc.V57.02.00.dev.tgz",
                pkg("pmc", "pmc", Map.of("lib/viper.custom.buypass.app-V1.jar", bytes("WRONG"))));
        return zip(path, packages);
    }

    static Path zip(Path path, Map<String, byte[]> packages) {
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream file = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(file)) {
                for (Map.Entry<String, byte[]> entry : packages.entrySet()) {
                    zip.putNextEntry(new ZipEntry("install/" + entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] header(String name, long size, char type, String link) {
        byte[] header = new byte[512];
        put(header, 0, 100, name);
        put(header, 100, 8, "0000644");
        put(header, 108, 8, "0000000");
        put(header, 116, 8, "0000000");
        put(header, 124, 12, String.format("%011o", size));
        put(header, 136, 12, String.format("%011o", 0));
        header[156] = (byte) type;
        put(header, 157, 100, link);
        put(header, 257, 6, "ustar");
        put(header, 263, 2, "00");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, 8, String.format("%06o", sum) + "\0 ");
        return header;
    }

    private static void put(byte[] header, int offset, int length, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(length, bytes.length));
    }

    private static byte[] padded(byte[] data) {
        int padding = (512 - data.length % 512) % 512;
        byte[] out = new byte[data.length + padding];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
