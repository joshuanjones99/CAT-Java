package com.verifone.cat.upgrade;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.w3c.dom.Element;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.CgiReplies;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * The AU (classic) device upgrade path: {@code CICDScript.bat}'s {@code upgrade=AU}.
 *
 * <p>A port of {@code petrotest.upgrade.classic.upgrade_classic}, in its order:
 * <ol>
 *   <li>{@code bypass.mem.size.check} on the Commander ({@link RootAccess#setBypassMemSizeCheck});</li>
 *   <li>push the overlay to the Commander: {@code Auto_Upgrade.jar -q upgr <overlay> <host> 443 0}, bounded by
 *       {@link Settings#overlayPushTimeout} (the rig's SSH timeout would kill every real 1.2 GB push);</li>
 *   <li>settle ({@code :InitiateUpgrade}'s 190 s, the CGI surface does not answer before), then the CGI dance:
 *       {@code vupgradesummary} for the token, {@code cclosepdnow}, {@code cbeginupgrade}, then poll
 *       {@code vupgradesummary} until it reads {@code UPGRADE_COMPLETED_SUCCESSFULLY}; the OTP is read over the
 *       Commander's SSH ({@code cat /var/logins/tat});</li>
 *   <li>push both root-access packages to the Commander ({@code -q lic});</li>
 *   <li>push root access to every register ({@code NetLoaderClient.jar}), every register whatever the last did.</li>
 * </ol>
 * It stops at the first failed step of 1 to 4 and returns what it has, so a caller sees how far it got.
 *
 * <p>Not ported, as in the Python: {@code CICDScript.bat}'s five per-phase retry budgets around
 * {@code :CheckAUStatus}, its stale-{@code upgradeStatus} workaround and its {@code auwaittime} heuristic; one
 * poll budget covers the CGI steps. Deviation: the settle wait is said on the log as it starts (the Python waits
 * silently).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port), end to end. This closes the current day and flashes firmware.
 */
public final class ClassicUpgrade {

    static final String STEP_OVERLAY = "push_commander_overlay";
    static final String STEP_DANCE = "cgi_upgrade_dance";
    private static final int STDERR_HEAD = 500;

    /**
     * The waits, each the Python's constant; a test passes short ones.
     *
     * @param overlaySettle      {@code AU_OVERLAY_SETTLE_SECONDS}: after the overlay push, before the CGI dance
     * @param overlayPushTimeout {@code AU_OVERLAY_PUSH_TIMEOUT_SECONDS}: the overlay upload's own budget
     * @param pollTimeout        {@code AU_UPGRADE_POLL_TIMEOUT_SECONDS}: polling for the upgrade's completion
     * @param pollInterval       {@code AU_UPGRADE_POLL_INTERVAL_SECONDS}
     */
    public record Settings(Duration overlaySettle, Duration overlayPushTimeout, Duration pollTimeout,
            Duration pollInterval) {

        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(190), Duration.ofSeconds(1800), Duration.ofSeconds(2400),
                    Duration.ofSeconds(5));
        }
    }

    private final ClassicUpgradeTools tools;
    private final Function<SshTarget, Transport> transports;
    private final Jars jars;
    private final Function<String, UpgradeCgi> cgi;
    private final Settings settings;
    private final Consumer<String> log;

    /**
     * @param cgi the CGILink client for one Commander host
     * @param log progress notes (the settle wait); the results themselves are returned
     */
    public ClassicUpgrade(ClassicUpgradeTools tools, Function<SshTarget, Transport> transports, Jars jars,
            Function<String, UpgradeCgi> cgi, Settings settings, Consumer<String> log) {
        this.tools = tools;
        this.transports = transports;
        this.jars = jars;
        this.cgi = cgi;
        this.settings = settings;
        this.log = log == null ? line -> { } : log;
    }

    /** The real thing: JSch, child processes and CGILink, with the Python's waits. */
    public static ClassicUpgrade system(ClassicUpgradeTools tools, Consumer<String> log) {
        return new ClassicUpgrade(tools, JschTransport::new, Jars.SYSTEM,
                host -> UpgradeCgi.over(new PdkShim(host)), Settings.defaults(), log);
    }

    /**
     * Runs the AU path against {@code rig}. Never throws for an ordinary upgrade failure.
     *
     * @return one result per step, in order; stops after the first failed step of the Commander's
     */
    public List<StepResult> run(Rig rig, Map<String, String> environ) {
        Duration ssh = Timeouts.of(rig.timeouts()).ssh();
        List<Target> targets = Target.fromRig(rig, environ);
        Target commander = targets.get(0);
        List<StepResult> results = new ArrayList<>();
        try (Transport box = transports.apply(commander.ssh())) {
            results.add(RootAccess.setBypassMemSizeCheck(box, ssh));
            if (last(results).failed()) {
                return results;
            }
            results.add(pushCommanderOverlay(commander.host()));
            if (last(results).failed()) {
                return results;
            }
            // CICDScript.bat's :InitiateUpgrade waits here before the Commander's CGI surface will answer at all.
            log.accept("overlay pushed; waiting " + settings.overlaySettle().toSeconds()
                    + " s for the Commander's CGI surface");
            Bounded.pause(settings.overlaySettle());
            Supplier<String> readOtp = () -> box.run("cat /var/logins/tat", ssh).stdout().strip();
            try {
                results.add(runCgiUpgradeDance(cgi.apply(commander.host()), readOtp, settings.pollTimeout(),
                        settings.pollInterval()));
            } catch (DeviceError e) {
                results.add(StepResult.failed(STEP_DANCE, String.valueOf(e.getMessage())));
            }
        }
        if (last(results).failed()) {
            return results;
        }
        results.add(RootAccess.pushCommanderRootAccess(tools, jars, commander.host(), ssh));
        if (last(results).failed()) {
            return results;
        }
        for (Target register : targets.subList(1, targets.size())) {
            results.add(RootAccess.pushRegisterRootAccess(tools, jars, register.host(), ssh));
        }
        return results;
    }

    /** {@code Auto_Upgrade.jar -q upgr <overlay> <host> 443 0}: {@code CICDScript.bat}'s {@code :PushPackage}. */
    StepResult pushCommanderOverlay(String host) {
        List<String> argv = List.of(tools.javaBin(), "-jar", tools.autoUpgradeJar(), "-q", "upgr",
                tools.overlayPath(), host, "443", "0");
        Jars.Result result;
        try {
            result = jars.run(argv, settings.overlayPushTimeout());
        } catch (IOException e) {
            return StepResult.failed(STEP_OVERLAY, String.valueOf(e.getMessage()));
        }
        if (result.exitCode() != 0) {
            String stderr = result.stderr().strip();
            return StepResult.failed(STEP_OVERLAY, "Auto_Upgrade.jar exited " + result.exitCode() + ": "
                    + stderr.substring(0, Math.min(STDERR_HEAD, stderr.length())));
        }
        return StepResult.done(STEP_OVERLAY);
    }

    /**
     * {@code vupgradesummary} then {@code cclosepdnow} then {@code cbeginupgrade}, then polls {@code vupgradesummary}
     * for completion, bounded by {@code pollTimeout} (no fixed sleep where state can be polled).
     */
    static StepResult runCgiUpgradeDance(UpgradeCgi client, Supplier<String> readOtp, Duration pollTimeout,
            Duration pollInterval) {
        try {
            Element summary = client.upgradeSummary(readOtp);
            String token = CgiReplies.upgradeToken(summary);
            client.closeDay(readOtp);
            client.beginUpgrade(token, readOtp);
            Bounded.pollUntil(() -> CgiReplies.isUpgradeComplete(client.upgradeSummary(readOtp))
                    ? Optional.of(Boolean.TRUE) : Optional.empty(), pollTimeout, pollInterval,
                    "Commander AU upgrade completion (vupgradesummary)");
        } catch (DeviceError | Bounded.TimedOut e) {
            return StepResult.failed(STEP_DANCE, String.valueOf(e.getMessage()));
        }
        return StepResult.done(STEP_DANCE);
    }

    private static StepResult last(List<StepResult> results) {
        return results.get(results.size() - 1);
    }
}
