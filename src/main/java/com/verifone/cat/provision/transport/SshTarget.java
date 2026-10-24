package com.verifone.cat.provision.transport;

import java.util.Map;

import com.verifone.cat.rig.Rig;

/**
 * One box {@code provision} signs in to: where it is, as which account, with which key file.
 *
 * <p>A port of {@code petrotest.provision.targets.TargetSpec} and its {@code targets_from_rig}. The
 * rig file names the key's environment variable ({@code commander.ssh_key_path_env}); the key's path is
 * read from the environment at resolution time and is never stored in the rig file or logged as a value.
 *
 * <p>Registers take the Commander's account and key, as in Python. The Java rig schema has no
 * {@code ssh_user} or key on a register, so there is no per-register override to port; the port is 22.
 *
 * @param keyFilename the converted OpenSSH key's path, or null when the env variable is blank or unset
 */
public record SshTarget(String name, String host, int port, String user, String keyFilename) {

    public SshTarget {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("ssh target needs a name");
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("ssh target " + name + " needs a host");
        }
        if (user == null || user.isEmpty()) {
            throw new IllegalArgumentException("ssh target " + name + " needs a user");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("ssh port must be 1 to 65535, got " + port);
        }
    }

    /** The Commander, as {@code targets_from_rig} lists it first. */
    public static SshTarget commander(Rig rig, Map<String, String> environ) {
        Rig.Commander c = rig.commander();
        return new SshTarget("commander", c.host(), DEFAULT_PORT, c.sshUser(), c.resolveKeyFilename(environ));
    }

    /** A register: the Commander's account and key, on port 22. */
    public static SshTarget register(Rig rig, Rig.Register register, Map<String, String> environ) {
        Rig.Commander c = rig.commander();
        return new SshTarget(register.id(), register.host(), DEFAULT_PORT, c.sshUser(),
                c.resolveKeyFilename(environ));
    }

    /** The default SSH port, as {@code ssh_port} defaults to in Python. */
    public static final int DEFAULT_PORT = 22;
}
