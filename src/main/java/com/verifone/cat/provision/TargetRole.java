package com.verifone.cat.provision;

/**
 * The two kinds of box a rig has: the Commander, and the registers (the C18 hardware the Topaz POS runs on).
 *
 * <p>A port of {@code petrotest.provision.artifacts.TargetRole}. {@code BOTH} is not a role: an artifact that
 * goes to both names both roles.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public enum TargetRole {
    COMMANDER("commander"),
    REGISTER("register");

    private final String wire;

    TargetRole(String wire) {
        this.wire = wire;
    }

    /** The name this role carries in a manifest and a stamp file. Matches the Python CAT's values. */
    public String wire() {
        return wire;
    }
}
