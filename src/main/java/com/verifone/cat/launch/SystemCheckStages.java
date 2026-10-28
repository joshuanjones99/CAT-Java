package com.verifone.cat.launch;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.StoredArtifactoryCredentials;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.connections.ConnectionProbe;
import com.verifone.cat.connections.Endpoints;
import com.verifone.cat.connections.Readiness;
import com.verifone.cat.connections.RigReadiness;
import com.verifone.cat.devices.commander.CommanderSuiteInfo;
import com.verifone.cat.devices.printer.SerialPorts;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ProcessOps;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimRunning;
import com.verifone.cat.sims.Supervisor;

/**
 * What System Checks asks outside this process on a rig run, one method each, so a test passes fakes: the rig's
 * network lines, the serial port listing, the Commander's {@code suiteinfo.dat}, the Artifactory client, which
 * simulator rows are running, and stopping a row built for the wrong base.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): {@link #real} reaches the rig's addresses, this PC's COM ports, the
 * Commander over SSH and Artifactory; the tests never call it.
 */
interface SystemCheckStages {

    /** The budget for each rig network probe: the Python CAT's {@code check_rig} default. */
    Duration READINESS_TIMEOUT = Duration.ofSeconds(3);

    /** The Artifactory client's per-request budget for a simulator or Java download. */
    Duration ARTIFACTORY_TIMEOUT = Duration.ofMinutes(10);

    /** The rig's network lines: the controller's rig adapter, the Commander's port and its CGILink answer. */
    List<Readiness> rigNetwork(Rig rig);

    /** This PC's serial ports, for the printer lines. */
    SerialPorts serialPorts();

    /** The Commander's {@code suiteinfo.dat}, with its {@code Base_Level}; throws when it cannot be read. */
    Map<String, String> suiteinfo(Rig rig);

    /** A client on Artifactory; throws {@link com.verifone.cat.build.ArtifactoryAuthError} with no login. */
    ArtifactoryClient artifactory();

    /** Row name to why it is running, for those of {@code entries} that are. */
    Map<String, String> running(List<SimEntry> entries, SimContext sims);

    /** Stops every running instance of {@code entries} (only what the supervisor would adopt, never by image name). */
    Map<String, Supervisor.StopRow> stopRunning(RunContext ctx, List<SimEntry> entries, SimContext sims);

    /**
     * How step 1 opens the register's printer port for its hold ({@link PrinterHold}). None by default, so a test's
     * fake never opens a real serial port; {@link #real} opens {@link com.verifone.cat.devices.printer.TopazPrinter}.
     */
    default PrinterHold.Opener printerOpener() {
        return PrinterHold.Opener.NONE;
    }

    /** Whether step 4's cloud tunnel is ported: the production stages' answer ({@link CommanderStages#cloudTunnelPorted}). */
    default boolean cloudTunnelPorted() {
        return CommanderStages.real(Map.of()).cloudTunnelPorted();
    }

    /** The real stages: the rig's addresses, jSerialComm, SSH to the Commander, the environment's Artifactory login. */
    static SystemCheckStages real(ProcessOps ops, Map<String, String> environ) {
        return new SystemCheckStages() {
            @Override
            public PrinterHold.Opener printerOpener() {
                return PrinterHold.Opener.TOPAZ;
            }

            @Override
            public List<Readiness> rigNetwork(Rig rig) {
                ExecutorService pool = ConnectionProbe.newProbePool();
                try {
                    return new ArrayList<>(new RigReadiness(new ConnectionProbe(pool)).check(Endpoints.of(rig),
                            READINESS_TIMEOUT));
                } finally {
                    pool.shutdownNow();
                }
            }

            @Override
            public SerialPorts serialPorts() {
                return SerialPorts.SYSTEM;
            }

            @Override
            public Map<String, String> suiteinfo(Rig rig) {
                CommanderAccess access = new CommanderAccess(rig);
                return CommanderSuiteInfo.read(access::connectedTransport, access.sshTimeout());
            }

            @Override
            public ArtifactoryClient artifactory() {
                return new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                        StoredArtifactoryCredentials.forEnvironment(environ), ARTIFACTORY_TIMEOUT);
            }

            @Override
            public Map<String, String> running(List<SimEntry> entries, SimContext sims) {
                return SimRunning.runningRows(entries, sims, ops);
            }

            @Override
            public Map<String, Supervisor.StopRow> stopRunning(RunContext ctx, List<SimEntry> entries,
                    SimContext sims) {
                return ctx.supervisor().stopRunning(entries, sims, Supervisor.STOP_WAIT);
            }
        };
    }
}
