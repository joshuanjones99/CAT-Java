package com.verifone.cat.tools;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;

import com.verifone.cat.commander.Say;
import com.verifone.cat.coverage.CoverageWaits;
import com.verifone.cat.coverage.DeviceOps;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;

/**
 * One box rebooted over SSH and waited for: the Tools page's Reboot C18 and Reboot Commander. No Python equivalent (its
 * Manual page is empty); the reboot and the waits are the ones {@code coverage} already uses after its own reboots
 * (the Python's {@code new_install.wait_for_ssh} and {@code wait_for_cgilink}, {@link CoverageWaits}).
 *
 * <ol>
 *   <li>{@code cat /proc/uptime} first, so a box that cannot be reached fails at once instead of after a twenty-minute
 *       wait for a reboot that was never sent;</li>
 *   <li>{@code reboot} (a dropped session is the reboot itself, not an error);</li>
 *   <li>SSH from a boot that started after the reboot was sent ({@link CoverageWaits#SSH_BACK_TIMEOUT});</li>
 *   <li>the Commander only: CGILink answering without {@code AAA service unavailable}
 *       ({@link CoverageWaits#CGILINK_BACK_TIMEOUT}).</li>
 * </ol>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no box has been rebooted from the Tools page.
 */
public final class RigReboot {

    /** The budget of the reachability check and of the {@code reboot} command itself. */
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /** Where a reboot reaches the world: the SSH sessions and the two waits. Tests pass fakes. */
    public record Seams(Function<TargetSpec, Transport> transports, DeviceOps.SshWait sshBack,
            Predicate<String> cgilinkBack) {

        /** JSch to the box, and {@link CoverageWaits}' waits with the Python's budgets. */
        public static Seams system() {
            Function<TargetSpec, Transport> transports = spec -> new JschTransport(spec.toSshTarget());
            return new Seams(transports, CoverageWaits.ssh(transports), CoverageWaits.cgilink());
        }
    }

    private RigReboot() {}

    /**
     * Reboots {@code box} and waits for it to come back.
     *
     * @return true once it is back; a failure is the last line on {@code say}'s error stream
     */
    public static boolean reboot(TargetSpec box, Seams seams, Say say) {
        String name = box.name() + " (" + box.host() + ")";
        long sentAt;
        Transport session;
        try {
            session = seams.transports().apply(box);
        } catch (RuntimeException e) {
            say.err("cannot reach " + name + " over SSH: " + message(e) + "; nothing was rebooted");
            return false;
        }
        try {
            CommandResult uptime = session.run("cat /proc/uptime", COMMAND_TIMEOUT);
            if (!uptime.ok()) {
                say.err(name + " did not answer over SSH (exit " + uptime.exitStatus() + "); nothing was rebooted");
                return false;
            }
            say.out("rebooting " + name);
            sentAt = System.nanoTime();
            try {
                session.run("reboot", COMMAND_TIMEOUT);
            } catch (DeviceError e) {
                // A reboot cuts the SSH session, so the command may report a failure that is the reboot itself.
            }
        } catch (RuntimeException e) {
            say.err("cannot reach " + name + " over SSH: " + message(e) + "; nothing was rebooted");
            return false;
        } finally {
            try {
                session.close();
            } catch (RuntimeException e) {
                // a session the reboot cut is not worth failing for
            }
        }
        say.out("reboot sent; waiting for SSH from the new boot (up to "
                + CoverageWaits.SSH_BACK_TIMEOUT.toMinutes() + " min)");
        if (!seams.sshBack().await(box, sentAt)) {
            say.err(name + " did not come back on SSH within " + CoverageWaits.SSH_BACK_TIMEOUT.toMinutes() + " min");
            return false;
        }
        say.out(name + " is back on SSH");
        if (box.role() == TargetRole.COMMANDER) {
            say.out("waiting for CGILink (up to " + CoverageWaits.CGILINK_BACK_TIMEOUT.toMinutes() + " min)");
            if (!seams.cgilinkBack().test(box.host())) {
                say.err(name + ": CGILink did not answer within " + CoverageWaits.CGILINK_BACK_TIMEOUT.toMinutes()
                        + " min (AAA still starting, or the application tier is down)");
                return false;
            }
            say.out(name + ": CGILink answers");
        }
        return true;
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
