package com.verifone.cat.commander.links;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.provision.Checklist;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * One gate for the Commander config that only takes effect after a restart: check everything first, import
 * only what is missing, restart at most once, and never restart a Commander that is already ready. A port of
 * {@code petrotest.commander_links} (the parts CAT-Java needs).
 *
 * <p>What "applied" means is a live connection, not a file: a run cannot take a card payment unless the EPS
 * holds its connection to the payment host, so {@link #established} reads {@code netstat -ant} for that
 * socket. A socket in {@code CLOSE_WAIT} does not count, nor does a file's timestamp.
 *
 * <p>The restart is the Commander's own {@code stopapp -r}, run from {@code pmc}'s login shell
 * ({@link #PMC_RESTART}): restarting the one service replaces a reboot (rig owner, 2026-09-21).
 *
 * <p>The payment-link flow behind {@code petrotest payment-links} (step 4's call, Python
 * {@code payment_links_cli}) is {@link PaymentLinks}, which the Commander configuration launch step calls.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The observations behind the parsing are real (Python
 * {@code commander_links}, 2026-09-21 and 2026-10-07); the parsing is tested only against rows copied from them.
 */
public final class CommanderLinks {

    /** Buypass over UMF: the port the FEP config sets, a property of the protocol, not of a rig. */
    public static final int BUYPASS_FEP_PORT = Checklist.BUYPASS_UMF_PORT;

    /** Petro Simulator Suite's ViperPay EPS face, {@code connectWithSSL}, the POP 1 port. */
    public static final int VIPERPAY_POP_PORT = 5000;

    /**
     * The Commander's supported restart of the payment controller, run from {@code pmc}'s own login shell
     * ({@code su -}). {@code sudo} from root's home starts a PMC that never logs (measured 2026-09-21).
     */
    public static final List<String> PMC_RESTART = List.of("su - pmc -c '/home/pmc/bin/stopapp -r'");

    /** Where PMC keeps POP 1's address once {@code vipercfg} is imported (read 2026-10-06). */
    public static final String POP_TABLE = "/home/pmc/data/tables/POPConfigTable.xml";

    /** The socket read: {@code netstat -ant}, the same on the busybox and GNU builds. */
    public static final String NETSTAT = "netstat -ant";

    /** Ceiling of one command over SSH (the Python's 30 s default). */
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /** The restart's ceiling (the Python's 120 s default). */
    public static final Duration RESTART_TIMEOUT = Duration.ofSeconds(120);

    /** How long the links may take to come back after one restart. */
    public static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(420);

    /** {@code EPSLogInTimerTask} fires every 10 minutes; the wait for the next login. */
    public static final Duration EPS_LOGIN_WAIT = Duration.ofSeconds(660);

    /** Between two reads of the links, or of the syslog. */
    public static final Duration POLL = Duration.ofSeconds(15);

    /** The restart the gate performs at most once: {@link #restartPmc} with its own ceiling. */
    public static final Consumer<Transport> RESTART_PMC = transport -> restartPmc(transport, RESTART_TIMEOUT);

    /**
     * The Buypass FEP settings that decide whether the EPS can talk to the controller's simulator at all. A
     * socket to 41107 proves none of them (run {@code c5c26c1b2b08}, 2026-10-12: sslEnabled true, plaintext
     * UMF host reading a TLS ClientHello).
     */
    public static final List<String> FEP_LINK_FIELDS = List.of("port", "sslEnabled", "secureChannelEnabled");

    /** The line the payment-link flow prints when it found {@link #FEP_LINK_FIELDS} drift and fixed it. */
    public static final String FEP_DRIFT_FIXED = "payment-links: Buypass FEP drift fixed";

    static final String EPS_LOGIN_MARK = "LogIn [*][*][*] Success";
    static final String EPS_LOGIN_TEXT = "LogIn *** Success";

    /** Socket states that mean the Commander is holding a working connection. CLOSE_WAIT is not one. */
    static final Set<String> LIVE_STATES = com.verifone.cat.devices.eps.CommanderLinks.LIVE_STATES;

    /** States in which a socket never carried a byte to its peer. Every other state is accepted by the far end. */
    static final Set<String> UNCONNECTED_STATES = com.verifone.cat.devices.eps.CommanderLinks.UNCONNECTED_STATES;

    private static final Pattern POP_ADDRESS = Pattern.compile("<IPAddress>\\s*([^<\\s]+)\\s*</IPAddress>");
    private static final Pattern VIPER_ADDRESS = Pattern.compile("(<ipAddress>)[^<]*(</ipAddress>)");

    private CommanderLinks() {}

    /** One row of {@code netstat -ant}: local endpoint, peer host and port, state. */
    public record NetRow(String local, String peerHost, int peerPort, String state) {}

    // ----------------------------------------------------------------------------------------------------
    // What the plan wants
    // ----------------------------------------------------------------------------------------------------

    /**
     * The links a card sale needs that a socket can honestly report: just the Buypass FEP. It is a heartbeat
     * channel, so the Commander holds it {@code ESTABLISHED} continuously. The ViperPay POP is deliberately not
     * here: it is request/response, and between exchanges its socket sits in {@code CLOSE_WAIT}.
     */
    public static List<CommanderLink> paymentLinks(Rig rig) {
        // The read-only probe's list (devices.eps), so preflight and the bring-up never disagree on what is wanted.
        return com.verifone.cat.devices.eps.CommanderLinks.paymentLinks(rig).stream()
                .map(link -> new CommanderLink(link.name(), link.host(), link.port())).toList();
    }

    /** Where POP 1 must point: the physical terminal on a {@code pinpad: vats} rig, else the controller. */
    public static String pinpadHost(Rig rig) {
        if (rig.pinpad() == Rig.Pinpad.VATS && rig.vats() != null) {
            return rig.vats().host();
        }
        return rig.controller().host();
    }

    /** Splits {@code wanted} into what the Commander already holds and what it does not. */
    public static LinkPlan plan(List<CommanderLink> wanted, Set<CommanderLink.Endpoint> established) {
        List<CommanderLink> present = new ArrayList<>();
        List<CommanderLink> missing = new ArrayList<>();
        for (CommanderLink link : wanted) {
            (established.contains(link.endpoint()) ? present : missing).add(link);
        }
        return new LinkPlan(present, missing, false, List.of());
    }

    // ----------------------------------------------------------------------------------------------------
    // What the Commander holds
    // ----------------------------------------------------------------------------------------------------

    /**
     * Peer endpoints the Commander holds a live socket to. A row not in {@link #LIVE_STATES} is not reported, so
     * a half-closed socket reads as missing rather than as a working link.
     *
     * @throws IllegalStateException {@code netstat} exited non-zero: an unavailable probe is not "nothing connected"
     */
    public static Set<CommanderLink.Endpoint> established(Transport transport, Duration timeout) {
        Set<CommanderLink.Endpoint> out = new java.util.HashSet<>();
        for (NetRow row : netstatRows(transport, timeout)) {
            if (LIVE_STATES.contains(row.state())) {
                out.add(new CommanderLink.Endpoint(row.peerHost(), row.peerPort()));
            }
        }
        return out;
    }

    /**
     * The Commander's sockets to {@code host:port} that the far end accepted: local endpoint to state. For a
     * request/response peer (the physical pinpad's POP port), where a held {@code ESTABLISHED} socket is the wrong
     * test: on 2026-10-07 the lab's sockets to the M400 sat in {@code LAST_ACK} (HANDOFF.md section 1.111).
     */
    public static java.util.Map<String, String> peerSockets(Transport transport, String host, int port,
            Duration timeout) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (NetRow row : netstatRows(transport, timeout)) {
            if (row.peerHost().equals(host) && row.peerPort() == port && !UNCONNECTED_STATES.contains(row.state())) {
                out.put(row.local(), row.state());
            }
        }
        return out;
    }

    /**
     * {@code netstat -ant}, as rows of (local endpoint, peer host, peer port, state). Reads the last three columns,
     * which is the shape of both builds on these devices; header and listener rows fall out (no numeric peer port).
     *
     * @throws IllegalStateException {@code netstat} exited non-zero
     */
    static List<NetRow> netstatRows(Transport transport, Duration timeout) {
        CommandResult result = transport.run(NETSTAT, timeout);
        if (!result.ok()) {
            throw new IllegalStateException("netstat on the Commander exited " + result.exitStatus());
        }
        // The parse is the read-only probe's (devices.eps), one rule for both.
        List<NetRow> rows = new ArrayList<>();
        for (com.verifone.cat.devices.eps.CommanderLinks.NetstatRow row
                : com.verifone.cat.devices.eps.CommanderLinks.parse(result.stdout())) {
            rows.add(new NetRow(row.local(), row.peerHost(), row.peerPort(), row.state()));
        }
        return rows;
    }


    // ----------------------------------------------------------------------------------------------------
    // The gate
    // ----------------------------------------------------------------------------------------------------

    /**
     * Checks, imports once if needed, restarts at most once, and waits for the links and for the next EPS login.
     * A Commander already holding every link is left alone: no import, and no restart. That is what makes running
     * this before every run affordable.
     *
     * @param importConfig applies the configuration behind the links, once for all missing links; may be null
     * @param restart      the one restart, normally {@link #RESTART_PMC}
     * @param force        act even when every link is up (a wrong POP, or FEP drift)
     * @throws IllegalStateException {@code netstat} fails, or the import raises
     */
    public static LinkPlan ensurePaymentLinks(List<CommanderLink> wanted, Transport transport, Runnable importConfig,
            Consumer<Transport> restart, Pauser pauser, boolean force) {
        LinkPlan plan = plan(wanted, established(transport, COMMAND_TIMEOUT));
        if (plan.ready() && !force) {
            return plan;
        }
        List<String> notes = new ArrayList<>();
        notes.add(plan.summary());
        if (importConfig != null) {
            importConfig.run();
            notes.add("imported the simulator payment configuration");
        }
        Optional<String> loginBefore = epsLastLogin(transport, COMMAND_TIMEOUT);
        restart.accept(transport);
        notes.add("restarted the Commander's EPS once");

        boolean settled = pauser.until(() -> plan(wanted, established(transport, COMMAND_TIMEOUT)).ready(),
                SETTLE_TIMEOUT, POLL);
        if (!settled) {
            notes.add("timed out after " + SETTLE_TIMEOUT.toSeconds()
                    + " s waiting for the Commander's payment links after one restart");
        }

        // A link up is not a register that can use it. After a PMC restart the COR logs back into the EPS only on
        // its 10-minute timer, so wait for the next login before calling the Commander ready.
        if (loginBefore.isPresent()) {
            String before = loginBefore.get();
            boolean again = pauser.until(() -> {
                Optional<String> latest = epsLastLogin(transport, COMMAND_TIMEOUT);
                return latest.isPresent() && !latest.get().isEmpty() && !latest.get().equals(before);
            }, EPS_LOGIN_WAIT, POLL);
            notes.add(again ? "the Commander logged back into its EPS"
                    : "timed out after " + EPS_LOGIN_WAIT.toSeconds()
                            + " s waiting for the Commander's COR logging back into the EPS after the restart");
        }

        LinkPlan finalPlan = plan(wanted, established(transport, COMMAND_TIMEOUT));
        return new LinkPlan(finalPlan.present(), finalPlan.missing(), true, notes);
    }

    /**
     * {@code killPMC}-then-{@code startPMC}, as {@code pmc}: the one restart a plan is allowed.
     *
     * @throws IllegalStateException the command exited non-zero
     */
    public static void restartPmc(Transport transport, Duration timeout) {
        for (String command : PMC_RESTART) {
            CommandResult result = transport.run(command, timeout);
            if (!result.ok()) {
                throw new IllegalStateException("'" + command + "' exited " + result.exitStatus() + ": "
                        + result.stderr().strip());
            }
        }
    }

    /**
     * COR's newest EPS login line in the syslog, timestamp and all. Present and empty when the current file holds
     * none; empty Optional when it cannot be read. A newer line is a new login (counting logins in a window of the
     * syslog stopped rising when the file rotated, 2026-09-28).
     */
    public static Optional<String> epsLastLogin(Transport transport, Duration timeout) {
        CommandResult result;
        try {
            result = transport.run("grep -E '" + EPS_LOGIN_MARK + "' /var/log/messages | tail -n 1", timeout);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String line = result.stdout() == null ? "" : result.stdout().strip();
        if (result.exitStatus() != 0 || (!line.isEmpty() && !line.contains(EPS_LOGIN_TEXT))) {
            return Optional.empty();
        }
        return Optional.of(line);
    }

    // ----------------------------------------------------------------------------------------------------
    // POP 1 and the Buypass FEP's protocol
    // ----------------------------------------------------------------------------------------------------

    /** The address PMC dials for POP 1, read from its table; empty when unreadable. */
    public static Optional<String> popAddress(Transport transport, Duration timeout) {
        CommandResult result;
        try {
            result = transport.run("cat " + POP_TABLE, timeout);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (result.exitStatus() != 0) {
            return Optional.empty();
        }
        Matcher match = POP_ADDRESS.matcher(result.stdout() == null ? "" : result.stdout());
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    /**
     * Each {@link #FEP_LINK_FIELDS} setting the live FEP config has other than {@code wanted}'s. Only fields
     * {@code wanted} sets are compared; one the live config lacks counts as drift.
     */
    public static List<String> fepConfigDrift(String live, String wanted) {
        List<String> drift = new ArrayList<>();
        for (String name : FEP_LINK_FIELDS) {
            String want = fepField(wanted, name);
            if (want == null) {
                continue;
            }
            String have = fepField(live, name);
            if (!want.equals(have)) {
                drift.add(name + " " + (have == null ? "absent" : have) + " (want " + want + ")");
            }
        }
        return drift;
    }

    static String fepField(String xml, String name) {
        Matcher match = Pattern.compile("<(?:\\w+:)?" + name + ">\\s*([^<]*?)\\s*</(?:\\w+:)?" + name + ">")
                .matcher(xml);
        return match.find() ? match.group(1) : null;
    }

    /** petroapps' lab payment config folder, Buypass over UMF with POP 1 on the controller. */
    public static Path simulatorConfigFolder(Path petroappsRoot) {
        return petroappsRoot.resolve("AutomationTests").resolve("configurations")
                .resolve("buypass-host-simulator-config");
    }

    /** petroapps' {@code vipercfg} for a physical pinpad: the POP 1 on the terminal. */
    public static Path pinpadConfigFolder(Path petroappsRoot) {
        return petroappsRoot.resolve("AutomationTests").resolve("configurations")
                .resolve("buypass-viper-pinpad-devices");
    }

    /**
     * The two payment files this rig needs, in {@code into}, with POP 1 pointed at {@link #pinpadHost}. The
     * simulator folder's files, except {@code vipercfg.xml}; {@code vipercfg.xml} from the physical-pinpad folder on
     * a VATS rig, else from the simulator folder. Both petroapps copies hardcode the lab's addresses, so POP 1's is
     * rewritten to the rig file's (DESIGN.md section 4.2 rule 5).
     *
     * @throws IOException a file cannot be read or written
     */
    public static Path stagePaymentConfig(Path petroappsRoot, Rig rig, Path into) throws IOException {
        Path simulator = simulatorConfigFolder(petroappsRoot);
        Path viperSource = rig.pinpad() == Rig.Pinpad.VATS ? pinpadConfigFolder(petroappsRoot) : simulator;
        Files.createDirectories(into);
        try (Stream<Path> files = Files.list(simulator)) {
            for (Path source : files.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                if (!source.getFileName().toString().equals("vipercfg.xml")) {
                    Files.write(into.resolve(source.getFileName()), Files.readAllBytes(source));
                }
            }
        }
        String viper = Files.readString(viperSource.resolve("vipercfg.xml"), StandardCharsets.UTF_8);
        String host = pinpadHost(rig);
        Matcher address = VIPER_ADDRESS.matcher(viper);
        String rewritten = address.find()
                ? viper.substring(0, address.start()) + address.group(1) + host + address.group(2)
                        + viper.substring(address.end())
                : viper;
        Files.writeString(into.resolve("vipercfg.xml"), rewritten, StandardCharsets.UTF_8);
        return into;
    }
}
