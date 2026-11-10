package com.verifone.cat;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

import com.verifone.cat.closeday.CloseDayCommand;
import com.verifone.cat.cloudtunnel.CloudTunnelCommand;
import com.verifone.cat.commander.connect.CommanderConnectCommand;
import com.verifone.cat.commander.snapshot.CommanderSnapshotCommand;
import com.verifone.cat.launch.LaunchCommand;
import com.verifone.cat.preflight.PreflightCommand;
import com.verifone.cat.provision.ProvisionCommand;
import com.verifone.cat.upgrade.PsiInitCommand;
import com.verifone.cat.commander.emv.EmvKeysCommand;
import com.verifone.cat.xray.XraySidecar;

/**
 * {@code java -jar cat-java.jar --rig lab [--register topaz101] [--port 8090]} starts the web UI;
 * {@code cat-java web --rig lab ...} is the same. Any word in {@link #COMMANDS} ({@code preflight},
 * {@code launch}, {@code provision}, {@code xray}, {@code close-day}, {@code commander-snapshot},
 * {@code commander-connect}, {@code cloud-tunnel}, {@code psi-init}, {@code emv-keys}, {@code coverage},
 * {@code xray-attach}) runs that command instead
 * and exits with its code.
 *
 * <p>The web UI's own flags, and what it does on start, are {@link com.verifone.cat.web.WebServe}'s.
 */
public final class Main {

    /** One command word: the class that runs it, and how. */
    record Command(Class<?> type, ToIntFunction<String[]> run) {
    }

    /**
     * Every command word. Each Python {@code petrotest <command>} that is ported is a
     * {@code <Thing>Command.run(String[])} here. Step 4's commands (wave 16): {@code commander-prep},
     * {@code commander-init}, {@code payment-links}, {@code register-app}, {@code payment-ready},
     * {@code commander-trust}, {@code secondary-fep}, {@code csite-reset}, {@code cloud-snapshot},
     * {@code build-pos-shim} (the Python's {@code tools/build_pos_shim.py}) and {@code install-resume} (new).
     * {@code upgrade}, {@code build}, {@code builds}, {@code artifactory-login}, {@code render-payload}: the upgrade slice's (wave 16).
     * {@code setup}: the webshell slice's (wave 16), the Python's {@code cat-term setup}.
     */
    static final Map<String, Command> COMMANDS = commands();

    private static Map<String, Command> commands() {
        Map<String, Command> table = new LinkedHashMap<>();
        table.put("preflight", new Command(PreflightCommand.class, argv -> new PreflightCommand().run(argv)));
        table.put("launch", new Command(LaunchCommand.class, argv -> new LaunchCommand().run(argv)));
        table.put("provision", new Command(ProvisionCommand.class, argv -> new ProvisionCommand().run(argv)));
        table.put("xray", new Command(XraySidecar.class, XraySidecar::run));
        table.put("close-day", new Command(CloseDayCommand.class, argv -> new CloseDayCommand().run(argv)));
        table.put("commander-snapshot",
                new Command(CommanderSnapshotCommand.class, argv -> new CommanderSnapshotCommand().run(argv)));
        table.put("commander-connect",
                new Command(CommanderConnectCommand.class, argv -> new CommanderConnectCommand().run(argv)));
        table.put("cloud-tunnel", new Command(CloudTunnelCommand.class, argv -> new CloudTunnelCommand().run(argv)));
        table.put("psi-init", new Command(PsiInitCommand.class, argv -> new PsiInitCommand().run(argv)));
        table.put("emv-keys", new Command(EmvKeysCommand.class, argv -> new EmvKeysCommand().run(argv)));
        table.put("setup", new Command(com.verifone.cat.setup.SetupCommand.class, argv -> new com.verifone.cat.setup.SetupCommand().run(argv)));
        table.put("commander-prep", new Command(com.verifone.cat.commander.CommanderPrepCommand.class, argv -> new com.verifone.cat.commander.CommanderPrepCommand().run(argv)));
        table.put("commander-init", new Command(com.verifone.cat.commander.CommanderInitCommand.class, argv -> new com.verifone.cat.commander.CommanderInitCommand().run(argv)));
        table.put("payment-links", new Command(com.verifone.cat.commander.links.PaymentLinksCommand.class, argv -> new com.verifone.cat.commander.links.PaymentLinksCommand().run(argv)));
        table.put("register-app", new Command(com.verifone.cat.commander.registerapp.RegisterAppCommand.class, argv -> new com.verifone.cat.commander.registerapp.RegisterAppCommand().run(argv)));
        table.put("payment-ready", new Command(com.verifone.cat.commander.PaymentReadyCommand.class, argv -> new com.verifone.cat.commander.PaymentReadyCommand().run(argv)));
        table.put("commander-trust", new Command(com.verifone.cat.commander.CommanderTrustCommand.class, argv -> new com.verifone.cat.commander.CommanderTrustCommand().run(argv)));
        table.put("secondary-fep", new Command(com.verifone.cat.commander.SecondaryFepCli.class, argv -> new com.verifone.cat.commander.SecondaryFepCli().run(argv)));
        table.put("csite-reset", new Command(com.verifone.cat.commander.connect.CSiteReset.class, argv -> new com.verifone.cat.commander.connect.CSiteReset().run(argv)));
        table.put("cloud-snapshot", new Command(com.verifone.cat.commander.connect.CloudSnapshotCommand.class, argv -> new com.verifone.cat.commander.connect.CloudSnapshotCommand().run(argv)));
        table.put("build-pos-shim", new Command(com.verifone.cat.provision.PosShimBuildCommand.class, argv -> new com.verifone.cat.provision.PosShimBuildCommand().run(argv)));
        table.put("install-resume", new Command(com.verifone.cat.launch.InstallResume.class, argv -> new com.verifone.cat.launch.InstallResume().run(argv)));
        table.put("coverage", new Command(com.verifone.cat.coverage.CoverageCommand.class, com.verifone.cat.coverage.CoverageCommand::run));
        table.put("xray-attach", new Command(com.verifone.cat.xray.Attach.class, com.verifone.cat.xray.Attach::run));
        table.put("upgrade", new Command(com.verifone.cat.upgrade.UpgradeCommand.class, argv -> new com.verifone.cat.upgrade.UpgradeCommand().run(argv)));
        table.put("build", new Command(com.verifone.cat.build.BuildCommand.class, argv -> new com.verifone.cat.build.BuildCommand().run(argv)));
        table.put("builds", new Command(com.verifone.cat.build.BuildsCommand.class, argv -> new com.verifone.cat.build.BuildsCommand().run(argv)));
        table.put("artifactory-login", new Command(com.verifone.cat.build.ArtifactoryLogin.class, argv -> new com.verifone.cat.build.ArtifactoryLogin().run(argv)));
        table.put("render-payload", new Command(ProvisionCommand.class, argv -> new ProvisionCommand().run(java.util.stream.Stream.concat(java.util.stream.Stream.of("render-payload"), Arrays.stream(argv)).toArray(String[]::new))));
        table.put("fetch-sims", new Command(com.verifone.cat.sims.FetchSimsCommand.class, argv -> new com.verifone.cat.sims.FetchSimsCommand().run(argv)));
        table.put("record", new Command(com.verifone.cat.recorder.RecordCommand.class, argv -> new com.verifone.cat.recorder.RecordCommand().run(argv)));
        table.put("generate", new Command(com.verifone.cat.recorder.GenerateCommand.class, argv -> new com.verifone.cat.recorder.GenerateCommand().run(argv)));
        table.put("steps", new Command(com.verifone.cat.authoring.StepsCommand.class, argv -> new com.verifone.cat.authoring.StepsCommand().run(argv)));
        table.put("authoring", new Command(com.verifone.cat.authoring.AuthoringCommand.class, argv -> new com.verifone.cat.authoring.AuthoringCommand().run(argv)));
        table.put("publish", new Command(com.verifone.cat.publish.PublishCommand.class, argv -> new com.verifone.cat.publish.PublishCommand().run(argv)));
        table.put("pinpad-clear", new Command(com.verifone.cat.devices.vats.PinpadClearCommand.class, argv -> new com.verifone.cat.devices.vats.PinpadClearCommand().run(argv)));
        return Collections.unmodifiableMap(table);
    }

    private Main() {}

    /**
     * A word in {@link #COMMANDS} runs that command and exits with its code. {@code cat-java web ...}, or the
     * same flags with no word, starts the web UI, which keeps running after {@code main} returns.
     */
    public static void main(String[] argv) {
        Command command = argv.length > 0 ? COMMANDS.get(argv[0]) : null;
        if (command != null) {
            System.exit(command.run().applyAsInt(Arrays.copyOfRange(argv, 1, argv.length)));
            return;
        }
        // The Python's `catt web` serve(): flags, browser, auto-close, web log, setup on start (wave 16, WebServe).
        System.exit(com.verifone.cat.web.WebServe.run(
                argv.length > 0 && "web".equals(argv[0]) ? Arrays.copyOfRange(argv, 1, argv.length) : argv));
    }
}
