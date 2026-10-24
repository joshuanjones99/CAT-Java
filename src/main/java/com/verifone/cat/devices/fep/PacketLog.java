package com.verifone.cat.devices.fep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.preflight.Bounded.TimeoutExpired;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Did the Buypass host simulator send the Commander a file? Read from its packet log. Ported from the
 * Python CAT's {@code petrotest.devices.buypass_host.PacketLog}.
 *
 * <p>{@code BUYPASS Host} appends every GMF message it receives and sends to {@code packetFile.txt} in its own
 * folder, one per line. The two EPS Network Functions downloads CAT presses each end in one
 * {@code AdminResponse} there: the table load ({@code <FileType>TABLE</FileType>}, answered {@code 000}) and
 * the EMV CA public key download ({@code EMV2KEY}, answered {@code 703} every time since 2026-09-02). The code
 * is reported, not judged.
 *
 * <p>The simulator runs on the controller, so the file is read in place. Tools root: {@code CAT_TOOLS_ROOT},
 * else {@code C:\CICD\Tools}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Read only from files shaped like the lab controller's.
 */
public final class PacketLog {

    /** The table load's {@code FileType}. */
    public static final String TABLE = "TABLE";
    /** The EMV CA public key download's {@code FileType}. */
    public static final String EMV_KEYS = "EMV2KEY";

    /** How often {@link #waitFor} looks again, as the Python's one-second interval. */
    static final Duration INTERVAL = Duration.ofSeconds(1);

    /**
     * One {@code AdminResponse}. Filtered by {@code TxnType} afterwards: a pattern that also looked for
     * {@code FileDownload} could start at an earlier response of another type and span into the download,
     * reporting the earlier one's {@code RespCode}.
     */
    private static final Pattern RESPONSE = Pattern.compile("<AdminResponse>.*?</AdminResponse>", Pattern.DOTALL);
    private static final String FILE_DOWNLOAD = "<TxnType>FileDownload</TxnType>";
    private static final Pattern RESP_CODE = Pattern.compile("<RespCode>([^<]*)</RespCode>");

    private final Path path;

    /** {@code <tools root>/BUYPASS/packetFile.txt}. */
    public PacketLog() {
        this(ToolsRoot.resolve((Path) null).resolve("BUYPASS").resolve("packetFile.txt"));
    }

    public PacketLog(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Path path() {
        return path;
    }

    /** Where the file ends now, in bytes; 0 when it cannot be read. */
    public long mark() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * The response code of the first {@code fileType} download sent after {@code mark}; empty when there is
     * none yet or the file cannot be read. A file shorter than the mark was truncated, so it is read whole.
     */
    public Optional<String> answered(String fileType, long mark) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return Optional.empty();
        }
        int from = data.length >= mark ? (int) mark : 0;
        // ISO-8859-1 maps each byte to one char, so offsets stay byte offsets and nothing is lost.
        String tail = new String(data, from, data.length - from, StandardCharsets.ISO_8859_1);
        String needle = "<FileType>" + fileType + "</FileType>";
        Matcher response = RESPONSE.matcher(tail);
        while (response.find()) {
            String text = response.group();
            if (text.contains(FILE_DOWNLOAD) && text.contains(needle)) {
                Matcher code = RESP_CODE.matcher(text);
                return Optional.of(code.find() ? code.group(1) : "no RespCode");
            }
        }
        return Optional.empty();
    }

    /** Waits, bounded, until the host has sent a {@code fileType} download; its response code. */
    public String waitFor(String fileType, long mark, Duration timeout) {
        return waitFor(fileType, mark, timeout, INTERVAL);
    }

    String waitFor(String fileType, long mark, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        while (true) {
            Optional<String> code = answered(fileType, mark);
            if (code.isPresent()) {
                return code.get();
            }
            long left = deadline - System.nanoTime();
            if (left <= 0 || Thread.currentThread().isInterrupted()) {
                throw new TimeoutExpired("the Buypass host to send a " + fileType + " download (" + path
                        + ") did not happen within " + timeout.toMillis() + " ms");
            }
            LockSupport.parkNanos(Math.min(interval.toNanos(), left));
        }
    }
}
