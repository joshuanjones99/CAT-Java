package com.verifone.cat.commander;

import java.io.PrintStream;
import java.time.LocalDate;
import java.util.Map;

import com.verifone.cat.provision.transport.JschTransport;

/**
 * {@code cat-java commander-prep --rig R}: {@code license.json}'s stop dates renewed and Gemreg's test marker created,
 * if needed. The Python's {@code petrotest commander-prep} ({@code cli.py}, {@code commander_prep}), over
 * {@link CommanderPrep#run}.
 *
 * <p>Exit codes: 0 done, 1 failed, 2 usage or a rig that does not load. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderPrepCommand {

    static final RigCommand.Spec SPEC = RigCommand.Spec.rigOnly("commander-prep");

    private final RigCommand.Loader loader;
    private final RigCommand.Body body;

    /** The production command: the rig's Commander over SSH, today's date. */
    public CommanderPrepCommand() {
        this(RigCommand.Loader.RIGS, (rig, args, env, say) -> RigCommand.exit(
                CommanderPrep.run(new CommanderAccess(rig, JschTransport::new, env), LocalDate.now(), say)));
    }

    /** A command over given seams; tests use this so no Commander is reached. */
    public CommanderPrepCommand(RigCommand.Loader loader, RigCommand.Body body) {
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
