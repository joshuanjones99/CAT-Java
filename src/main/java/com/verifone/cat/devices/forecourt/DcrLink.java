package com.verifone.cat.devices.forecourt;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads Petro Simulator Suite's own log to say whether DCR {@code n} is connected to the Commander, and whether a
 * driver init's display has shown. Ported from the Python {@code dcr_link_fault}, {@code dcr_display_signal} and
 * {@code reinit_signal} (HANDOFF.md 1.99).
 *
 * <p>Unknown is not down: a log that cannot be read gives no fault.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class DcrLink {

    /** Reconnects in a row, with no display between, that mean the link is down: about 50 s at the suite's ~17 s retry. */
    static final int DOWN_RECONNECTS = 3;
    /** How recent the last reconnect must be for the loop to be live, not a stopped suite's leftover tail. */
    static final long LOOP_FRESH_SECONDS = 120;
    /** The tail of the log that is read. */
    static final long TAIL_BYTES = 4_000_000L;

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm:ss", Locale.ENGLISH);

    private DcrLink() {
    }

    /** The line for a display the Commander pushed to DCR {@code dcr}: the link is up. Two forms, as the suite logs them. */
    public static Pattern displaySignal(int dcr) {
        return Pattern.compile("Sending Display command to DCR\\[" + dcr + "\\]|displayPromptMenu Position " + dcr + "(?!\\d)");
    }

    /**
     * Why the suite's DCR {@code dcr} is not connected to the Commander, or null. Read from the tail of the log: refused,
     * the suite logs "DCR[n] comm error..reconnecting" every ~17 s and never a display.
     */
    public static String fault(Path log, int dcr, LocalDateTime now) {
        List<String> lines;
        try {
            lines = tailLines(log, TAIL_BYTES);
        } catch (IOException e) {
            return null;
        }
        String reconnect = "DCR[" + dcr + "] comm error..reconnecting";
        Pattern display = displaySignal(dcr);
        List<String> since = new ArrayList<>(); // the reconnects after the last display
        for (String line : lines) {
            if (display.matcher(line).find()) {
                since.clear();
            } else if (line.contains(reconnect)) {
                since.add(line);
            }
        }
        if (since.size() < DOWN_RECONNECTS) {
            return null;
        }
        LocalDateTime last = logTime(since.get(since.size() - 1), now);
        if (last == null || Duration.between(last, now).getSeconds() > LOOP_FRESH_SECONDS) {
            return null;
        }
        String first = since.get(0).substring(0, Math.min(19, since.get(0).length()));
        return "not connected to the Commander: Petro has reconnected it at least " + since.size()
                + " times since " + first + " and the Commander has sent it no display (" + log + "). The Commander"
                + " closes its session after GetSupportedTerminalIds (HANDOFF.md 1.99).";
    }

    /** {@link #fault(Path, int, LocalDateTime)} at the current time. */
    public static String fault(Path log, int dcr) {
        return fault(log, dcr, LocalDateTime.now());
    }

    /**
     * How many lines holding {@code signal} the suite has logged since byte {@code offset}. 0 when the log cannot be
     * read, or was rotated shorter than {@code offset}.
     */
    public static int reinitSignal(Path log, long offset, Pattern signal) {
        String tail = tailFrom(log, offset);
        if (tail == null) {
            return 0;
        }
        return (int) tail.lines().filter(line -> signal.matcher(line).find()).count();
    }

    /** As {@link #reinitSignal(Path, long, Pattern)}, counting occurrences of a plain string. */
    public static int reinitSignal(Path log, long offset, String signal) {
        String tail = tailFrom(log, offset);
        if (tail == null) {
            return 0;
        }
        int count = 0;
        for (int at = tail.indexOf(signal); at >= 0 && !signal.isEmpty(); at = tail.indexOf(signal, at + signal.length())) {
            count++;
        }
        return count;
    }

    /** A suite log line's own time ("Oct 01 18:49:01.712 ..."), in {@code now}'s year, or null when it has none. */
    static LocalDateTime logTime(String line, LocalDateTime now) {
        String head = line.substring(0, Math.min(15, line.length()));
        try {
            LocalDateTime stamp = LocalDateTime.parse(now.getYear() + " " + head, LOG_TIME);
            return stamp.isAfter(now.plusDays(1)) ? stamp.minusYears(1) : stamp;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<String> tailLines(Path log, long tailBytes) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long start = Math.max(file.length() - tailBytes, 0L);
            file.seek(start);
            byte[] buf = new byte[(int) (file.length() - start)];
            file.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static String tailFrom(Path log, long offset) {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            if (length < offset) {
                return null;
            }
            file.seek(offset);
            byte[] buf = new byte[(int) (length - offset)];
            file.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
