package com.verifone.cat.coverage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;

import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The two waits after a coverage reboot, which {@link DeviceOps#defaults} wires: the Python's
 * {@code petrotest.upgrade.new_install.wait_for_ssh} and {@code wait_for_cgilink}, as {@code coverage.device}'s
 * {@code _ops_defaults} uses them.
 *
 * <p><b>SSH</b>: polled until the device answers {@code cat /proc/uptime} with an uptime shorter than the time since
 * the reboot was sent, so only a boot that started after the reboot counts. <b>CGILink</b>: polled until a
 * deliberately malformed {@code validate} gets HTTP 200 without {@code AAA service unavailable} (it reaches AAA before
 * any handler and sends no credential, so it can never count as a failed login).
 *
 * <p>The upgrade package has the same waits privately ({@code upgrade.NewInstall}); they are repeated here so this
 * package depends on nothing the upgrade slice may change. Polling parks the thread
 * ({@link CoverageDevice#boundedPoll}), never sleeps.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CoverageWaits {

    /** How long a device has to answer SSH after a reboot: the Python's {@code SSH_BACK_TIMEOUT_SECONDS}. */
    public static final Duration SSH_BACK_TIMEOUT = Duration.ofSeconds(1200);

    /** How long the Commander's application tier has after its reboot: {@code CGILINK_BACK_TIMEOUT_SECONDS}. */
    public static final Duration CGILINK_BACK_TIMEOUT = Duration.ofSeconds(900);

    /** The gap between polls: {@code SSH_POLL_SECONDS}. */
    public static final Duration POLL = Duration.ofSeconds(10);

    /** The budget of one {@code cat /proc/uptime}. */
    static final Duration UPTIME_TIMEOUT = Duration.ofSeconds(20);

    static final String CGILINK_PROBE_PATH = "/cgi-bin/CGILink?cmd=validate";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private CoverageWaits() {}

    /** The SSH wait over {@code transports}, with the Python's budgets. */
    public static DeviceOps.SshWait ssh(Function<TargetSpec, Transport> transports) {
        return ssh(transports, SSH_BACK_TIMEOUT, POLL);
    }

    static DeviceOps.SshWait ssh(Function<TargetSpec, Transport> transports, Duration timeout, Duration interval) {
        return (spec, rebootedAtNanos) -> CoverageDevice.boundedPoll(
                () -> bootedAfter(transports, spec, rebootedAtNanos), timeout, interval);
    }

    /** The CGILink wait with the Python's budgets. */
    public static Predicate<String> cgilink() {
        return cgilink(CoverageWaits::cgilinkAnswers, CGILINK_BACK_TIMEOUT, POLL);
    }

    static Predicate<String> cgilink(Predicate<String> answers, Duration timeout, Duration interval) {
        return host -> CoverageDevice.boundedPoll(() -> answers.test(host), timeout, interval);
    }

    /** Whether {@code spec} answers SSH from a boot later than {@code rebootedAtNanos} (a {@link System#nanoTime}). */
    static boolean bootedAfter(Function<TargetSpec, Transport> transports, TargetSpec spec, long rebootedAtNanos) {
        Transport box;
        try {
            box = transports.apply(spec);
        } catch (RuntimeException e) {
            return false; // a device mid-boot answers anything: refused, reset, half a banner
        }
        try {
            CommandResult result = box.run("cat /proc/uptime", UPTIME_TIMEOUT);
            if (!result.ok()) {
                return false;
            }
            double uptime = Double.parseDouble(result.stdout().strip().split("\\s+")[0]);
            double since = (System.nanoTime() - rebootedAtNanos) / 1_000_000_000.0;
            return uptime < since;
        } catch (RuntimeException e) { // DeviceError included
            return false;
        } finally {
            try {
                box.close();
            } catch (RuntimeException e) {
                // closing a half-open session is not worth failing the wait for
            }
        }
    }

    /** One CGILink probe: HTTP 200 and no {@code AAA service unavailable}. */
    static boolean cgilinkAnswers(String host) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + CGILINK_PROBE_PATH))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && !response.body().contains("AAA service unavailable");
        } catch (IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
