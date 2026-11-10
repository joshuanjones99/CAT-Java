package com.verifone.cat.web;

import java.nio.file.Path;

import com.verifone.cat.prefs.WebSettings;
import com.verifone.cat.tools.RegisterTarget;

/**
 * Which register the POS page checks and shows, as it stands now (user, 2026-11-10): the rig's, when CAT was started
 * with a rig that loads; else the Settings page's register address, read on each request so a saved address applies
 * without a restart. The page needs nothing else of a rig file.
 */
@FunctionalInterface
interface PosRegister {

    /** The register, or none with why (shown on the page). */
    record Resolved(RegisterTarget target, String why) {

        static Resolved none(String why) {
            return new Resolved(null, why);
        }
    }

    Resolved current();

    /** None ever: CAT shows no register, and why. */
    static PosRegister none(String why) {
        Resolved none = Resolved.none(why);
        return () -> none;
    }

    /**
     * The rig's register when there is one, else the address saved in {@code settingsFile}.
     *
     * @param fromRig  the loaded rig's register, or null
     * @param rigWhy   why there is no rig register (the rig's load error), shown when no address is saved either
     * @param shimPort the pos-shim port ({@code --shim-port})
     */
    static PosRegister of(RegisterTarget fromRig, String rigWhy, Path settingsFile, int shimPort) {
        if (fromRig != null) {
            Resolved rig = new Resolved(fromRig, "");
            return () -> rig;
        }
        return () -> {
            String host = WebSettings.registerHost(settingsFile);
            if (host.isEmpty()) {
                return Resolved.none((rigWhy == null || rigWhy.isBlank() ? "" : rigWhy + " ")
                        + "Set the register's address on the Settings page.");
            }
            try {
                return new Resolved(new RegisterTarget(null, host, shimPort, RegisterTarget.DEFAULT_CDP_PORT), "");
            } catch (IllegalArgumentException e) {
                return Resolved.none("The register address on the Settings page (" + host + ") is not usable: "
                        + e.getMessage() + ".");
            }
        };
    }
}
