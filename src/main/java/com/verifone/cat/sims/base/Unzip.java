package com.verifone.cat.sims.base;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks a downloaded zip under a folder: Python's {@code zipfile.ZipFile.extractall}, which the Python CAT's
 * {@code cat.sims.fetch} and {@code cat.sims.java} unpack simulators and Java sets with. A file that is not a zip is
 * a {@link java.util.zip.ZipException}, as Python's is a {@code BadZipFile}.
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
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root) || name.startsWith("/")) {
                    throw new IOException(zip.getFileName() + ": entry " + entry.getName() + " leaves the folder");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
