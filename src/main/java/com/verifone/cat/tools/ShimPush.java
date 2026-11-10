package com.verifone.cat.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.Artifact;
import com.verifone.cat.provision.Artifacts;
import com.verifone.cat.provision.PosShimBuild;
import com.verifone.cat.provision.ProvisionOptions;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.ProvisionRunner;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.setup.SetupItems;
import com.verifone.cat.sims.ToolsRoot;

/**
 * The POS page's Push shim to register, with no rig file (user, 2026-11-10): the pos-shim built here when it is not
 * built yet, then pushed to one register over SSH as {@code root} and started. The Commander is never reached. No
 * Python equivalent: the Python provisions only a whole rig.
 *
 * <p>What it pushes is the shim's artifact alone ({@code pos_rmi_agent}: the shim, its launcher, the retained RMI agent,
 * the TopazERP enabler and {@code tokenRMI.prop}), with the pos-shim checklist. Not the browser override: its pushed
 * Chromium is a download only the Python CAT stages, so the POS page's screen relies on the override a rig run already
 * installed on the register.
 *
 * <p>The build ({@link PosShimBuild}) compiles CAT-Java's own {@code bridge/pos-shim} ({@link ProvisionPaths#bridgeRoot})
 * and the retained agent from petroapps ({@link Collect#petroappsRoot}) against the vendor jars cached in
 * {@code <tools root>/pos-shim-deps}. Before anything is reached the SSH key is checked ({@code
 * $PETROTEST_COMMANDER_SSH_KEY}, else the tools root's {@code development-no-pw.ppk}, the key Setup checks and the
 * Python's {@code DEFAULT_SSH_KEY_PATH}); a missing key or a failed build fails the push with nothing sent.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): {@code ShimPushTest} covers the checks, the build trigger and the target
 * over fakes only. That the registers accept this key for {@code root} is assumed from the rig files' {@code ssh_user:
 * root} and the Python's default key, not seen.
 */
public final class ShimPush {

    /** The user every rig file's {@code commander.ssh_user} names, which provision uses for the registers too. */
    public static final String SSH_USER = "root";

    /** What stands in for the rig's name in the stamp. */
    public static final String LABEL = "pos-page";

    /** The artifact a shim push sends: the pos-shim and the retained RMI agent. */
    public static final String SHIM_ARTIFACT = "pos_rmi_agent";

    /** The provision itself: {@link ProvisionRunner#provisionRegister} in production. */
    @FunctionalInterface
    public interface Provision {
        ProvisionReport run(TargetSpec spec, ProvisionOptions options);
    }

    /** The shim build: {@link PosShimBuild#build} with this JVM's javac in production. */
    @FunctionalInterface
    public interface Build {
        void run(PosShimBuild.Options options, Consumer<String> say);
    }

    /** The real provision. */
    public static final Provision REAL = (spec, options) -> ProvisionRunner.provisionRegister(spec, LABEL, options);

    /** The real build. */
    public static final Build REAL_BUILD = (options, say) -> PosShimBuild.build(options, PosShimBuild.javac(), say);

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

    /** The shim's artifact, from the provision table. */
    static Artifact shimArtifact() {
        return Artifacts.ARTIFACTS.stream().filter(a -> a.name().equals(SHIM_ARTIFACT)).findFirst().orElseThrow();
    }

    /** {@link #push(RegisterTarget, Map, Build, Provision, Say)} with the real build. */
    public static boolean push(RegisterTarget register, Map<String, String> env, Provision provision, Say say) {
        return push(register, env, REAL_BUILD, provision, say);
    }

    /**
     * Pushes the pos-shim to {@code register}, building it first when the payload lacks it. Says each step on
     * {@code say}, and every failure as its last error line.
     *
     * @return whether the register is provisioned
     */
    public static boolean push(RegisterTarget register, Map<String, String> env, Build build, Provision provision,
            Say say) {
        Path key = keyFile(env);
        if (!Files.isRegularFile(key)) {
            say.err("no SSH key at " + key + ": run Setup, or set $" + SetupItems.SSH_KEY_ENV
                    + " to the register's key; nothing was pushed");
            return false;
        }
        Path payload = ProvisionPaths.payloadRoot(env);
        if (!missingPayload(payload).isEmpty() && !build(env, payload, build, say)) {
            return false;
        }
        TargetSpec spec = new TargetSpec(TargetRole.REGISTER, register.id(), register.host(), SSH_USER,
                TargetSpec.DEFAULT_SSH_PORT, register.shimPort(), key.toString());
        say.out("push the pos-shim to " + register.describe() + " as " + SSH_USER + " with " + key
                + "; the Commander is not touched");
        // The shim's artifact and checklist only: nothing staged (the build made the payload), no browser override.
        ProvisionOptions options = ProvisionOptions.defaults()
                .payloadRoot(payload)
                .artifacts(List.of(shimArtifact()))
                .registerChecklist(List.of())
                .stage(false)
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

    /** Builds {@code <payload>/pos} from CAT-Java's {@code bridge/pos-shim}; false, said, when it cannot. */
    private static boolean build(Map<String, String> env, Path payload, Build build, Say say) {
        Path bridge = ProvisionPaths.bridgeRoot(env);
        Path petroapps = Collect.petroappsRoot(env);
        Path deps = PosShimBuild.defaultDeps(env);
        say.out("build the pos-shim into " + payload.resolve("pos") + " from " + bridge.resolve("bridge/pos-shim")
                + ", petroapps " + petroapps + " and the jars in " + deps);
        try {
            build.run(new PosShimBuild.Options(bridge, petroapps, deps, payload.resolve("pos"), false), say::out);
        } catch (PosShimBuild.BuildError | IllegalStateException e) {
            say.err("could not build the pos-shim: " + e.getMessage() + "; nothing was pushed");
            return false;
        }
        List<String> missing = missingPayload(payload);
        if (!missing.isEmpty()) {
            say.err("the pos-shim build left out " + String.join(", ", missing) + " in " + payload.resolve("pos")
                    + "; nothing was pushed");
            return false;
        }
        return true;
    }
}
