package com.verifone.cat.commander;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.StepResult;
import com.verifone.cat.provision.StepStatus;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * Step 4's {@code commander-prep}: keep the Commander's license nags off for a run. A port of
 * {@code petrotest.commander_prep} (HANDOFF.md section 1.47).
 *
 * <p>Both parts are one SSH read when nothing needs changing, and neither pushes a package or restarts
 * anything. Reads use {@code cat}/{@code test} over plain SSH; a write (SFTP, and so the transport's
 * {@code setenforce 0}) happens only when a file changes.
 * <ol>
 *   <li><b>Move {@code license.json}'s stop dates forward</b> (rig owner, 2026-09-16). A {@code stop} less
 *       than {@link #LICENSE_RENEW_WITHIN_DAYS} days away, or past, moves to {@link #LICENSE_EXTENSION_DAYS}
 *       days after today, so the ASM license is neither in grace nor expired and the POS stops showing
 *       {@code NOT ONBOARDED}. Only the {@code stop} values are replaced, textually; the rest of the file
 *       stays byte for byte.</li>
 *   <li><b>Create Gemreg's test marker if it is missing</b>: the only payload of PATS's
 *       {@code gemreg-force-registered} package, which clears {@code SYSTEM NOT REGISTERED}.</li>
 * </ol>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's own writes are unverified too: its one real run
 * found nothing due. The Commander rewrites {@code license.json} by itself, so an edit may revert.
 */
public final class CommanderPrep {

    public static final String LICENSE_JSON = "/home/coresvcs/data/license/license.json";
    public static final String GEMREG_MARKER = "/home/coresvcs/config/gemreg_enable_testing";

    /** The rig owner's "maybe a month forward". */
    public static final int LICENSE_EXTENSION_DAYS = 30;

    /** A {@code stop} further away than this is left alone, so most runs write nothing. */
    public static final int LICENSE_RENEW_WITHIN_DAYS = 7;

    /** The package's own marker text, verbatim. */
    static final byte[] GEMREG_MARKER_TEXT = "This file forces Gemreg to be registered\n".getBytes(StandardCharsets.UTF_8);

    /** The owner of both files' directories on the lab Commander, 2026-09-16; the transport writes as root. */
    static final String OWNER = "coresvcs:vfi";

    private static final Pattern STOP = Pattern.compile("(\"stop\"\\s*:\\s*\")([^\"]*)(\")");

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CommanderPrep() {}

    private static StepResult failed(String step, String detail) {
        return new StepResult(step, StepStatus.FAILED, detail);
    }

    /** Writes {@code path} as {@link #OWNER}, mode 0644. A failure, or {@code null} on success. */
    private static StepResult writeOwned(Transport transport, String path, byte[] content, String step,
            Duration timeout) {
        CommandResult owned;
        try {
            transport.put(path, content, 0644, timeout);
            owned = transport.run("chown " + OWNER + " " + path, timeout);
        } catch (DeviceError e) {
            return failed(step, "write failed: " + e.getMessage());
        }
        if (!owned.ok()) {
            return failed(step, "chown failed: " + (owned.stderr().isEmpty() ? owned.stdout() : owned.stderr()).strip());
        }
        return null;
    }

    /** {@link #extendLicense(Transport, LocalDate, int, int, Duration)} with the default window and extension. */
    public static StepResult extendLicense(Transport transport, LocalDate today, Duration timeout) {
        return extendLicense(transport, today, LICENSE_EXTENSION_DAYS, LICENSE_RENEW_WITHIN_DAYS, timeout);
    }

    /** Moves every {@code stop} due within {@code renewWithin} days to the end of {@code today + days}. */
    public static StepResult extendLicense(Transport transport, LocalDate today, int days, int renewWithin,
            Duration timeout) {
        String step = "extend_license";
        CommandResult read;
        try {
            read = transport.run("cat " + LICENSE_JSON, timeout);
        } catch (DeviceError e) {
            return failed(step, "read failed: " + e.getMessage());
        }
        if (!read.ok()) {
            return failed(step, "cannot read " + LICENSE_JSON + ": " + read.stderr().strip());
        }
        String text = read.stdout();
        if (text.isBlank()) {
            return failed(step, "not JSON: the file is empty");
        }
        try {
            JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return failed(step, "not JSON: " + e.getOriginalMessage());
        }

        String due = today.plusDays(renewWithin) + "T23:59:59Z";
        String newStop = today.plusDays(days) + "T23:59:59Z";
        int changed = 0;
        Matcher m = STOP.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(0);
            if (m.group(2).compareTo(due) <= 0) {
                changed++;
                replacement = m.group(1) + newStop + m.group(3);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        if (changed == 0) {
            return new StepResult(step, StepStatus.ALREADY, "every stop is after " + due);
        }
        StepResult failure = writeOwned(transport, LICENSE_JSON, out.toString().getBytes(StandardCharsets.UTF_8), step,
                timeout);
        if (failure != null) {
            return failure;
        }
        return new StepResult(step, StepStatus.DONE, changed + " stop date(s) set to " + newStop);
    }

    /** Creates {@link #GEMREG_MARKER} unless it exists. Its content is never read. */
    public static StepResult ensureGemregMarker(Transport transport, Duration timeout) {
        String step = "ensure_gemreg_marker";
        boolean present;
        try {
            present = transport.run("test -e " + GEMREG_MARKER, timeout).ok();
        } catch (DeviceError e) {
            return failed(step, "check failed: " + e.getMessage());
        }
        if (present) {
            return new StepResult(step, StepStatus.ALREADY, "");
        }
        StepResult failure = writeOwned(transport, GEMREG_MARKER, GEMREG_MARKER_TEXT, step, timeout);
        return failure != null ? failure : new StepResult(step, StepStatus.DONE, GEMREG_MARKER);
    }

    /** Both parts against the rig's Commander, one line per part. Whether neither failed. */
    public static boolean run(CommanderAccess access, LocalDate today, Say say) {
        Duration timeout = access.sshTimeout();
        List<StepResult> results = new ArrayList<>();
        Transport transport = null;
        try {
            transport = access.connectedTransport();
            results.add(extendLicense(transport, today, timeout));
            results.add(ensureGemregMarker(transport, timeout));
        } catch (DeviceError e) {
            results.clear();
            results.add(failed("connect", e.getMessage()));
        } finally {
            if (transport != null) {
                transport.close();
            }
        }
        boolean ok = true;
        for (StepResult result : results) {
            boolean failed = result.status() == StepStatus.FAILED;
            ok = ok && !failed;
            String line = result.step() + ": " + result.status().wire()
                    + (result.detail().isEmpty() ? "" : " — " + result.detail());
            if (failed) {
                say.err(line);
            } else {
                say.out(line);
            }
        }
        return ok;
    }
}
