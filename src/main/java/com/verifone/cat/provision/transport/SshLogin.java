package com.verifone.cat.provision.transport;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * The SSH login check the Connections step runs for each provisioning target: sign in, run {@code true},
 * sign out. A port of {@code ssh_login_problem} in {@code cat/launch/steps/connections.py}.
 *
 * <p>Blocking. Call it from a worker thread. The Connections step itself is not wired here; it is the
 * caller's job to run {@link #check} for every target and end the run on a refused login.
 */
public final class SshLogin {

    /** Seconds for one login check (connect, authenticate, {@code true}), as the Python constant. */
    public static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * A failed check: the words for the operator, and whether the login itself was refused. A refused login
     * ends the run; a box that cannot be reached is reported and the run may go on.
     */
    public record Problem(String message, boolean refused) {}

    private SshLogin() {
    }

    /** The login check against the real JSch transport. Empty when the login works. */
    public static Optional<Problem> check(SshTarget target) {
        return check(target, JschTransport::new);
    }

    /** The seam: {@code factory} builds the transport, so tests never sign in to a box. */
    static Optional<Problem> check(SshTarget target, Function<SshTarget, Transport> factory) {
        Transport transport = factory.apply(target);
        try {
            transport.run("true", LOGIN_TIMEOUT);
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of(failure(target, e));
        } finally {
            transport.close();
        }
    }

    /** Python's {@code _ssh_failure}: the cause decides the words. */
    static Problem failure(SshTarget target, RuntimeException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (JschTransport.isAuthRefusal(cause)) {
            String key = target.keyFilename() == null
                    ? "none: the rig's commander.ssh_key_path_env is unset"
                    : target.keyFilename();
            return new Problem(target.name() + " (" + target.host() + ") refused CAT's SSH key (" + key + "): it is "
                    + "not authorized there, or the no-pw patch is not applied", true);
        }
        return new Problem("SSH to " + target.name() + " (" + target.host() + ") failed: " + cause.getMessage(), false);
    }
}
