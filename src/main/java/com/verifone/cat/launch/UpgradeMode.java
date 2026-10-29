package com.verifone.cat.launch;

import java.util.Locale;

/**
 * How, or whether, a run gets the rig onto a build before its tests.
 *
 * <p>Mirrors the Python CAT's {@code petrotest.upgrade.models.UpgradeMode}, with the same wire
 * values. It stays in {@code launch} although the {@code upgrade} and {@code build} packages are ported (wave 16):
 * the web pages, the run service and the launch all name it here, and those packages use it from here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no upgrade has been run by this port.
 */
public enum UpgradeMode {

    /** Do nothing. The default, and the only mode that needs no external tool or CGI access. */
    SKIP("skip"),
    /** {@code CICDScript.bat}'s {@code upgrade=AU}: {@code upgrade.ClassicUpgrade}. */
    CLASSIC("classic"),
    /** {@code CICDScript.bat}'s {@code upgrade=VHQ}: not implemented, it fails saying so ({@code upgrade.VhqUpgrade}). */
    VHQ("vhq"),
    /** {@code CICDScript.bat}'s {@code upgrade=PSI}: a fresh install. */
    NEW_INSTALL("new_install");

    private final String wire;

    UpgradeMode(String wire) {
        this.wire = wire;
    }

    /** The lowercase value written to the run's events and to the command line. */
    public String wire() {
        return wire;
    }

    /** Parses a command-line or saved value; blank is {@link #SKIP}. */
    public static UpgradeMode of(String text) {
        if (text == null || text.isBlank()) {
            return SKIP;
        }
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        for (UpgradeMode mode : values()) {
            if (mode.wire.equals(wanted)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown upgrade mode '" + text + "': expected skip, classic, vhq or new_install");
    }
}
