package com.verifone.cat.recorder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The recording file: {@code petrotest-recording}, version 1. Port of {@code petrotest.recorder.format}'s constants
 * (CAT_RESULTS_AND_GENERATE.md 3.4); the line types are described on {@link Recording}.
 *
 * <p>NDJSON: one JSON object per line, UTF-8, {@code \n}-terminated, flushed as it is written, so a recorder that dies
 * mid-case leaves every tap it saw on disk. No address is ever written: a recording is a file people pass around.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): nothing here has watched a register.
 */
public final class RecordingFormat {

    /** The header's {@code format}. */
    public static final String FORMAT = "petrotest-recording";

    /** The only version this reader and writer know. */
    public static final int VERSION = 1;

    /** {@code source.kind} for the fake POS before its rename on 2026-10-08; read as {@code fake_pos}. */
    public static final String LEGACY_FAKE_POS_KIND = "pos_double";

    /** What version 1 does not see, and why, in the words a reviewer needs (the Python dict's order). */
    public static final Map<String, String> NOT_CAPTURED = notCaptured();

    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private RecordingFormat() {}

    private static Map<String, String> notCaptured() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("pinpad", "prompts and keys on the pinpad are ViperPay inside Petro, not the browser (§3.2)");
        out.put("card", "insert/swipe is a simulator action, not a DOM event (§3.2)");
        out.put("fuel", "pump actions need P11.0's forecourt observation (D15)");
        out.put("dcr", "DCR actions need P11.0's forecourt observation (D15)");
        out.put("receipt", "the printed receipt comes over the register's printer port, rig-only (§3.5)");
        out.put("tlog", "the TLog is a Commander report over CGILink, rig-only (§3.5)");
        out.put("keyboard", "physical POS keyboard presses are not listened for");
        out.put("screen_after", "the screen each tap produced is not read back");
        return Collections.unmodifiableMap(out);
    }

    /** Now, as {@code utc_iso()}: {@code 2026-09-18T00:00:00.000+00:00}. */
    public static String utcIso() {
        return utcIso(Instant.now());
    }

    /** {@code utc_iso(epoch_ms)}: milliseconds since the epoch, truncated to the millisecond as {@code isoformat} does. */
    public static String utcIso(double epochMs) {
        return utcIso(Instant.ofEpochMilli((long) Math.floor(epochMs)));
    }

    /** {@code moment} in UTC, to the millisecond. */
    public static String utcIso(Instant moment) {
        return ISO_MILLIS.format(moment.atZone(ZoneOffset.UTC));
    }
}
