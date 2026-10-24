package com.verifone.cat.commander;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * The rig's Commander, as every caller reaches it: SSH as its {@code ssh_user} with the rig's key, and
 * CGILink with an OTP read over that SSH. A port of {@code petrotest.devices.commander}.
 *
 * <p>The SSH budget is the rig file's own {@code timeouts:} block ({@link #sshTimeout(Rig)}), the fix the
 * Python module was extracted for: before it, every caller read the defaults.
 *
 * <p>The OTP reader is lazy. Most CGILink calls never fault {@code CGIPortal.OTPRequired}, so a reader
 * that connected eagerly would make each of them depend on SSH credentials it does not need
 * ({@code docs/commander-config-surface.md} section 4: the file is root-only, with no HTTP route).
 *
 * <p>The transport factory is a seam: a test passes in-memory transports, so nothing signs in to a box.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderAccess {

    /** Where the Commander keeps its current OTP. */
    public static final String OTP_COMMAND = "cat /var/logins/tat";

    private final Rig rig;
    private final Function<SshTarget, Transport> transports;
    private final Map<String, String> environ;

    /** The production access: real SSH, the process environment for the key's variable. */
    public CommanderAccess(Rig rig) {
        this(rig, JschTransport::new, System.getenv());
    }

    /**
     * @param transports builds one (unconnected) transport per box; tests pass in-memory ones
     * @param environ    where {@code commander.ssh_key_path_env} is looked up
     */
    public CommanderAccess(Rig rig, Function<SshTarget, Transport> transports, Map<String, String> environ) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.environ = environ == null ? Map.of() : Map.copyOf(environ);
    }

    public Rig rig() {
        return rig;
    }

    /** The environment the key's variable is read from. */
    public Map<String, String> environ() {
        return environ;
    }

    /** The SSH budget from the rig's own {@code timeouts:} block (the default when it has none). */
    public static Duration sshTimeout(Rig rig) {
        return Timeouts.of(rig.timeouts()).ssh();
    }

    /** {@link #sshTimeout(Rig)} for this rig. */
    public Duration sshTimeout() {
        return sshTimeout(rig);
    }

    /** The Commander's SSH target. */
    public SshTarget target() {
        return SshTarget.commander(rig, environ);
    }

    /** A transport to the Commander; it connects on first use. */
    public Transport transport() {
        return transports.apply(target());
    }

    /** {@link #transport}, connected within {@link #sshTimeout()} when it is an SSH one. */
    public Transport connectedTransport() {
        Transport transport = transport();
        if (transport instanceof JschTransport jsch) {
            try {
                jsch.connect(sshTimeout());
            } catch (RuntimeException e) {
                transport.close();
                throw e;
            }
        }
        return transport;
    }

    /** A transport to {@code register}, with the Commander's account and key (as provisioning does). */
    public Transport registerTransport(Rig.Register register) {
        return transports.apply(SshTarget.register(rig, register, environ));
    }

    /** The register targets of {@link #registerTransport}, for naming them in lines. */
    public SshTarget registerTarget(Rig.Register register) {
        return SshTarget.register(rig, register, environ);
    }

    /**
     * The lazy OTP source for CGILink: {@code cat /var/logins/tat} over SSH, one connection per call,
     * opened only when called.
     */
    public Supplier<String> otpReader() {
        return () -> {
            Transport transport = transport();
            try {
                return transport.run(OTP_COMMAND, sshTimeout()).stdout().strip();
            } finally {
                transport.close();
            }
        };
    }

    /** A CGILink client with PATS's read budget and {@link #otpReader}. */
    public PdkShim pdkShim() {
        return new PdkShim(rig.commander().host(), PdkShim.COMMANDER_READ_TIMEOUT, otpReader());
    }

    /** {@link #pdkShim} as the bring-up's {@link CommanderClient}. */
    public CommanderClient client() {
        return CommanderClient.of(pdkShim());
    }
}
