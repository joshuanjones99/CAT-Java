package com.verifone.cat.commander;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.provision.transport.JschTransport;

/**
 * {@code cat-java commander-trust --rig R --endpoint host:port --alias A [--no-restart]}: a simulator's TLS
 * certificate imported into the Java {@code cacerts} PMC runs on, and PMC restarted if it changed. The Python's
 * {@code petrotest commander-trust} ({@code cli.py}, {@code commander_trust_cli}), over
 * {@link CommanderTrust#trustCli}.
 *
 * <p>Exit codes: 0 trusted, 1 failed, 2 usage or a rig that does not load. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderTrustCommand {

    static final RigCommand.Spec SPEC = new RigCommand.Spec("commander-trust",
            "usage: cat-java commander-trust --rig <name|file.yaml> --endpoint host:port --alias ALIAS [--no-restart]",
            Map.of("--endpoint", "", "--alias", ""), Set.of("--endpoint", "--alias"), Set.of("--no-restart"),
            Set.of(), List.of());

    private final RigCommand.Loader loader;
    private final RigCommand.Body body;

    /** The production command. */
    public CommanderTrustCommand() {
        this(RigCommand.Loader.RIGS, (rig, args, env, say) -> RigCommand.exit(CommanderTrust.trustCli(
                new CommanderAccess(rig, JschTransport::new, env), args.value("--endpoint"), args.value("--alias"),
                !args.has("--no-restart"), say)));
    }

    /** A command over given seams; tests use this so no Commander is reached. */
    public CommanderTrustCommand(RigCommand.Loader loader, RigCommand.Body body) {
        this.loader = loader;
        this.body = body;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, body);
    }
}
