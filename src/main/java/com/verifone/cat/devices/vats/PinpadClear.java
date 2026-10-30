package com.verifone.cat.devices.vats;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;

import com.verifone.cat.commander.Say;
import com.verifone.cat.rig.Rig;

/**
 * {@code cat-java pinpad-clear --rig <rig>}'s work: {@link VatsPinpad#clearStuckCard} on a terminal-only session. A
 * port of the Python CAT's {@code petrotest.devices.vats.pinpad.pinpad_clear_cli}, with its lines.
 *
 * <p>True unless the rig has no {@code vats:} block: an unreachable terminal is reported and left to the steps that
 * need it, which fail on it with their own cause. Step 4 runs it before the payment links: each register login sends
 * the M400 {@code RemoveICCCard}, and a card left in from an earlier session holds that login, and the EPS with it,
 * until something takes the card out (Python HANDOFF.md section 1.146).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PinpadClear {

    /** Opens a terminal-only session: {@link VatsSession#openTerminal} in production. */
    public interface Opener extends BiFunction<Rig, Path, VatsSession> {}

    private PinpadClear() {}

    /** The production run: this controller's SDK, the results root's {@code vats} folder. */
    public static boolean run(Rig rig, Map<String, String> env, Say say) {
        VatsSession.Environment where = VatsSession.Environment.forThisMachine(env);
        return run(rig, VatsSession.workdir(env), (r, workdir) -> VatsSession.openTerminal(r, workdir, where), say);
    }

    /** {@link #run(Rig, Map, Say)} with the session opener a test supplies. */
    public static boolean run(Rig rig, Path workdir, Opener opener, Say say) {
        if (rig.vats() == null) {
            say.err("pinpad-clear: the rig has no vats: block");
            return false;
        }
        VatsSession session;
        try {
            session = opener.apply(rig, workdir);
        } catch (RuntimeException e) {
            say.out("pinpad-clear: WARN the terminal was not reachable: " + e.getMessage());
            return true;
        }
        try {
            Boolean present = session.pinpad().cardPresent();
            String done = session.pinpad().clearStuckCard();
            if (!done.isEmpty()) {
                say.out("pinpad-clear: " + done);
            } else {
                String state = present == null ? "unknown" : present ? "a card" : "no card";
                say.out("pinpad-clear: the terminal's reader holds " + state + "; nothing to do");
            }
        } finally {
            session.close();
        }
        return true;
    }
}
