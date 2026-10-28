package com.verifone.cat.sims.base;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The Commander base a run's simulators will face: the Python CAT's {@code cat.sims.java.commander_base}.
 *
 * <p>The build the run installs, when its branch names a base ({@code master055.02}); otherwise the Commander's
 * installed {@code Base_Level} ({@code 055.02.09}), read from its {@code suiteinfo.dat} over SSH. A branch that names
 * no base ({@code Solutions}, {@code master}) falls through to the Commander's, as the Python does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the suiteinfo read is the caller's, and the tests pass a map.
 */
public final class CommanderBase {

    /**
     * The base, and where it came from.
     *
     * @param base the base text ({@code 055.02.09} or {@code master055.02}), or {@code ""} when unknown
     * @param why  where it came from, or why it is unknown, for the step's line
     */
    public record Base(String base, String why) {

        /** Whether a base was found. */
        public boolean known() {
            return !base.isEmpty();
        }
    }

    private CommanderBase() {
    }

    /**
     * @param branch    the Commander build branch the run installs, or {@code ""} when it installs none
     * @param suiteinfo reads the Commander's {@code suiteinfo.dat}; asked only when {@code branch} names no base
     */
    public static Base of(String branch, Supplier<Map<String, String>> suiteinfo) {
        String installs = branch == null ? "" : branch;
        if (JavaSets.baseOf(installs).isPresent()) {
            return new Base(installs, "the run installs " + installs);
        }
        String level;
        try {
            Map<String, String> fields = suiteinfo.get();
            level = fields.get("Base_Level");
            if (level == null) {
                throw new IllegalStateException("suiteinfo.dat has no Base_Level");
            }
        } catch (RuntimeException e) {
            return new Base("", "the Commander's base could not be read (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")");
        }
        if (JavaSets.baseOf(level).isEmpty()) {
            return new Base("", "the Commander's Base_Level '" + level + "' names no base");
        }
        return new Base(level, "the Commander is on Base_Level " + level);
    }
}
