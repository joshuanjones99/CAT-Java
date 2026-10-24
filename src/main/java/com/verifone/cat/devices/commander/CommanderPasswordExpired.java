package com.verifone.cat.devices.commander;

import com.verifone.cat.devices.DeviceError;

/**
 * The Commander user's password has expired, so the Commander has stripped its roles.
 *
 * <p>Found on the 55.02 lab Commander, 2026-10-09: {@code validate} still logs in and answers
 * {@code <passwd expire="1" days="-17">}, but the session carries 14 functions
 * ({@code changepasswd} among them) and every import, {@code crefreshcfg} and
 * {@code cclosepdnow} is refused, the Commander logging "Remote access not allowed" /
 * "Permission denied for the function". Those refusals come back as HTTP 200, so without this
 * check a run spent five minutes on commander-init and ended on an unexplained
 * {@code CGIPortal.InternalError}. The Python CAT's {@code config_shim.CommanderPasswordExpired}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderPasswordExpired extends DeviceError {

    private final int days;

    /**
     * @param host the Commander, from the rig file
     * @param days the {@code days} attribute of {@code <passwd>}; zero or below means expired
     */
    public CommanderPasswordExpired(String host, int days) {
        super("the Commander " + host + "'s '" + PdkShim.COMMANDER_USER + "' password expired " + (-days)
                + " day(s) ago, so it has stripped that user's roles and refuses every configuration command. "
                + "Recover it (rig owner, 2026-10-09; docs/commander-maint-commands.md): as maint, "
                + "`resetpw manager`, log in with the temporary password it prints and change it "
                + "to the one CAT uses; if the Commander refuses that as a recent password, repeat, "
                + "up to five more times");
        this.days = days;
    }

    /** The {@code days} the Commander reported. */
    public int days() {
        return days;
    }
}
