package com.verifone.cat.tools;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Boxes rebooted over SSH and waited for: the Tools page's Reboot C18, Reboot Commander and Reboot Commander + C18. No
 * Python equivalent (its Manual page is empty); the reboot and the waits are the ones {@code coverage} already uses
 * after its own reboots (the Python's {@code new_install.wait_for_ssh} and {@code wait_for_cgilink},
 * {@link CoverageWaits}).
 *
 * <ol>
 *   <li>{@code cat /proc/uptime} first, on every box, so a box that cannot be reached fails at once instead of after a
 *       twenty-minute wait for a reboot that was never sent, and so a reboot of several boxes sends nothing unless
 *       all of them answer;</li>
 *   <li>{@code reboot} (a dropped session is the reboot itself, not an error), to every box before any wait;</li>
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
     * Reboots {@code box} and waits for it to come back: {@link #rebootTogether} with one box.
     *
     * @return true once it is back; a failure is the last line on {@code say}'s error stream
     */
    public static boolean reboot(TargetSpec box, Seams seams, Say say) {
        return rebootTogether(List.of(box), seams, say);
    }

    /**
     * Reboots every box in {@code boxes} at once and waits for all of them to come back: the Tools page's Reboot
     * Commander + C18 (user, 2026-11-10: "something people will do commonly").
     *
     * <p>Every box is checked first, and if any one does not answer, none is rebooted: half of the set rebooted is not
     * what the operator asked for. Then {@code reboot} goes to each in {@code boxes}' order before any wait starts, so
     * they reboot together. The waits run one after another, each for a boot that started after that box's own
     * {@code reboot} was sent, so a box already back passes its wait at once. A box that does not come back fails the
     * whole, but the others are still waited for, so the log says which came back.
     *
     * @return true once every box is back; a failure is the last line on {@code say}'s error stream
     */
    public static boolean rebootTogether(List<TargetSpec> boxes, Seams seams, Say say) {
        Map<TargetSpec, Transport> sessions = new LinkedHashMap<>();
        Map<TargetSpec, Long> sentAt = new LinkedHashMap<>();
        try {
            for (TargetSpec box : boxes) {
                if (!checked(box, seams, sessions, say)) {
                    return false;
                }
            }
            for (Map.Entry<TargetSpec, Transport> entry : sessions.entrySet()) {
                TargetSpec box = entry.getKey();
                say.out("rebooting " + name(box));
                sentAt.put(box, System.nanoTime());
                try {
                    entry.getValue().run("reboot", COMMAND_TIMEOUT);
                } catch (DeviceError e) {
                    // A reboot cuts the SSH session, so the command may report a failure that is the reboot itself.
                }
            }
        } finally {
            sessions.values().forEach(RigReboot::close);
        }
        say.out("reboot sent; waiting for SSH from the new boot (up to "
                + CoverageWaits.SSH_BACK_TIMEOUT.toMinutes() + " min)");
        boolean allBack = true;
        for (Map.Entry<TargetSpec, Long> entry : sentAt.entrySet()) {
            allBack &= back(entry.getKey(), entry.getValue(), seams, say);
        }
        return allBack;
    }

    /** Opens a session to {@code box} and checks it answers; false (and said) when it does not. */
    private static boolean checked(TargetSpec box, Seams seams, Map<TargetSpec, Transport> sessions, Say say) {
        String nothing = sessions.isEmpty() ? "; nothing was rebooted"
                : "; nothing was rebooted, not even the boxes that answered";
        Transport session;
        try {
            session = seams.transports().apply(box);
        } catch (RuntimeException e) {
            say.err("cannot reach " + name(box) + " over SSH: " + message(e) + nothing);
            return false;
        }
        sessions.put(box, session);
        try {
            CommandResult uptime = session.run("cat /proc/uptime", COMMAND_TIMEOUT);
            if (!uptime.ok()) {
                say.err(name(box) + " did not answer over SSH (exit " + uptime.exitStatus() + ")" + nothing);
                return false;
            }
        } catch (RuntimeException e) {
            say.err("cannot reach " + name(box) + " over SSH: " + message(e) + nothing);
            return false;
        }
        return true;
    }

    /** Waits for {@code box} to be back on SSH and, for the Commander, on CGILink. */
    private static boolean back(TargetSpec box, long sentAt, Seams seams, Say say) {
        String name = name(box);
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

    private static String name(TargetSpec box) {
        return box.name() + " (" + box.host() + ")";
    }

    private static void close(Transport session) {
        try {
            session.close();
        } catch (RuntimeException e) {
            // a session the reboot cut is not worth failing for
        }
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
