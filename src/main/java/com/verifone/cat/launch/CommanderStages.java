package com.verifone.cat.launch;

import java.time.LocalDate;
import java.util.Map;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.commander.CommanderPrep;
import com.verifone.cat.commander.PaymentReady;
import com.verifone.cat.commander.Say;
import com.verifone.cat.provision.ProvisionOptions;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.ProvisionRunner;
import com.verifone.cat.provision.Stage;
import com.verifone.cat.rig.Rig;

/**
 * The ported stages of step 4, each the Python step's {@code petrotest <command>} child run in-process: a
 * stage writes its lines to a {@link Say} and returns whether it passed. {@link #real} reaches the rig; a
 * test passes recorders, so no step test signs in to a box.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
interface CommanderStages {

    /** {@code petrotest provision}: the pos-shim payload pushed, verified and stamped on every target. */
    boolean provision(Rig rig, Say say);

    /** {@code petrotest commander-prep}: the license dates and the Gemreg marker. */
    boolean commanderPrep(Rig rig, Say say);

    /** {@code petrotest commander-init} ({@code --no-forecourt} when {@code forecourt} is false). */
    boolean commanderInit(Rig rig, boolean forecourt, Say say);

    /** {@code petrotest payment-ready}: the EPS ready for card sales. */
    boolean paymentReady(Rig rig, Say say);

    /**
     * What step 4 runs between the inits and the payment-ready wait that CAT-Java does not have, or ""
     * when nothing is missing. While it is not "", the step ends unavailable there.
     */
    default String missingAfterInit() {
        return CommanderConfigurationStep.MISSING_AFTER_INIT;
    }

    /** The real stages, against the rig's devices, with {@code environ} for keys and paths. */
    static CommanderStages real(Map<String, String> environ) {
        return new CommanderStages() {
            @Override
            public boolean provision(Rig rig, Say say) {
                ProvisionOptions options = ProvisionOptions.defaults()
                        .payloadRoot(ProvisionPaths.payloadRoot(environ))
                        .stageSources(Stage.Sources.fromEnv(environ))
                        .environ(environ)
                        .toolVersion("cat-java");
                ProvisionReport report;
                try {
                    report = ProvisionRunner.provisionRig(rig, options);
                } catch (IllegalArgumentException e) {
                    say.err("provision: " + e.getMessage());
                    return false;
                }
                say.out(report.render());
                if (!report.ok()) {
                    say.err("provision: a target is not provisioned (its report is above)");
                }
                return report.ok();
            }

            @Override
            public boolean commanderPrep(Rig rig, Say say) {
                return CommanderPrep.run(access(rig), LocalDate.now(), say);
            }

            @Override
            public boolean commanderInit(Rig rig, boolean forecourt, Say say) {
                return CommanderInit.forRig(access(rig), say).run(null, forecourt, true);
            }

            @Override
            public boolean paymentReady(Rig rig, Say say) {
                return PaymentReady.run(access(rig), say);
            }

            private CommanderAccess access(Rig rig) {
                return new CommanderAccess(rig, com.verifone.cat.provision.transport.JschTransport::new, environ);
            }
        };
    }
}
