package com.verifone.cat.commander;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.provision.transport.JschTransport;

/**
 * {@code cat-java commander-init --rig R [--no-forecourt] [--no-snapshot]}: the base configuration imported, then
 * PATS's init commands. The Python's {@code petrotest commander-init} ({@code cli.py}, {@code commander_init}), over
 * {@link CommanderInit#run}.
 *
 * <p>{@code --no-forecourt} sends only {@code crefreshcfg} (Petro Simulator Suite is not running);
 * {@code --no-snapshot} skips the Commander snapshot restore before the base import, as {@code $CAT_NO_SNAPSHOT=1}
 * does ({@link CommanderInit#snapshotDisabled}, read inside the init).
 *
 * <p>Exit codes: 0 done, 1 failed, 2 usage or a rig that does not load. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderInitCommand {

    static final RigCommand.Spec SPEC = new RigCommand.Spec("commander-init",
            "usage: cat-java commander-init --rig <name|file.yaml> [--no-forecourt] [--no-snapshot]",
            Map.of(), Set.of(), Set.of("--no-forecourt", "--no-snapshot"), Set.of(), List.of());

    private final RigCommand.Loader loader;
    private final RigCommand.Body body;

    /** The production command: the rig's Commander over SSH and CGILink. */
    public CommanderInitCommand() {
        this(RigCommand.Loader.RIGS, (rig, args, env, say) -> RigCommand.exit(
                CommanderInit.forRig(new CommanderAccess(rig, JschTransport::new, env), say)
                        .run(null, !args.has("--no-forecourt"), !args.has("--no-snapshot"))));
    }

    /** A command over given seams; tests use this so no Commander is reached. */
    public CommanderInitCommand(RigCommand.Loader loader, RigCommand.Body body) {
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
