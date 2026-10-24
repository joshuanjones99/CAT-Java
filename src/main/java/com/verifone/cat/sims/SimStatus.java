package com.verifone.cat.sims;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One status change for a simulator, handed to {@link Events#status}.
 *
 * <p>Plain data, as the Python CAT's {@code SimStatus} is: the supervisor does not know the
 * operator's UI, and the caller maps this onto whatever it shows.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param name the registry row's name, or {@link Supervisor#PROBE_ROW}
 * @param state where the simulator is
 * @param owner {@link Owner#CAT} when this supervisor spawned it, {@link Owner#EXTERNAL} when adopted
 * @param bind the {@code host:port} it was found on, or empty
 * @param detail one line for the operator
 */
public record SimStatus(String name, State state, Owner owner, String bind, String detail) {

    /** The Python CAT's status strings, as {@link #label}. */
    public enum State {
        STARTING("starting"),
        READY("ready"),
        ADOPTED("adopted"),
        STARTED("started"),
        NEEDS_ATTENTION("needs attention"),
        FAILED("failed");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Who started the process. D9: CAT stops only what it spawned. */
    public enum Owner {
        CAT("CAT"),
        EXTERNAL("external");

        private final String label;

        Owner(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public SimStatus {
        bind = bind == null ? "" : bind;
        detail = detail == null ? "" : detail;
    }

    /**
     * The warning for a clicked row this port does not start: one line that names the simulator and
     * says what the operator does by hand. This is the one place that wording lives. The step's
     * failure detail, the run's Overview and the Launch Simulators row all show this line.
     *
     * @param name the registry row's name
     * @param buttons the Swing buttons to press, in order
     * @return a {@code needs attention} status whose detail is that line
     */
    public static SimStatus awaitingButtons(String name, List<String> buttons) {
        String presses = buttons.isEmpty() ? "the buttons"
                : buttons.stream().map(button -> "\"" + button + "\"").collect(Collectors.joining(", then "));
        return new SimStatus(name, State.NEEDS_ATTENTION, Owner.CAT, "",
                name + " needs attention: press " + presses + " in its window (Access Bridge not ported).");
    }

    /**
     * The warning when the process list cannot be read: a simulator that is already up cannot be
     * recognised, so it may be started a second time. Reported on {@link Supervisor#PROBE_ROW}.
     *
     * @param error the probe's error text
     * @return a {@code needs attention} status whose detail is that line
     */
    public static SimStatus processListUnavailable(String error) {
        return new SimStatus(Supervisor.PROBE_ROW, State.NEEDS_ATTENTION, Owner.CAT, "",
                "could not list running processes: " + error + "; a simulator that is already up cannot be "
                        + "recognised and may be started a second time. Fix the process listing, then start again.");
    }
}
