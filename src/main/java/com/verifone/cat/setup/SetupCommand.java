package com.verifone.cat.setup;

import java.io.PrintStream;
import java.util.List;
import java.util.function.Supplier;

import com.verifone.cat.desktop.DesktopHost;
import com.verifone.cat.desktop.Shortcuts;

/**
 * {@code cat-java setup}: the Python CAT's {@code cat-term setup}. Checks this PC, fixes what CAT can fix (no
 * confirmation), and prints what is left; exits 0 when every required item passes, 1 when one does not, 2 on a bad
 * argument. Sign-ins are entered on the web UI's Setup page, never here.
 *
 * <p>Then, as the Python's {@code setup.ps1} step 7b does, it adds the CAT-Java shortcut to the Desktop and the
 * Start menu ({@link Shortcuts}) unless {@code --skip-shortcuts}. A shortcut that cannot be added is reported and
 * does not change the exit code, as the Python's {@code Write-Warn}. The shortcut is a step of this command, not a
 * setup item: the web UI runs the setup audit's repairs on every start, and a shortcut is the operator's choice to
 * make once (Settings has the same button).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the repairs change this PC (a user environment variable through
 * {@code setx}, the results folder, {@code jabswitch -enable}, the shortcut).
 */
public final class SetupCommand {

    static final String SKIP_SHORTCUTS = "--skip-shortcuts";

    static final String USAGE = "usage: cat-java setup [" + SKIP_SHORTCUTS + "]\n"
            + "  checks this PC, fixes what CAT can fix, and lists what needs you (sign in on the Setup page);\n"
            + "  then adds the CAT-Java shortcut to the Desktop and Start menu (" + SKIP_SHORTCUTS + " leaves them)";

    private final Supplier<SetupAudit> audits;
    private final PrintStream out;
    private final Supplier<Shortcuts.Outcome> shortcuts;

    public SetupCommand() {
        this(() -> SetupAudit.standard(SetupEnv.system()), System.out, () -> DesktopHost.system().addShortcuts());
    }

    /** A test's command: no shortcut step. */
    SetupCommand(Supplier<SetupAudit> audits, PrintStream out) {
        this(audits, out, null);
    }

    /** @param shortcuts adds the shortcuts; null for none (a test) */
    SetupCommand(Supplier<SetupAudit> audits, PrintStream out, Supplier<Shortcuts.Outcome> shortcuts) {
        this.audits = audits;
        this.out = out;
        this.shortcuts = shortcuts;
    }

    public int run(String[] argv) {
        List<String> args = List.of(argv);
        if (args.contains("-h") || args.contains("--help")) {
            out.println(USAGE);
            return 0;
        }
        boolean skipShortcuts = false;
        for (String arg : args) {
            if (arg.equals(SKIP_SHORTCUTS)) {
                skipShortcuts = true;
            } else {
                out.println("Unknown option " + arg + ".");
                out.println(USAGE);
                return 2;
            }
        }
        boolean ok = new SetupPrompt(audits.get(), out::println).run();
        if (shortcuts != null) {
            if (skipShortcuts) {
                out.println("Skipping the CAT-Java shortcuts (" + SKIP_SHORTCUTS + ")");
            } else {
                out.println("Adding the CAT-Java shortcut to the Desktop and the Start menu");
                Shortcuts.Outcome outcome = shortcuts.get();
                outcome.lines().forEach(out::println);
            }
        }
        return ok ? 0 : 1;
    }
}
