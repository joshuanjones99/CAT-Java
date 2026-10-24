package com.verifone.cat.pos.fake;

import java.io.PrintStream;
import java.util.List;

/**
 * The run-end banner a run that used the fake POS prints. Text ported verbatim from the Python
 * {@code pytest_terminal_summary} in {@code petrotest.pos.plugin}.
 *
 * <p>This project's rule is that a mock-backed pass is never presented as hardware verification;
 * this banner is the enforcement mechanism for that rule.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class FakePosBanner {

    /** The title on the separator line above the box. */
    public static final String TITLE = "FAKE POS";

    /** The box, line by line, as the Python prints it. */
    public static final List<String> LINES = List.of(
            "█".repeat(80),
            "█" + " ".repeat(78) + "█",
            "█  WARNING: FAKE POS WAS USED — THIS RUN IS EVIDENCE ABOUT TEST CODE,   █",
            "█  NOT ABOUT HARDWARE. A test passing here does NOT mean a rig passes.  █",
            "█" + " ".repeat(78) + "█",
            "█".repeat(80));

    private FakePosBanner() {}

    /** Prints the banner: the title, the box, then a closing rule. */
    public static void print(PrintStream out) {
        out.println("==== " + TITLE + " ====");
        for (String line : LINES) {
            out.println(line);
        }
        out.println("=".repeat(80));
    }
}
