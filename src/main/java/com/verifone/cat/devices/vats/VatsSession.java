package com.verifone.cat.devices.vats;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Everything a run needs to drive one physical pinpad, started in order, stopped together. A port of the Python CAT's
 * {@code petrotest.devices.vats.pinpad.VatsSession}.
 *
 * <p>{@link #open}: BTT's card server ({@link Btt#startCardServer}, which adopts one already up), Verifone's KM4 relay
 * ({@link VatsRelay}, adopted when running), CAT's agent ({@link AgentClient} running the shipped
 * {@link AgentScript}), the terminal's {@code hello}, then a card an earlier session left in the reader taken out.
 * {@link #openTerminal}: the relay and the agent only, no BTT, enough to read the terminal and move its simulated card
 * ({@code cat-java pinpad-clear}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class VatsSession implements AutoCloseable {

    /** PATS's MSR track file, relative to the petroapps checkout ({@code MSRCardDataXMLHandler}). */
    public static final Path MSR_DATA = Path.of("isdApps", "pcapps", "simulators", "app", "config", "MSRCardData.xml");

    /** The terminal's {@code hello} budget, and its response time, as the Python's: 20 s. */
    static final double HELLO_BUDGET = 20.0;
    static final int HELLO_RESPONSE_MS = 20000;

    /**
     * Where a session's pieces are on this controller.
     *
     * @param toolsRoot     the staged simulators' root (the SDK is its {@code terminalConnector-V4})
     * @param petroappsRoot the petroapps checkout: the MSR track file and the cases' own card images
     * @param agentDir      where the shipped {@code agent.py} is written ({@code ~/.cat/java/vats})
     * @param btt           the BTT installs and their card server
     */
    public record Environment(Path toolsRoot, Path petroappsRoot, Path agentDir, Btt btt) {

        /** This controller's: {@code CAT_TOOLS_ROOT}, {@code PETROAPPS_ROOT}, the user's home. */
        public static Environment forThisMachine(Map<String, String> env) {
            Path tools = ToolsRoot.resolve(env.get(ToolsRoot.ENV), null);
            String petroapps = env.getOrDefault("PETROAPPS_ROOT", "");
            Path home = Path.of(System.getProperty("user.home"));
            return new Environment(tools,
                    Path.of(petroapps.isEmpty() ? Collect.DEFAULT_PETROAPPS_ROOT : petroapps),
                    home.resolve(".cat").resolve("java").resolve("vats"),
                    Btt.forThisMachine(home, tools));
        }

        /** The staged connector's folder. */
        public Path connectorRoot() {
            return toolsRoot.resolve(VatsSdk.CONNECTOR_DIR);
        }

        /** {@code <petroapps>/isdApps/pcapps/simulators/app/config/MSRCardData.xml}. */
        public Path msrDataFile() {
            return petroappsRoot.resolve(MSR_DATA);
        }

        /** {@link CardImages}' search order: the cases' own images, every BTT newest first, the connector's. */
        public List<Path> imageRoots(VatsSdk sdk) {
            List<Path> roots = new ArrayList<>();
            roots.add(petroappsRoot.resolve("AutomationTests").resolve("configurations").resolve("Collis_Images"));
            for (Btt.Install install : btt.installs()) {
                roots.add(install.images());
            }
            roots.add(sdk.collisImages());
            return roots;
        }
    }

    /** {@code <results root>/vats}: where the relay and the agent log, as the Python's {@code results_root() / "vats"}. */
    public static Path workdir(Map<String, String> env) {
        return ReportPaths.resultsRoot(env).resolve("vats");
    }

    private final VatsRelay relay;
    private final VatsAgent agent;
    private final VatsPinpad pinpad;
    private final Map<String, Object> terminal;

    VatsSession(VatsRelay relay, VatsAgent agent, VatsPinpad pinpad, Map<String, Object> terminal) {
        this.relay = relay;
        this.agent = agent;
        this.pinpad = pinpad;
        this.terminal = terminal;
    }

    public VatsPinpad pinpad() {
        return pinpad;
    }

    /** What the terminal said to {@code hello}: model, version, os, region. */
    public Map<String, Object> terminal() {
        return terminal;
    }

    /** The full session: BTT's card server, the relay, the agent and the terminal's hello. */
    public static VatsSession open(Rig rig, Path workdir, Consumer<String> log, Environment env) {
        Rig.Vats config = requireVats(rig);
        VatsSdk sdk = VatsSdk.locate(env.connectorRoot());
        Btt.CardServerStart started = env.btt().startCardServer(config.cardServerPort(), config.interfaceKind(),
                config.automationPort(), config.bttVersion(), Btt.DEFAULT_START_TIMEOUT, log);
        log.accept("BTT card server: " + started.route() + " — " + started.detail());
        VatsRelay relay = new VatsRelay(sdk, config.host(), config.port(), config.km4Port(), workdir).start();
        AgentClient agent = new AgentClient(sdk, config.km4Port(), workdir, AgentScript.extract(env.agentDir()));
        Map<String, Object> terminal;
        try {
            agent.start();
            terminal = agent.call("hello", HELLO_BUDGET, Map.of("response_ms", HELLO_RESPONSE_MS));
            if (!VatsAgent.truthy(terminal, "model")) {
                throw new DeviceUnreachable("the terminal at " + config.host() + ":" + config.port(), "the VATS relay "
                        + "connected but the terminal never identified itself — is it running the VATS-enabled OS and "
                        + "VIPA packages?");
            }
        } catch (RuntimeException e) {
            agent.close();
            relay.stop();
            throw e;
        }
        log.accept("VATS terminal: " + terminal.get("model") + " (" + terminal.get("version") + ")");
        VatsPinpad pinpad = new VatsPinpad(agent, config, new CardImages(env.imageRoots(sdk)), env.msrDataFile(),
                promptWait(rig), started.ok() ? null : started.detail());
        String cleared = pinpad.clearStuckCard();
        if (!cleared.isEmpty()) {
            log.accept("VATS terminal: " + cleared);
        }
        return new VatsSession(relay, agent, pinpad, terminal);
    }

    /** The relay and agent only, no BTT: its pinpad loads no card image. */
    public static VatsSession openTerminal(Rig rig, Path workdir, Environment env) {
        Rig.Vats config = requireVats(rig);
        VatsSdk sdk = VatsSdk.locate(env.connectorRoot());
        VatsRelay relay = new VatsRelay(sdk, config.host(), config.port(), config.km4Port(), workdir).start();
        AgentClient agent = new AgentClient(sdk, config.km4Port(), workdir, AgentScript.extract(env.agentDir()));
        Map<String, Object> terminal;
        try {
            agent.start();
            terminal = agent.call("hello", HELLO_BUDGET, Map.of("response_ms", HELLO_RESPONSE_MS));
        } catch (RuntimeException e) {
            agent.close();
            relay.stop();
            throw e;
        }
        VatsPinpad pinpad = new VatsPinpad(agent, config, new CardImages(List.of()), env.msrDataFile(),
                promptWait(rig), "BTT is not started for a terminal-only session");
        return new VatsSession(relay, agent, pinpad, terminal);
    }

    private static Rig.Vats requireVats(Rig rig) {
        if (rig.vats() == null) {
            throw VatsSdk.unavailable("pinpad: vats", "the rig has no vats: block");
        }
        return rig.vats();
    }

    private static Duration promptWait(Rig rig) {
        return Timeouts.of(rig.timeouts()).promptWait();
    }

    @Override
    public void close() {
        pinpad.close();
        agent.close();
        relay.stop();
    }
}
