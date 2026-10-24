package com.verifone.cat.closeday;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * How the day close reaches the Commander: an SSH session for its log and its coresvcs, and a
 * CGILink client for {@code cclosepdnow} and the configuration reads. A port of the Python's
 * {@code commander_transport} and {@code commander_client}.
 *
 * <p>Both are made fresh per call, as the Python makes them: a caller closes what it gets. A test
 * supplies its own; {@link #forRig} is the production one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface CommanderAccess {

    /** The Commander's address, for messages only. */
    String host();

    /** A new SSH session to the Commander as its rig user. It connects on first use. */
    Transport ssh();

    /** A new CGILink client for the Commander, with the SSH OTP reader. */
    CgiClient cgi();

    /**
     * The CGILink calls the day close makes. Narrower than {@link PdkShim} so a test can stand in for
     * it without a socket; the production one is {@link #forRig}'s.
     */
    interface CgiClient extends AutoCloseable {

        /** {@code cmd=<command>} as a view: the reply text. */
        String viewConfiguration(String command);

        /** {@code cmd=cclosepdnow}. Throws a {@code DeviceError} on a fault. */
        void closeDay();

        /** Imports every file in {@code folder} as a configuration. */
        void importConfigurationFolder(Path folder);

        /** {@code crefreshcfg}. */
        void refreshConfiguration();

        @Override
        void close();
    }

    /** The SSH OTP read's budget, as the Python's {@code commander_ssh_timeout} default. */
    Duration SSH_TIMEOUT = Duration.ofSeconds(30);

    /** The production access to the rig's Commander, with the rig's SSH key from {@code env}. */
    static CommanderAccess forRig(Rig rig, Map<String, String> env) {
        Rig.Commander commander = rig.commander();
        return new CommanderAccess() {
            @Override
            public String host() {
                return commander.host();
            }

            @Override
            public Transport ssh() {
                return new JschTransport(SshTarget.commander(rig, env));
            }

            @Override
            public CgiClient cgi() {
                PdkShim shim = new PdkShim(commander.host(), PdkShim.COMMANDER_READ_TIMEOUT, this::readOtp);
                return new PdkCgiClient(shim);
            }

            /** {@code cat /var/logins/tat} over SSH, opened only when called (the Python's OTP reader). */
            private String readOtp() {
                try (Transport transport = ssh()) {
                    return transport.run("cat /var/logins/tat", SSH_TIMEOUT).stdout().strip();
                }
            }
        };
    }
}
