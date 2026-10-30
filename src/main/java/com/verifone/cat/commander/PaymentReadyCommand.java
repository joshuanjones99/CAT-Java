package com.verifone.cat.commander;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.provision.transport.JschTransport;

/**
 * {@code cat-java payment-ready --rig R [--timeout SECONDS]}: waits until the Commander's EPS reports its payment
 * controller up, its primary FEP connected and every pinpad connected ({@code vpaymentdiagnostics}). The Python's
 * {@code petrotest payment-ready} ({@code cli.py}, {@code payment_ready_cli}), over {@link PaymentReady#run}.
 *
 * <p>{@code --timeout} is in seconds and may be fractional, as the Python's {@code type=float}; absent, or 0 (which
 * the Python's {@code args.timeout or READY_TIMEOUT} also reads as absent), it is {@link PaymentReady#READY_TIMEOUT}.
 *
 * <p>Exit codes: 0 ready, 1 not ready in time, 2 usage or a rig that does not load. UNVERIFIED AGAINST HARDWARE
 * (Java port).
 */
public final class PaymentReadyCommand {

    static final RigCommand.Spec SPEC = new RigCommand.Spec("payment-ready",
            "usage: cat-java payment-ready --rig <name|file.yaml> [--timeout SECONDS]",
            Map.of("--timeout", ""), Set.of(), Set.of(), Set.of(), List.of());

    /** Waits for the EPS over the rig's Commander. */
    @FunctionalInterface
    public interface Wait {
        boolean run(CommanderAccess access, Duration timeout, Say say);
    }

    private final RigCommand.Loader loader;
    private final Wait wait;

    /** The production command. */
    public PaymentReadyCommand() {
        this(RigCommand.Loader.RIGS, (access, timeout, say) -> PaymentReady.run(access::client, timeout,
                PaymentReady.READY_POLL, Pauser.REAL, say));
    }

    /** A command over given seams; tests use this so no Commander is reached. */
    public PaymentReadyCommand(RigCommand.Loader loader, Wait wait) {
        this.loader = loader;
        this.wait = wait;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, (rig, args, environ, say) -> {
            Duration timeout;
            try {
                timeout = timeout(args.value("--timeout"));
            } catch (NumberFormatException e) {
                err.println("payment-ready: argument --timeout: invalid float value: '" + args.value("--timeout")
                        + "'");
                return RigCommand.EXIT_USAGE;
            }
            return RigCommand.exit(wait.run(new CommanderAccess(rig, JschTransport::new, environ), timeout, say));
        });
    }

    /** {@code args.timeout or READY_TIMEOUT}: blank or zero is the default. */
    static Duration timeout(String value) {
        if (value == null || value.isBlank()) {
            return PaymentReady.READY_TIMEOUT;
        }
        double seconds = Double.parseDouble(value.strip());
        if (seconds == 0) {
            return PaymentReady.READY_TIMEOUT;
        }
        return Duration.ofNanos((long) (seconds * 1e9));
    }
}
