package com.verifone.cat.connections;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The rig network lines of the Python CAT's System Checks: the controller's rig adapter, the
 * Commander's TCP port, and whether the Commander's application tier answers.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python CAT's {@code cat.readiness.check_rig}
 * and {@code commander_answers} are the specification, and the rig-verified run of 2026-09-14
 * (HANDOFF.md §1.34, §1.35) was the Python's, not this port's.
 *
 * <p>Not ported, and so not checked here: the VATS lines for {@code pinpad: vats}, and the
 * receipt-printer lines (each register's {@code printer_port} must be listed by Windows and must
 * open). A serial listing and a COM open are Windows-specific and belong with the printer
 * slice, and the VATS checks belong with the VATS slice. The rig model is ported, so the step that
 * runs these lines (System Checks) names each missing group as unavailable.
 *
 * <p>{@link #commanderAnswers} never repairs anything. A Commander with a dead RMI tier is named
 * and left for a person to fix; a run that silently rebuilt the Commander would not be evidence
 * about the rig.
 */
public final class RigReadiness {

    /** The line's hint when the controller's rig adapter has dropped. */
    public static final String ADAPTER_HINT = "the controller's USB rig network adapter has probably gone dark: restart the PC";

    /** The line's hint when the Commander does not answer. */
    public static final String COMMANDER_HINT = "check that the Commander is up and cabled to the rig switch";

    /** What the Commander answers every CGILink command with when its RMI layer is down (HANDOFF.md §1.63, §1.73). */
    public static final String AAA_DOWN_FAULT = "AAA service unavailable";

    /** The named cause behind {@link #AAA_DOWN_FAULT}, diagnosed on hardware 2026-09-21 (HANDOFF.md §1.73). */
    public static final String AAA_DOWN_HINT = "the Commander's RMI/TLS keystore is missing, so coresvcs and every "
            + "other RMI service is crash-looping. Check /home/common/x509/rmi_keystore.jks exists and that "
            + "/home/tlscert/bin is populated; a build whose overlay.zip omits the "
            + "foundation.base.psc.tlscert package produces exactly this. THIS IS NOT AUTO-REPAIRED — fix the "
            + "Commander, then start the run again";

    /** The Commander answers a CGILink request after this long at most, however the caller set its timeout. */
    static final Duration COMMANDER_ANSWER_FLOOR = Duration.ofSeconds(10);

    private final ConnectionProbe probe;
    private final int commanderPort;
    private final HttpClient http;

    /** The platform's CGILink port, {@link Endpoints#COMMANDER_SERVICE_PORT}. */
    public RigReadiness(ConnectionProbe probe) {
        this(probe, Endpoints.COMMANDER_SERVICE_PORT);
    }

    /** @param commanderPort CGILink's port; a test passes an ephemeral one */
    RigReadiness(ConnectionProbe probe, int commanderPort) {
        this.probe = probe;
        this.commanderPort = commanderPort;
        this.http = HttpClient.newHttpClient();
    }

    /** The lines for {@code endpoints}, in display order. Blocking, and bounded by {@code timeout}. */
    public List<Readiness> check(Endpoints endpoints, Duration timeout) {
        List<ProbeResult> probes = probe.probeAll(List.of(
                Target.local("Host PC", endpoints.controllerHost()),
                Target.tcp("Commander", endpoints.commanderHost(), commanderPort)), timeout);
        ProbeResult host = probes.get(0);
        ProbeResult commander = probes.get(1);

        List<Readiness> lines = new ArrayList<>();
        if (host.state() == ProbeState.UP) {
            lines.add(new Readiness(true, "controller rig adapter", host.detail(), true));
        } else if (host.state() == ProbeState.DOWN) {
            lines.add(new Readiness(false, "controller rig adapter",
                    endpoints.controllerHost() + " is not on any local network adapter — " + ADAPTER_HINT, true));
        } else {
            // The bind-style test could not run: report it, but do not stop the run on it.
            lines.add(new Readiness(false, "controller rig adapter", host.detail(), false));
        }

        if (commander.state() == ProbeState.UP) {
            lines.add(new Readiness(true, "commander reachable", commander.detail(), true));
        } else {
            String hint = host.state() == ProbeState.DOWN ? ADAPTER_HINT : COMMANDER_HINT;
            lines.add(new Readiness(false, "commander not reachable", commander.detail() + " — " + hint, true));
        }

        // Asked only once the port is open; otherwise it would restate the line above.
        if (commander.state() == ProbeState.UP) {
            Duration answerTimeout = timeout.compareTo(COMMANDER_ANSWER_FLOOR) > 0 ? timeout : COMMANDER_ANSWER_FLOOR;
            lines.add(commanderAnswers(endpoints.commanderHost(), commanderPort, answerTimeout));
        }
        // Not ported: the VATS lines (pinpad: vats) and the printer-port lines, see the class Javadoc.
        return lines;
    }

    /**
     * One CGILink round trip, to tell a listening Commander from a working one. A healthy
     * Commander rejects a deliberately malformed command on its own terms; a broken one answers
     * {@link #AAA_DOWN_FAULT} whatever it was sent, and a 503 means Tomcat has not finished starting.
     */
    public Readiness commanderAnswers(String host, int port, Duration timeout) {
        // A failed line's label is quoted bare in the step summary, so it says what went wrong.
        String label = "commander not answering";
        HttpResponse<String> response;
        try {
            URI uri = URI.create("http://" + host + ":" + port + "/cgi-bin/CGILink?cmd=validate");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).POST(HttpRequest.BodyPublishers.noBody()).build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return new Readiness(false, label, e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " — " + COMMANDER_HINT, true);
        } catch (IllegalArgumentException e) {
            return new Readiness(false, label, "the Commander's address is not a valid URL host: "
                    + e.getMessage() + " — " + COMMANDER_HINT, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Readiness(false, label, "interrupted while asking the Commander", true);
        }
        if (response.statusCode() == 503) {
            return new Readiness(false, label, "Apache answered 503: the Commander's Tomcat is still starting", true);
        }
        if (response.statusCode() != 200) {
            return new Readiness(false, label, "CGILink answered HTTP " + response.statusCode(), true);
        }
        if (response.body() != null && response.body().contains(AAA_DOWN_FAULT)) {
            return new Readiness(false, label, AAA_DOWN_FAULT + " — " + AAA_DOWN_HINT, true);
        }
        return new Readiness(true, "commander answers", "CGILink answered", true);
    }
}
