package com.verifone.cat.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Writes {@code index.json}: every file under a run folder, with its size in bytes and in
 * words, so a person can find the evidence for a failure.
 *
 * <p>The Python CAT's {@code petrotest.report.index.ArtifactIndexWriter}. The shape is the
 * same: {@code run_id}, {@code artifact_count}, {@code total_size_bytes},
 * {@code total_size_human} and {@code artifacts}, each with {@code path}, {@code size_bytes}
 * and {@code size_human}. The run id is the folder's name. The index itself is left out of
 * its own listing.
 *
 * <p>A file's {@code path} is relative to the run folder and uses the platform's separator,
 * as the Python does, so a Windows index reads {@code PETROCORE-1\steps.log}.
 */
public final class ArtifactIndex {

    /** The file's name in a run folder. */
    public static final String INDEX_FILE = "index.json";

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArtifactIndex() {}

    /**
     * Writes {@code <artifactsDir>/index.json}, creating the folder if needed.
     *
     * @throws IOException when the folder cannot be walked or the index cannot be written
     */
    public static void write(Path artifactsDir) throws IOException {
        Files.createDirectories(artifactsDir);
        Path indexPath = artifactsDir.resolve(INDEX_FILE);

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(artifactsDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(indexPath))
                    .sorted()
                    .forEach(files::add);
        }

        ArrayNode entries = MAPPER.createArrayNode();
        long total = 0;
        for (Path file : files) {
            long size = Files.size(file);
            total += size;
            ObjectNode entry = entries.addObject();
            entry.put("path", artifactsDir.relativize(file).toString());
            entry.put("size_bytes", size);
            entry.put("size_human", formatSize(size));
        }

        ObjectNode index = MAPPER.createObjectNode();
        index.put("run_id", artifactsDir.getFileName() == null ? "" : artifactsDir.getFileName().toString());
        index.put("artifact_count", files.size());
        index.put("total_size_bytes", total);
        index.put("total_size_human", formatSize(total));
        index.set("artifacts", entries);

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), index);
    }

    /**
     * A size in words: {@code 512 B} under a kilobyte, {@code 1.2 KB} and so on up to
     * terabytes, then {@code PB}. A whole number of bytes is not given a decimal.
     */
    public static String formatSize(long sizeBytes) {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        double size = sizeBytes;
        for (int unit = 1; unit < UNITS.length; unit++) {
            size /= 1024;
            if (size < 1024) {
                return String.format(Locale.ROOT, "%.1f %s", size, UNITS[unit]);
            }
        }
        return String.format(Locale.ROOT, "%.1f PB", size / 1024);
    }
}
