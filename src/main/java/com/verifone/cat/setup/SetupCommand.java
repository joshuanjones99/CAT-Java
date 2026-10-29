package com.verifone.cat.setup;

import java.io.PrintStream;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code cat-java setup}: the Python CAT's {@code cat-term setup}. Checks this PC, fixes what CAT can fix (no
 * confirmation), and prints what is left; exits 0 when every required item passes, 1 when one does not, 2 on a bad
 * argument. Sign-ins are entered on the web UI's Setup page, never here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the repairs change this PC (a user environment variable through
 * {@code setx}, the results folder, {@code jabswitch -enable}).
 */
public final class SetupCommand {

    static final String USAGE = "usage: cat-java setup\n"
            + "  checks this PC, fixes what CAT can fix, and lists what needs you (sign in on the Setup page)";

    private final Supplier<SetupAudit> audits;
    private final PrintStream out;

    public SetupCommand() {
        this(() -> SetupAudit.standard(SetupEnv.system()), System.out);
    }

    SetupCommand(Supplier<SetupAudit> audits, PrintStream out) {
        this.audits = audits;
        this.out = out;
    }

    public int run(String[] argv) {
        List<String> args = List.of(argv);
        if (args.contains("-h") || args.contains("--help")) {
            out.println(USAGE);
            return 0;
        }
        if (!args.isEmpty()) {
            out.println("Unknown option " + args.get(0) + ".");
            out.println(USAGE);
            return 2;
        }
        return new SetupPrompt(audits.get(), out::println).run() ? 0 : 1;
    }
}
