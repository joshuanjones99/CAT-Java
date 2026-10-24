package com.verifone.cat.commander.connect;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.cloudtunnel.CloudTunnel;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * Onboards a Commander to Commander Connect: service id, lab, sign-in (with retries), then Online. Each step is skipped
 * when the Commander already has it. A port of the Python {@code petrotest.commander_connect.apply_commander_connect}.
 *
 * <p>The order and the reasons for each wait are the Python's (read from petroapps' source, 2026-10): the lab is set
 * before the sign-in, and a changed lab restarts coresvcs; a sign-in that fails on a host the cloud tunnel has not
 * relayed yet is retried once the tunnel relays it; a registered Commander whose MQTT bridge reads Offline gets its
 * broker restarted, and then coresvcs.
 *
 * <p>Transports and clients come from suppliers, one per use, closed by this class, so the production wiring can
 * connect them and the tests can hand in fakes.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Run it once against the lab Commander before trusting it. This is not
 * yet called from the launch steps; the Python's step 4 ({@code cat.launch.steps}) is where it goes.
 */
public final class ConnectService {

    /** The Commander's service id, digits only, one line. */
    public static final String SERVICE_ID_FILE = "/home/rsd/config/service_id";
    /** {@code CloudConnectProfile.updateProfile} writes {@code profile=<lab>} here. */
    public static final String PROFILE_FILE = CloudTunnel.PROFILE_FILE;
    /** The cloud bridge's status once it is up. */
    public static final String ONLINE = "Online";
    /** The state a failed sign-in returns the Commander to, with the error after it. */
    static final String FAILED_STATE = "INITIATE_CLOUD_CONNECT:";
    /** Sign-ins before onboarding gives up. */
    public static final int ONBOARD_ATTEMPTS = 3;

    /** {@code killCS -r}: stops coresvcs and starts it again. */
    static final String CORESVCS_RESTART =
            "su - coresvcs -c 'setsid nohup /home/coresvcs/bin/killCS -r >/dev/null 2>&1 </dev/null &'";
    /** The MQTT broker (HiveMQ Edge), which {@code killCS -r} leaves running. */
    static final String MQTT_BROKER_RESTART =
            "su - coresvcs -c 'setsid nohup /home/coresvcs/bin/killMqttBroker -r >/dev/null 2>&1 </dev/null &'";

    private final Supplier<Transport> transports;
    private final Supplier<ConnectClient> clients;
    private final ConnectTiming timing;
    private final Duration sshTimeout;
    private final Consumer<String> say;

    /**
     * @param transports fresh connected transports to the Commander's SSH (closed by this class)
     * @param clients    fresh Commander Connect clients (closed by this class)
     * @param timing     the waits; {@link ConnectTiming#defaults()} in production
     * @param sshTimeout the bound on each SSH command
     * @param say        where the progress lines go, without their prefix
     */
    public ConnectService(Supplier<Transport> transports, Supplier<ConnectClient> clients, ConnectTiming timing,
            Duration sshTimeout, Consumer<String> say) {
        this.transports = transports;
        this.clients = clients;
        this.timing = timing;
        this.sshTimeout = sshTimeout;
        this.say = say;
    }

    /**
     * Service id, lab, onboard, then Online. Each step is skipped if the Commander has it.
     *
     * @throws DeviceError when a step fails; the message names it
     */
    public void apply(CommanderConnect settings) {
        Transport transport = transports.get();
        try {
            setServiceId(transport, settings.serviceId());
            setLab(transport, settings.lab());
        } finally {
            transport.close();
        }
        onboard(settings);
        ensureOnline();
    }

    /** The service id file, written as root and owned like its directory. */
    void setServiceId(Transport transport, String serviceId) {
        if (readTrimmed(transport, SERVICE_ID_FILE).equals(serviceId)) {
            say.accept("service id already " + serviceId);
            return;
        }
        writeConfigFile(transport, SERVICE_ID_FILE, serviceId + "\n", 0644);
        say.accept("service id set to " + serviceId + " in " + SERVICE_ID_FILE);
    }

    /** Sets the profile; restarts coresvcs only when it changed. */
    void setLab(Transport transport, String lab) {
        String wanted = lab.toLowerCase(Locale.ROOT);
        String current = afterEquals(readTrimmed(transport, PROFILE_FILE)).toLowerCase(Locale.ROOT);
        if (current.isEmpty()) {
            current = "prod";
        }
        if (current.equals(wanted)) {
            say.accept("lab already " + lab);
            return;
        }
        ConnectClient client = clients.get();
        try {
            client.setCloudConnectProfile(lab.toUpperCase(Locale.ROOT));
        } catch (DeviceError exc) {
            // The base import's roles grant `manager` ccloudconnect but not ucloudconnectprofile. The handler only
            // writes `profile=<lab>` to the file, so write it as it would.
            say.accept("ucloudconnectprofile refused (" + exc.getMessage() + "); writing " + PROFILE_FILE + " directly");
            writeConfigFile(transport, PROFILE_FILE, "profile=" + wanted, 0664);
        } finally {
            client.close();
        }
        String written = afterEquals(readTrimmed(transport, PROFILE_FILE)).toLowerCase(Locale.ROOT);
        if (!written.equals(wanted)) {
            throw new DeviceError("the Commander kept lab " + (written.isEmpty() ? "(empty)" : "'" + written + "'")
                    + " after ucloudconnectprofile " + lab.toUpperCase(Locale.ROOT)
                    + " - QAT and DEV need a test-signed Commander");
        }
        say.accept("lab set to " + lab + " (was " + current + "); restarting coresvcs to apply it");
        restartCoresvcs(transport, null, "coresvcs to come back");
    }

    /**
     * {@code killCS -r}, then waits until coresvcs is back: by {@code ready}, or by Cloud Connect's own status. A caller
     * restarting it for anything else passes its own {@code ready}, because on a Commander that is not onboarded the
     * status call faults however healthy coresvcs is.
     *
     * @param ready the readiness check, or null for Cloud Connect's status
     */
    void restartCoresvcs(Transport transport, BooleanSupplier ready, String what) {
        transport.run(CORESVCS_RESTART, sshTimeout);
        // Give the old VM time to go down first, or the first poll reaches it still running.
        Bounded.pause(timing.coresvcsSettle());
        BooleanSupplier check = ready == null ? this::cloudStatusAnswers : ready;
        if (!Bounded.poll(check, timing.coresvcsTimeout(), timing.coresvcsPoll())) {
            throw new DeviceError("timed out waiting for " + what);
        }
    }

    /** Whether Cloud Connect's status answers: a login alone is not enough, SoftHSM comes up last. */
    private boolean cloudStatusAnswers() {
        ConnectClient client = clients.get();
        try {
            client.cloudConnectStatus();
            return true;
        } catch (RuntimeException exc) {
            return false;
        } finally {
            client.close();
        }
    }

    /** Signs in and waits for the registration, up to {@link #ONBOARD_ATTEMPTS} sign-ins. */
    void onboard(CommanderConnect settings) {
        ConnectClient client = clients.get();
        try {
            if (client.cloudConnectStatus().registered()) {
                say.accept("already onboarded; leaving it");
                return;
            }
            Attempt state = new Attempt();
            for (int attempt = 1; attempt <= ONBOARD_ATTEMPTS; attempt++) {
                client.cloudConnect(settings.email(), settings.password());
                say.accept("onboarding started as " + settings.email() + "; waiting for the registration");
                state.started = System.nanoTime();
                boolean settled = Bounded.poll(() -> settled(client, state), timing.onboardTimeout(),
                        timing.onboardPoll());
                if (!settled) {
                    throw new DeviceError("onboarding did not complete (" + state.detail + ")");
                }
                if (state.done) {
                    say.accept("registered");
                    return;
                }
                if (attempt < ONBOARD_ATTEMPTS) {
                    say.accept("sign-in " + attempt + " failed (" + state.detail
                            + "); again once the tunnel relays what it needed");
                    waitForRelay();
                }
            }
            throw new DeviceError("onboarding failed " + ONBOARD_ATTEMPTS + " times (" + state.detail + ")");
        } finally {
            client.close();
        }
    }

    /** One onboarding poll: done, or a failed state believed after the settle time. */
    private boolean settled(ConnectClient client, Attempt state) {
        try {
            ConnectClient.Status status = client.cloudConnectStatus();
            state.done = status.registered();
            state.detail = status.detail();
        } catch (DeviceError exc) {
            // Before a sign-in the status runs the Commander's own connectivity check, and faults while the
            // tunnel re-plans to relay a learned host. Not settled yet; the timeout still holds.
            state.detail = "status unavailable: " + exc.getMessage();
            return false;
        }
        boolean failed = state.detail.startsWith(FAILED_STATE);
        long elapsed = System.nanoTime() - state.started;
        return state.done || (failed && elapsed >= timing.onboardSettle().toNanos());
    }

    /**
     * After a failed sign-in: until the tunnel relays every host the Commander could not reach, at most
     * {@link ConnectTiming#onboardRetryWait()}. If the relay cannot be checked, waits the whole retry wait.
     */
    void waitForRelay() {
        Transport transport = null;
        try {
            Transport open = transports.get();
            transport = open;
            List<String> missing = new ArrayList<>();
            boolean relayed = Bounded.poll(() -> {
                missing.clear();
                missing.addAll(CloudTunnel.unrelayedHosts(open, sshTimeout));
                return missing.isEmpty();
            }, timing.onboardRetryWait(), timing.relayPoll());
            if (relayed) {
                say.accept("the tunnel relays every host the sign-in needed");
            } else {
                say.accept("not relayed after " + timing.onboardRetryWait().toSeconds() + " s: "
                        + String.join(", ", missing));
            }
        } catch (RuntimeException exc) {
            // The Python's (DeviceError, DeviceUnavailable, OSError): a transport that cannot be reached either way.
            say.accept("could not check the relay (" + exc.getMessage() + "); waiting "
                    + timing.onboardRetryWait().toSeconds() + " s");
            Bounded.pause(timing.onboardRetryWait());
        } finally {
            if (transport != null) {
                transport.close();
            }
        }
    }

    /**
     * Registered is not connected: until {@code cloudConnectivityStatus} reads Online. Offline gets one MQTT broker
     * restart; still Offline after {@link ConnectTiming#bridgeSettle()}, one coresvcs restart; then the online timeout.
     */
    void ensureOnline() {
        String[] seen = {connectivity()};
        if (ONLINE.equals(seen[0])) {
            say.accept("cloud connectivity Online");
            return;
        }
        say.accept("registered but cloud connectivity " + orUnknown(seen[0]) + "; restarting the MQTT broker");
        Transport transport = transports.get();
        try {
            transport.run(MQTT_BROKER_RESTART, sshTimeout);
        } finally {
            transport.close();
        }
        BooleanSupplier online = () -> {
            seen[0] = connectivity();
            return ONLINE.equals(seen[0]);
        };
        if (Bounded.poll(online, timing.bridgeSettle(), timing.onlinePoll())) {
            say.accept("cloud connectivity Online");
            return;
        }
        // The bridge may be up while coresvcs still reads Offline: its view of the bridge comes from the broker's
        // status topic, which it did not hear after the restart.
        say.accept("cloud connectivity still " + orUnknown(seen[0]) + " after the broker restart; restarting coresvcs");
        transport = transports.get();
        try {
            restartCoresvcs(transport, null, "coresvcs to come back");
        } finally {
            transport.close();
        }
        if (!Bounded.poll(online, timing.onlineTimeout(), timing.onlinePoll())) {
            throw new DeviceError("registered, but cloud connectivity still " + orUnknown(seen[0]) + " "
                    + timing.onlineTimeout().toSeconds() + " s after an MQTT broker and a coresvcs restart - the"
                    + " broker's syslog lines (MBR, hivemq-cmdr-cloud-bridge) say why");
        }
        say.accept("cloud connectivity Online");
    }

    private String connectivity() {
        ConnectClient client = clients.get();
        try {
            return client.cloudConnectivity();
        } catch (DeviceError exc) {
            return "status unavailable: " + exc.getMessage();
        } finally {
            client.close();
        }
    }

    /** Writes {@code path} and makes it owned like its directory (the transport writes as root). */
    void writeConfigFile(Transport transport, String path, String text, int mode) {
        transport.put(path, text.getBytes(StandardCharsets.UTF_8), mode, sshTimeout);
        String parent = path.substring(0, path.lastIndexOf('/'));
        CommandResult owned = transport.run("chown --reference=" + parent + " " + path, sshTimeout);
        if (!owned.ok()) {
            String detail = owned.stderr().isEmpty() ? owned.stdout() : owned.stderr();
            throw new DeviceError("chown " + path + " failed: " + detail.strip());
        }
    }

    /** A file's text with surrounding whitespace removed, or empty when it is absent. */
    private String readTrimmed(Transport transport, String path) {
        CommandResult result = transport.run("cat " + path, sshTimeout);
        return result.ok() ? result.stdout().strip() : "";
    }

    /** The text after the first {@code =}, stripped; empty when there is none. */
    static String afterEquals(String text) {
        int eq = text.indexOf('=');
        return eq < 0 ? "" : text.substring(eq + 1).strip();
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    /** One onboarding's progress, shared between the poll and its caller. */
    private static final class Attempt {
        boolean done;
        String detail = "no status yet";
        long started;
    }
}
