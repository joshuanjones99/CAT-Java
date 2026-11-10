package com.verifone.cat.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.PosShimBuild;
import com.verifone.cat.provision.ProvisionOptions;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.ProvisionRunner;
import com.verifone.cat.provision.Stage;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.setup.SetupItems;
import com.verifone.cat.sims.ToolsRoot;

/**
 * The POS page's Push shim to register, with no rig file (user, 2026-11-10): one register provisioned over SSH as
 * {@code root}, the browser override (the CDP relay the page's screen goes through) and the pos-shim, then the shim
 * started. The Commander is never reached. No Python equivalent: the Python provisions only a whole rig.
 *
 * <p>Two things are checked before anything is reached, and either missing fails the push with nothing sent: the SSH
 * key ({@code $PETROTEST_COMMANDER_SSH_KEY}, else the tools root's {@code development-no-pw.ppk}, the key Setup checks
 * and the Python's {@code DEFAULT_SSH_KEY_PATH}), and the built pos-shim payload ({@code payload/pos/}, which
 * {@code build-pos-shim} writes from the Python CAT's {@code bridge/pos-shim/} sources).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): {@code ShimPushTest} covers the checks and the target over a fake
 * provision only. In particular, that the registers accept this key for {@code root} is assumed from the rig files'
 * {@code ssh_user: root} and the Python's default key, not seen.
 */
public final class ShimPush {

    /** The user every rig file's {@code commander.ssh_user} names, which provision uses for the registers too. */
    public static final String SSH_USER = "root";

    /** What stands in for the rig's name in the stamp and the staged payload. */
    public static final String LABEL = "pos-page";

    /** The provision itself: {@link ProvisionRunner#provisionRegister} in production. */
    @FunctionalInterface
    public interface Provision {
        ProvisionReport run(TargetSpec spec, ProvisionOptions options);
    }

    /** The real provision. */
    public static final Provision REAL = (spec, options) -> ProvisionRunner.provisionRegister(spec, LABEL, options);

    private ShimPush() {}

    /** {@code $PETROTEST_COMMANDER_SSH_KEY}, else {@code <tools root>/development-no-pw.ppk}. */
    public static Path keyFile(Map<String, String> env) {
        String configured = env.get(SetupItems.SSH_KEY_ENV);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return ToolsRoot.resolve(env.get(ToolsRoot.ENV), null).resolve(SetupItems.SSH_KEY_FILE);
    }

    /** The payload files a push needs that are not in {@code <payload>/pos/}; empty when it is built. */
    public static List<String> missingPayload(Path payloadRoot) {
        Path pos = payloadRoot.resolve("pos");
        return PosShimBuild.OUTPUTS.stream().filter(name -> !Files.isRegularFile(pos.resolve(name))).toList();
    }

    /**
     * Pushes the pos-shim to {@code register}. Says each step on {@code say}, and every failure as its last error
     * line.
     *
     * @return whether the register is provisioned
     */
    public static boolean push(RegisterTarget register, Map<String, String> env, Provision provision, Say say) {
        Path key = keyFile(env);
        if (!Files.isRegularFile(key)) {
            say.err("no SSH key at " + key + ": run Setup, or set $" + SetupItems.SSH_KEY_ENV
                    + " to the register's key; nothing was pushed");
            return false;
        }
        Path payload = ProvisionPaths.payloadRoot(env);
        List<String> missing = missingPayload(payload);
        if (!missing.isEmpty()) {
            say.err("no pos-shim payload in " + payload.resolve("pos") + " (missing " + String.join(", ", missing)
                    + "): build it with build-pos-shim, which needs the Python CAT's bridge/pos-shim sources, or set "
                    + "$CAT_PAYLOAD_ROOT to a built payload; nothing was pushed");
            return false;
        }
        TargetSpec spec = new TargetSpec(TargetRole.REGISTER, register.id(), register.host(), SSH_USER,
                TargetSpec.DEFAULT_SSH_PORT, register.shimPort(), key.toString());
        say.out("provision " + register.describe() + " as " + SSH_USER + " with " + key
                + ": the browser override and the pos-shim; the Commander is not touched");
        ProvisionOptions options = ProvisionOptions.defaults()
                .payloadRoot(payload)
                .stageSources(Stage.Sources.fromEnv(env))
                .environ(env)
                .toolVersion("cat-java");
        ProvisionReport report;
        try {
            report = provision.run(spec, options);
        } catch (IllegalArgumentException | DeviceError e) {
            say.err("provision: " + e.getMessage());
            return false;
        }
        say.out(report.render());
        if (!report.ok()) {
            say.err("provision: " + register.describe() + " is not provisioned (its report is above)");
        }
        return report.ok();
    }
}
