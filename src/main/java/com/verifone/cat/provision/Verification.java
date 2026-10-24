package com.verifone.cat.provision;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.preflight.Bounded;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * Post-push verification (DESIGN.md 6.1): every file re-read and digest-checked, then the RMI agent and the HTTP
 * shim asked. A port of {@code petrotest.provision.verify}.
 *
 * <p>Content is hashed on the target with {@code sha256sum} first, and read back only when that gives no digest:
 * a 179 MB tarball read over SFTP overran the budget on the register (2026-09-16).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Verification {

    /** The HTTP shim's port when a rig does not name one. */
    public static final int DEFAULT_SHIM_PORT = TargetSpec.DEFAULT_SHIM_PORT;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private Verification() {}

    /** What verification found. An empty problem list is the only pass. */
    public static final class Report {
        private final String role;
        private final String host;
        private final List<String> problems = new ArrayList<>();
        private int checked;
        private boolean agentsOk;

        public Report(String role, String host) {
            this.role = role;
            this.host = host;
        }

        public String role() {
            return role;
        }

        public String host() {
            return host;
        }

        public int checked() {
            return checked;
        }

        public boolean agentsOk() {
            return agentsOk;
        }

        public void agentsOk(boolean value) {
            agentsOk = value;
        }

        public List<String> problems() {
            return List.copyOf(problems);
        }

        public boolean ok() {
            return problems.isEmpty();
        }

        public void fail(String message) {
            problems.add(message);
        }
    }

    /**
     * Re-reads every file in {@code manifest} from the target and confirms its digest. Bounded as a whole: the
     * caller's budget, not one per file.
     */
    public static Report verifyContent(Manifest manifest, Transport transport, Duration timeout, Report report) {
        Report result = report != null ? report : new Report(manifest.role().wire(), transport.host());
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Manifest.Entry entry : manifest.entries()) {
            String path = entry.remotePath();
            String actual;
            byte[] raw = null;
            try {
                actual = remoteSha256(transport, path, slice(deadline, timeout));
                if (actual == null) {
                    raw = transport.get(path, slice(deadline, timeout)).orElse(null);
                }
            } catch (Bounded.TimeoutExpired e) {
                result.fail(path + ": read timed out during verification");
                break;
            } catch (DeviceError e) {
                result.fail(path + ": " + e.getMessage());
                continue;
            }
            result.checked++;
            if (actual == null) {
                if (raw == null) {
                    result.fail(path + ": absent after push (" + entry.artifact() + ")");
                    continue;
                }
                actual = Manifest.sha256Hex(raw);
            }
            if (!actual.equals(entry.sha256())) {
                String onTarget = raw != null ? raw.length + " bytes" : "size not read";
                result.fail(path + ": digest mismatch after push (" + entry.artifact() + "); expected "
                        + entry.sha256().substring(0, 12) + "... got " + actual.substring(0, 12) + "... ("
                        + onTarget + " on target, " + entry.size() + " pushed)");
            }
        }
        return result;
    }

    /**
     * The target's own {@code sha256sum} of {@code path}, or null when it gives none (absent, no {@code sha256sum},
     * unparseable output). The caller then reads the file back.
     */
    static String remoteSha256(Transport transport, String path, Duration timeout) {
        CommandResult result = transport.run("sha256sum " + RemotePaths.shellQuote(path), timeout);
        String text = result.stdout().strip();
        if (text.isEmpty()) {
            return null;
        }
        String digest = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!result.ok() || !SHA256_HEX.matcher(digest).matches()) {
            return null;
        }
        return digest;
    }

    /**
     * Confirms the RMI agent and HTTP shim answer. The shim is asked first: if it is down, "the RMI agent does not
     * answer" is not evidence about the agent.
     */
    public static Report verifyAgents(AgentProbe probe, Duration timeout, Report report) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!probe.shimAnswers(slice(deadline, timeout))) {
                report.fail("HTTP shim did not answer after provisioning - the push may have landed but the shim is"
                        + " not running (6.1)");
                return report;
            }
            if (!probe.rmiAgentAnswers(slice(deadline, timeout))) {
                report.fail("HTTP shim is up but the POS RMI agent did not answer a status round-trip - every 6.2"
                        + " preflight probe depends on this surface");
                return report;
            }
        } catch (Bounded.TimeoutExpired e) {
            report.fail("agent probe did not finish: " + e.getMessage());
            return report;
        }
        report.agentsOk(true);
        return report;
    }

    /** The part of the budget left, or a timeout when none is. */
    static Duration slice(long deadline, Duration timeout) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new Bounded.TimeoutExpired("the verification budget of " + Poll.seconds(timeout) + " s ran out");
        }
        return Duration.ofNanos(Math.min(timeout.toNanos(), left));
    }
}
