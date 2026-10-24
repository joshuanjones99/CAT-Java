package com.verifone.cat.pats.runner.handlers.configuration;

/**
 * FEPSIM v2 serving Buypass this run (the Python's {@code buypass_fepsim()}): an {@code UPDATE_CONFIGURATION}
 * for BUYPASS goes to its rule text over its own interface, not to {@code PayHostSim.prop}. Its restore puts
 * back the text it held.
 */
public interface BuypassV2 {

    /** The rule text FEPSIM v2 holds now, or {@code null} when it holds none. */
    String configText();

    /** Replaces the rule text. */
    void applyConfig(String text);
}
