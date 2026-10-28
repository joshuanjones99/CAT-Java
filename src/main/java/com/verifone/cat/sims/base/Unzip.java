package com.verifone.cat.sims.base;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a downloaded zip under a folder: Python's {@code zipfile.ZipFile.extractall}, which the Python CAT's
 * {@code cat.sims.fetch} and {@code cat.sims.java} unpack simulators and Java sets with.
 *
 * <p>An entry whose name would land outside the destination (an absolute path, a {@code ..} segment) is refused
 * with an {@link IOException}: Python's {@code extractall} strips such parts silently, this port refuses the zip.
 */
public final class Unzip {

    private Unzip() {
    }

    /** Every entry of {@code zip} under {@code dest}, folders created as needed. */
    public static void extract(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (InputStream file = Files.newInputStream(zip); ZipInputStream in = new ZipInputStream(file)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName().replace('\\', '/');
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root) || name.startsWith("/")) {
                    throw new IOException(zip.getFileName() + ": entry " + entry.getName() + " leaves the folder");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
