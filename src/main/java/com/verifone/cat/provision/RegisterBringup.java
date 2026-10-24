package com.verifone.cat.provision;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.posshim.ShimReplies.Health;
import com.verifone.cat.preflight.Bounded;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * Starts the register's pos-shim, and replaces one that is not the payload's build (2026-10-16). A port of
 * {@code petrotest.provision.register_bringup}.
 *
 * <p>{@link EnsurePosShimCurrent} compares {@code GET /health}'s {@code build} with the payload's stamp. When they
 * differ it does what {@code bridge/pos-shim/README.md} says a replacement takes: restart the shim, restart the POS,
 * then wait until {@code /health} shows the POS drivable with state on the new build. {@link EnsurePosShimRunning}
 * is the {@code start} the launcher keeps idempotent.
 *
 * <p>The register's POS restart is {@link #POS_APP_RESTART}; it is detached (nohup) because the POS restart
 * outlives the SSH session that issues it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). No register has been through this port.
 */
public final class RegisterBringup {

    private static final Logger log = LoggerFactory.getLogger(RegisterBringup.class);

    /** {@code petrotest-pos-shim.sh}, where the pos-shim artifacts land. */
    public static final String POS_SHIM_SCRIPT = "/home/topaz/automation/petrotest-pos-shim.sh";

    /** The launcher's log, truncated at each start. */
    public static final String POS_SHIM_LOG = "/home/topaz/automation/petrotest-pos-shim.log";

    /** The launcher refuses to run as anyone but {@code topaz}. */
    static final String SHIM_USER = "topaz";

    /** One {@code GET /health}. */
    public static final Duration HEALTH_READ = Duration.ofSeconds(5);

    /** How long a restarted shim may take to answer {@code /health} again. */
    public static final Duration SHIM_START = Duration.ofSeconds(60);

    public static final Duration SHIM_START_POLL = Duration.ofSeconds(1);

    /** The POS's settle budget after a restart: README says "a couple of minutes"; 1 m 42 s on Reg101. */
    public static final Duration POS_APP_SETTLE = Duration.ofSeconds(300);

    public static final Duration POS_APP_POLL = Duration.ofSeconds(5);

    /** Drops a serial lock whose owner is gone, so the POS's printer port opens after a restart. */
    private static final String DROP_STALE_SERIAL_LOCKS = "for f in /var/lock/LCK..tty*; do [ -f \"$f\" ] || continue; "
            + "pid=$(tr -dc 0-9 < \"$f\"); [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null || rm -f \"$f\"; done";

    /** {@code /etc/init.d/apps stop && start}, detached. The POS loads the shim's observer only at its start. */
    public static final String POS_APP_RESTART = "nohup sh -c '/etc/init.d/apps stop; " + DROP_STALE_SERIAL_LOCKS
            + "; /etc/init.d/apps start' </dev/null >/dev/null 2>&1 &";

    private RegisterBringup() {}

    /** {@code petrotest-pos-shim.sh start}, as {@code topaz}. Idempotent: a running shim is kept. */
    public static StepResult ensurePosShimRunning(Transport transport, Duration timeout) {
        String step = "ensure_pos_shim_running";
        CommandResult result = transport.run("su - " + SHIM_USER + " -c '" + POS_SHIM_SCRIPT + " start'", timeout);
        String detail = firstNonEmpty(result.stdout(), result.stderr()).strip();
        if (detail.isEmpty()) {
            detail = "exit " + result.exitStatus();
        }
        if (!result.ok()) {
            return new StepResult(step, StepStatus.FAILED, detail);
        }
        return new StepResult(step, detail.contains("already running") ? StepStatus.ALREADY : StepStatus.DONE, detail);
    }

    /** The checklist step that starts the shim. */
    public record EnsurePosShimRunning() implements ChecklistStep {

        @Override
        public String name() {
            return "ensure_pos_shim_running";
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            return ensurePosShimRunning(transport, timeout);
        }
    }

    /**
     * The checklist step that replaces a running shim which is not the payload's build. Bound to each register by
     * {@link #forTarget}: the shim's address is the register's host and shim port, and the stamp is the payload
     * being pushed. Unbound, it skips rather than guess an address.
     */
    public record EnsurePosShimCurrent(String host, int port, Path payloadRoot, Duration shimStart,
            Duration shimPoll, Duration settle, Duration poll) implements ChecklistStep {

        /** Unbound, with the payload directory and the defaults a rig run uses. */
        public EnsurePosShimCurrent() {
            this(null, PosShimClient.DEFAULT_SHIM_PORT, ProvisionPaths.payloadRoot(System.getenv()), SHIM_START,
                    SHIM_START_POLL, POS_APP_SETTLE, POS_APP_POLL);
        }

        public EnsurePosShimCurrent {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("shim port must be 1 to 65535");
            }
        }

        @Override
        public String name() {
            return "ensure_pos_shim_current";
        }

        @Override
        public ChecklistStep forTarget(TargetSpec spec, Path root) {
            return new EnsurePosShimCurrent(spec.host(), spec.shimPort(), root, shimStart, shimPoll, settle, poll);
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            if (host == null) {
                return new StepResult(name(), StepStatus.SKIPPED,
                        "not bound to a register: provision_rig binds it to each one (for_target)");
            }
            return ensurePosShimCurrent(transport, timeout, this);
        }
    }

    /**
     * Replaces the register's running shim when it is not the payload's build.
     *
     * <ol>
     *   <li>Reads the payload's stamp and the running shim's {@code /health} {@code build}. Equal: ALREADY.</li>
     *   <li>Restarts the shim as {@code topaz}, then waits for it to answer on the payload's build.</li>
     *   <li>Restarts the POS, then waits for {@code /health} to show it drivable, with state, not silent.</li>
     * </ol>
     *
     * No shim listening is SKIPPED ({@link EnsurePosShimRunning} starts the pushed build next). A failed restart, a
     * shim that does not come back on the payload's build, or a POS that does not settle is FAILED.
     */
    static StepResult ensurePosShimCurrent(Transport transport, Duration timeout, EnsurePosShimCurrent cfg) {
        String step = cfg.name();
        Path posDir = cfg.payloadRoot().resolve("pos");
        Optional<String> wantedStamp = ShimStamp.payloadStamp(posDir);
        if (wantedStamp.isEmpty()) {
            return new StepResult(step, StepStatus.SKIPPED, posDir.resolve(ShimStamp.STAMP_NAME) + " is absent (a payload"
                    + " built before 2026-10-16, or by a build that did not finish), so the running shim's build was not"
                    + " compared; rebuild with `python tools/build_pos_shim.py`");
        }
        String wanted = wantedStamp.get();
        PosShimClient shim = new PosShimClient(cfg.host(), cfg.port(), null, HEALTH_READ);
        Health first;
        try {
            first = shim.health();
        } catch (DeviceError e) {
            return new StepResult(step, StepStatus.SKIPPED, "no shim answers at " + shim.baseUri() + " (" + e.getMessage()
                    + "): none to replace; ensure_pos_shim_running starts the pushed build " + first12(wanted));
        }
        String running = first.build();
        if (wanted.equals(running)) {
            return new StepResult(step, StepStatus.ALREADY, "the running shim is the payload's build " + first12(wanted));
        }
        String was = running == null || running.isEmpty() ? "unstamped (built before 2026-10-16)"
                : "build " + first12(running);
        log.info("{}: the shim runs {} and the payload is build {}: restarting the shim, then the POS", cfg.host(), was,
                first12(wanted));

        CommandResult restart = transport.run("su - " + SHIM_USER + " -c '" + POS_SHIM_SCRIPT + " restart'", timeout);
        String said = firstNonEmpty(restart.stdout(), restart.stderr()).strip().replace("\n", " | ");
        if (!restart.ok()) {
            return new StepResult(step, StepStatus.FAILED, "petrotest-pos-shim.sh restart exited " + restart.exitStatus()
                    + ": " + said);
        }
        log.info("{}: {}", cfg.host(), said);

        HealthWatch watch = new HealthWatch(shim);
        Health health;
        try {
            health = Poll.until(() -> watch.check(h -> true), cfg.shimStart(), cfg.shimPoll(),
                    "the restarted shim at " + shim.baseUri());
        } catch (Bounded.TimeoutExpired e) {
            return new StepResult(step, StepStatus.FAILED, "the restarted shim did not answer GET /health in "
                    + Poll.seconds(cfg.shimStart()) + " s (" + watch.last() + "); the POS was not restarted"
                    + logTail(transport, timeout));
        }
        if (!wanted.equals(health.build())) {
            return new StepResult(step, StepStatus.FAILED, "the restarted shim reports build "
                    + (health.build() == null ? "none" : first12(health.build())) + ", not the payload's "
                    + first12(wanted) + ": the jar on the register is not the payload's (did this provision's push land?);"
                    + " the POS was not restarted");
        }

        CommandResult pos = transport.run(POS_APP_RESTART, timeout);
        if (!pos.ok()) {
            return new StepResult(step, StepStatus.FAILED, "the POS restart exited " + pos.exitStatus() + ": "
                    + firstNonEmpty(pos.stderr(), pos.stdout()).strip() + "; the shim is build " + first12(wanted)
                    + ", and its status surface stays silent until the POS restarts");
        }
        log.info("{}: POS restarting; waiting up to {} s for it to push its state to build {}", cfg.host(),
                Poll.seconds(cfg.settle()), first12(wanted));
        long started = System.nanoTime();
        try {
            health = Poll.until(() -> watch.check(posBack(wanted)), cfg.settle(), cfg.poll(),
                    "the POS on " + cfg.host() + " to push its state to the restarted shim");
        } catch (Bounded.TimeoutExpired e) {
            return new StepResult(step, StepStatus.FAILED, "the POS did not come back in " + Poll.seconds(cfg.settle())
                    + " s after its restart: GET /health last said " + watch.last());
        }
        double elapsed = (System.nanoTime() - started) / 1e9;
        String clients = String.join(", ", health.drivableClients());
        String detail = "the shim was " + was + ": restarted on build " + first12(wanted) + ", then the POS; " + clients
                + " drivable with state after " + String.format(Locale.ROOT, "%.0f", elapsed) + " s";
        log.info("{}: {}", cfg.host(), detail);
        return new StepResult(step, StepStatus.DONE, detail);
    }

    /** The README's step 6, on the payload's build: the POS drivable with state, and none drivable but silent. */
    static Predicate<Health> posBack(String wanted) {
        return h -> wanted.equals(h.build()) && !h.drivableClients().isEmpty() && h.statePresent()
                && h.drivableButSilent().isEmpty();
    }

    /** What one {@code GET /health} said, as far as it decides whether the POS is back. */
    static String healthSaid(Health h) {
        StringBuilder said = new StringBuilder("build ")
                .append(h.build() == null || h.build().isEmpty() ? "none" : first12(h.build()))
                .append(", drivable_clients ").append(pyList(h.drivableClients()))
                .append(", state_present ").append(h.statePresent() ? "True" : "False")
                .append(", drivable_but_silent ").append(pyList(h.drivableButSilent()));
        if (!h.readOnlyClients().isEmpty()) {
            said.append(", read_only_clients ").append(pyList(h.readOnlyClients()));
        }
        return said.toString();
    }

    /** The shim's log tail for a shim that did not come back; empty when it is unreadable. */
    private static String logTail(Transport transport, Duration timeout) {
        try {
            CommandResult result = transport.run("tail -n 5 " + POS_SHIM_LOG, timeout);
            String text = result.stdout().lines().map(String::strip).filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" | "));
            return result.ok() && !text.isEmpty() ? "; " + POS_SHIM_LOG + " ends: " + text : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String first12(String build) {
        return build.substring(0, Math.min(12, build.length()));
    }

    private static String firstNonEmpty(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    /** A Python list repr of strings, so the detail reads as the Python CAT's does. */
    private static String pyList(List<String> items) {
        return items.stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ", "[", "]"));
    }

    /** {@code GET /health}, polled. Remembers what the last read said, for a timeout's detail. */
    private static final class HealthWatch {
        private final PosShimClient shim;
        private String last = "nothing (not read yet)";

        HealthWatch(PosShimClient shim) {
            this.shim = shim;
        }

        String last() {
            return last;
        }

        /** The health when {@code ready} holds, else null. A shim that does not answer is not yet. */
        Health check(Predicate<Health> ready) {
            Health health;
            try {
                health = shim.health();
            } catch (DeviceError e) {
                last = "no answer: " + e.getMessage();
                return null;
            }
            last = healthSaid(health);
            return ready.test(health) ? health : null;
        }
    }
}
