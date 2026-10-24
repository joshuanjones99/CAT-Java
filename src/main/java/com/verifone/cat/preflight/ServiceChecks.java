package com.verifone.cat.preflight;

import java.time.Duration;

/**
 * The service-reachability check, DESIGN.md 6.2 row 8. Ported from the Python CAT's
 * {@code petrotest/preflight/checks/services.py}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ServiceChecks {

    private ServiceChecks() {}

    /**
     * Row 8: the Commander's EPS is connected to its payment host.
     *
     * <p><b>Not</b> a TCP connect to the Commander, which is what the Python first did and why it failed on a
     * healthy rig (its HANDOFF.md 1.73): {@code commander.eps_port} is the Buypass UMF port, the listener on it
     * is the payment-host simulator on the controller, and the Commander's EPS dials out to it. The probe
     * ({@code devices.eps.CommanderLinkEpsProbe}) asks the Commander whether it holds that socket ESTABLISHED.
     * A probe that cannot look raises, and the runner records ERROR rather than a false FAIL.
     *
     * <p>The repair is the escalation to {@code provision --force} (DESIGN.md 6.1, recovery rung 5). This port
     * has no reprovision hook on the context, so the repair fails naming the remedy. The one-restart fix
     * ({@code commander_links.ensure_payment_links}) restarts the Commander's payment controller and is
     * deliberately not something preflight does on its own.
     */
    public static final class EpsReachable extends BaseCheck {

        public EpsReachable() {
            super("eps_reachable", Duration.ofSeconds(120));
        }

        @Override
        public Result verify(RigContext rig) {
            if (rig.eps().reachable(rig.defaultWait())) {
                return Result.passed(name());
            }
            return Result.failed(name(), "the Commander holds no ESTABLISHED connection for " + rig.eps().describe()
                    + " — is the payment-host simulator (BUYPASS Host) running on the controller, and does the "
                    + "Commander's FEP config point at it?");
        }

        @Override
        public Result repair(RigContext rig) {
            return Result.failed(name(), "EPS is not connected and no reprovision hook is wired — escalate to "
                    + "`petrotest provision --rig X --force` (DESIGN.md 6.1)");
        }
    }
}
