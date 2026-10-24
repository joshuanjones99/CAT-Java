package com.verifone.cat.runservice;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.report.events.RunLogWriter;

/**
 * The last lines of a run's {@code run.log}, read from the end of the file so a long run costs
 * the same to show as a short one.
 */
public final class RunLogTail {

    /** The most lines the Log tab shows, as the results page's log does. */
    public static final int LINES = 500;

    /** The most bytes read from the end of the file. Enough for {@link #LINES} ordinary lines. */
    static final long MAX_BYTES = 256L * 1024;

    private RunLogTail() {}

    /** The last {@code max} lines of the folder's {@code run.log}, oldest first; empty when there is none. */
    public static List<String> tail(Path folder, int max) throws IOException {
        Path file = folder.resolve(RunLogWriter.RUN_LOG);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        byte[] bytes;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - MAX_BYTES);
            raf.seek(start);
            bytes = new byte[(int) (length - start)];
            raf.readFully(bytes);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (bytes.length == MAX_BYTES && !text.isEmpty()) {
            // Read from the middle of the file: the first line is cut, so drop it.
            int newline = text.indexOf('\n');
            text = newline < 0 ? "" : text.substring(newline + 1);
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        int from = Math.max(0, lines.size() - Math.max(0, max));
        return List.copyOf(lines.subList(from, lines.size()));
    }
}
