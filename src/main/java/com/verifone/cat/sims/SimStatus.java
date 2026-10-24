package com.verifone.cat.sims;

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
}
