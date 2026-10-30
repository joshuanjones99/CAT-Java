package com.verifone.cat.provision;

import java.util.Map;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.rig.Rig;

/**
 * {@code provision --force} as a call: the Python's {@code petrotest.provision.provision_force}, which preflight's
 * {@code eps_reachable} escalation (DESIGN.md §6.2) and recovery rung 5 (§6.3) both need as a call rather than a
 * child process, so the structured {@link ProvisionReport} reaches the run summary.
 *
 * <p>The options are {@code provision --rig}'s ({@link ProvisionOptions#defaults()}: the register checklists, so the
 * re-push restarts what the command's does), with {@code force} on.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ProvisionForce {

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionForce.class);

    private ProvisionForce() {}

    /** The options {@code provision --rig R --force} runs with, over {@code environ}. */
    public static ProvisionOptions options(Map<String, String> environ) {
        return ProvisionOptions.defaults()
                .force(true)
                .payloadRoot(ProvisionPaths.payloadRoot(environ))
                .stageSources(Stage.Sources.fromEnv(environ))
                .environ(environ)
                .toolVersion("cat-java");
    }

    /** {@code provision_force(rig)}: every target re-pushed and re-verified, regardless of its stamp. */
    public static ProvisionReport run(Rig rig, Map<String, String> environ) {
        return ProvisionRunner.provisionRig(rig, options(environ));
    }

    /**
     * The Python's {@code devices.context.provision_hook}: a call preflight's escalation makes, logged at WARNING
     * because it re-pushes artifacts to the rig over SSH. True when the report is ok.
     */
    public static BooleanSupplier hook(Rig rig, Map<String, String> environ) {
        return () -> {
            LOG.warn("preflight escalating to `provision --force` on {} (§6.2 eps_reachable, §6.3 rung 5)",
                    rig.name());
            return run(rig, environ).ok();
        };
    }
}
