package com.verifone.cat.commander.registerapp;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * {@code cat-java register-app --rig R}: every register still in the SCO app switched back to the cashier POS app
 * (the App Selector, as PATS's {@code APP_SELECTOR ..._POS} does). The Python's {@code petrotest register-app}
 * ({@code cli.py}, {@code register_app_cli}), over {@link RegisterApp#run}.
 *
 * <p>Exit codes: 0 every register in the POS app, 1 not, 2 usage or a rig that does not load. UNVERIFIED AGAINST
 * HARDWARE (Java port).
 */
public final class RegisterAppCommand {

    static final RigCommand.Spec SPEC = RigCommand.Spec.rigOnly("register-app");

    /** The SSH connect budget for each register. */
    static final Duration CONNECT = Duration.ofSeconds(20);

    private final RigCommand.Loader loader;
    private final RigCommand.Body body;

    /** The production command. */
    public RegisterAppCommand() {
        this(RigCommand.Loader.RIGS, (rig, args, env, say) -> RigCommand.exit(
                forRig(rig, new CommanderAccess(rig, JschTransport::new, env), say)));
    }

    /** A command over given seams; tests use this so no register is reached. */
    public RegisterAppCommand(RigCommand.Loader loader, RigCommand.Body body) {
        this.loader = loader;
        this.body = body;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, body);
    }

    /** {@code register_app_cli(rig)}: each register over SSH (connected here) and its POS page over CDP. */
    public static boolean forRig(Rig rig, CommanderAccess access, Say say) {
        Function<Rig.Register, Transport> transports = register -> {
            Transport transport = access.registerTransport(register);
            if (transport instanceof JschTransport jsch) {
                try {
                    jsch.connect(CONNECT);
                } catch (RuntimeException e) {
                    transport.close();
                    throw e;
                }
            }
            return transport;
        };
        return RegisterApp.run(rig, transports, new CdpPosSeams(rig), Pauser.REAL, RegisterApp.SWITCH_TIMEOUT, say);
    }
}
