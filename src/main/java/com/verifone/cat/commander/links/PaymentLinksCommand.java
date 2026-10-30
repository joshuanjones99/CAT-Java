package com.verifone.cat.commander.links;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.JschTransport;

/**
 * {@code cat-java payment-links --rig R [--petroapps DIR]}: the simulator payment config imported and the EPS
 * restarted once, only if a link is down. The Python's {@code petrotest payment-links} ({@code cli.py},
 * {@code payment_links_cli}), over {@link PaymentLinks#run}.
 *
 * <p>{@code --petroapps} defaults to {@code $PETROAPPS_ROOT}, else the controller's checkout.
 *
 * <p>Exit codes: 0 the links are up, 1 not, 2 usage or a rig that does not load. UNVERIFIED AGAINST HARDWARE (Java
 * port).
 */
public final class PaymentLinksCommand {

    static final RigCommand.Spec SPEC = new RigCommand.Spec("payment-links",
            "usage: cat-java payment-links --rig <name|file.yaml> [--petroapps DIR]",
            Map.of("--petroapps", ""), Set.of(), Set.of(), Set.of(), List.of());

    private final RigCommand.Loader loader;
    private final RigCommand.Body body;

    /** The production command. */
    public PaymentLinksCommand() {
        this(RigCommand.Loader.RIGS, (rig, args, env, say) -> {
            CommanderAccess access = new CommanderAccess(rig, JschTransport::new, env);
            try (PdkShim shim = access.pdkShim()) {
                return RigCommand.exit(PaymentLinks.run(rig, petroapps(args.value("--petroapps"), env),
                        PaymentLinks.ConfigPort.of(shim), access::connectedTransport, Pauser.REAL, say));
            }
        });
    }

    /** A command over given seams; tests use this so no Commander is reached. */
    public PaymentLinksCommand(RigCommand.Loader loader, RigCommand.Body body) {
        this.loader = loader;
        this.body = body;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, body);
    }

    /** {@code --petroapps}, else {@code $PETROAPPS_ROOT}, else the controller's checkout. */
    public static Path petroapps(String given, Map<String, String> env) {
        if (given != null && !given.isBlank()) {
            return Path.of(given);
        }
        String configured = env.getOrDefault("PETROAPPS_ROOT", "");
        return Path.of(configured.isBlank() ? Collect.DEFAULT_PETROAPPS_ROOT : configured);
    }
}
