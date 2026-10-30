package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.devices.DeviceUnreachable;

/**
 * Verifone's KM4 relay: the one process that holds the terminal's VATS connection. A port of the Python CAT's
 * {@code petrotest.devices.vats.relay.Relay}.
 *
 * <p>{@code vats_connector.py} (in the staged SDK) dials the terminal's VATS agent, {@code <terminal>:22001}, and
 * serves test scripts on a local port, {@code km4_port} (11000). PATS's Terminal Connector starts it as a child of its
 * {@code main.py}; CAT starts it itself, with the same arguments {@code patslib}'s {@code getVatsConnectorCommand}
 * builds plus the terminal port. A relay already serving {@code km4_port} (a CAT session, or PATS's connector) is
 * adopted and never stopped.
 *
 * <p>The two ports: 22001 is on the <em>terminal</em> ({@code --tcpport}); 11000 is on <em>this PC</em>
 * ({@code --km4_port}). Both rig files had them backwards until 2026-10-06.
 *
 * <p>Stopping: {@code vats_connector.py} forks {@code multiprocessing} children (the terminal side and an event store),
 * so killing the parent alone leaves them holding the terminal. The Python runs {@code taskkill /T /F /PID}; this kills
 * the started process's descendants and then the process, by its handle: the same tree, never by image name.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's relay ran against the lab M400 on 2026-10-06 (its session
 * up within a few seconds); this class has run only against a stand-in script.
 */
public final class VatsRelay {

    /** The relay's log in the work folder. */
    public static final String LOG_FILE = "relay.log";

    /** How long a started relay gets to open its port: the Python's 20 s. */
    public static final Duration START_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration POLL = Duration.ofMillis(200);

    private final VatsSdk sdk;
    private final String terminalHost;
    private final int terminalPort;
    private final int km4Port;
    private final Path workdir;
    private final Pauser pauser;
    private Process process;
    private boolean adopted;

    public VatsRelay(VatsSdk sdk, String terminalHost, int terminalPort, int km4Port, Path workdir) {
        this(sdk, terminalHost, terminalPort, km4Port, workdir, Pauser.REAL);
    }

    VatsRelay(VatsSdk sdk, String terminalHost, int terminalPort, int km4Port, Path workdir, Pauser pauser) {
        this.sdk = sdk;
        this.terminalHost = terminalHost;
        this.terminalPort = terminalPort;
        this.km4Port = km4Port;
        this.workdir = workdir;
        this.pauser = pauser;
    }

    /** The relay's command line, as {@code getVatsConnectorCommand} builds it, plus the terminal port. */
    public List<String> argv() {
        return List.of(sdk.python().toString(), sdk.relayScript().toString(),
                "--tcpip", terminalHost,
                "--tcpport", String.valueOf(terminalPort),
                "--km4_port", String.valueOf(km4Port),
                "--ipc_comm", "0",
                "--workingdir", workdir.toString(),
                "--autoreconnection");
    }

    /** Whether a relay that was already running was adopted rather than started. */
    public boolean adopted() {
        return adopted;
    }

    /** Starts the relay unless one already serves {@code km4_port}; waits for its port. */
    public VatsRelay start() {
        return start(START_TIMEOUT);
    }

    /** {@link #start()} with its own budget. */
    public VatsRelay start(Duration timeout) {
        if (Ports.listening(km4Port)) {
            adopted = true;
            return this;
        }
        Path log = workdir.resolve(LOG_FILE);
        try {
            Files.createDirectories(workdir);
            ProcessBuilder builder = new ProcessBuilder(argv())
                    .directory(sdk.app().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            builder.environment().put("PYTHONPATH", sdk.vatsRunner().toString());
            process = builder.start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new DeviceUnreachable("the VATS relay", "could not start " + argv().get(0) + ": " + e.getMessage(),
                    e);
        }
        boolean up;
        try {
            up = pauser.until(() -> {
                Process p = process;
                if (p != null && !p.isAlive()) {
                    throw new DeviceUnreachable("the VATS relay", "exited with " + p.exitValue()
                            + " before opening port " + km4Port + "; see " + log);
                }
                return Ports.listening(km4Port);
            }, timeout, POLL);
        } catch (DeviceUnreachable e) {
            process = null;
            throw e;
        }
        if (!up) {
            stop();
            throw new VatsTimeout("VATS relay on port " + km4Port, timeout.toMillis() / 1000.0);
        }
        return this;
    }

    /** Stops a relay CAT started, children included; an adopted one is left alone. */
    public void stop() {
        Process p = process;
        process = null;
        if (p == null || !p.isAlive()) {
            return;
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        try {
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
