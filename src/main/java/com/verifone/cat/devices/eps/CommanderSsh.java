package com.verifone.cat.devices.eps;

import java.util.Map;
import java.util.function.Supplier;

import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * An SSH transport opener to {@code commander.host} with the rig's key: the Python CAT's
 * {@code petrotest.devices.eps.commander_transport_factory}.
 *
 * <p>Nothing connects until {@link Opener#open()} is called and the transport is used.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderSsh {

    private CommanderSsh() {}

    /**
     * How to reach the Commander over SSH, or why this rig cannot.
     *
     * @param open       opens a fresh transport, or null when the rig has no key to log in with
     * @param missingKey why {@code open} is null; empty when it is not
     */
    public record Opener(Supplier<Transport> open, String missingKey) {

        public Opener {
            missingKey = missingKey == null ? "" : missingKey;
        }

        public boolean available() {
            return open != null;
        }
    }

    /**
     * A {@link JschTransport} opener to the Commander, or an unavailable one naming the environment
     * variable it wanted when {@code commander.ssh_key_path_env} does not resolve to a key.
     */
    public static Opener opener(Rig rig, Map<String, String> environ) {
        Rig.Commander commander = rig.commander();
        Map<String, String> env = environ == null ? Map.of() : environ;
        if (commander.resolveKeyFilename(env) == null) {
            String name = commander.sshKeyPathEnv();
            String why = name.isEmpty() ? "the rig file sets no commander.ssh_key_path_env" : "$" + name + " is not set";
            return new Opener(null, why);
        }
        SshTarget target = SshTarget.commander(rig, env);
        return new Opener(() -> new JschTransport(target), "");
    }
}
