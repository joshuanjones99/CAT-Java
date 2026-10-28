package com.verifone.cat.launch;

import java.util.Optional;
import java.util.function.Function;

import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.connections.ProbeResult;
import com.verifone.cat.connections.Target;
import com.verifone.cat.connections.XrayProbe;
import com.verifone.cat.devices.printer.SerialPorts;
import com.verifone.cat.provision.transport.SshLogin;
import com.verifone.cat.provision.transport.SshTarget;

/**
 * The checks Connections makes that reach outside this process: the SSH login to each provisioning
 * target, the Xray sign-in, and this PC's serial port listing (the Receipt Printer row). The launcher
 * passes the real ones; a test passes fakes, so no test signs in to a box or to Atlassian, or lists a
 * real COM port.
 *
 * @param ssh    signs in to one target; empty when the login works, else what went wrong
 * @param xray   answers the Xray row
 * @param serial lists the serial ports, for the Receipt Printer row
 */
record ConnectionChecks(
        Function<SshTarget, Optional<SshLogin.Problem>> ssh,
        Function<Target, ProbeResult> xray,
        SerialPorts serial) {

    /** The SSH and Xray checks, with this PC's own serial listing. */
    ConnectionChecks(Function<SshTarget, Optional<SshLogin.Problem>> ssh, Function<Target, ProbeResult> xray) {
        this(ssh, xray, SerialPorts.SYSTEM);
    }

    /** The real checks: the JSch login, the Xray sign-in with the credentials file in the home folder, jSerialComm. */
    static ConnectionChecks real() {
        return new ConnectionChecks(SshLogin::check, XrayProbe.forCredentialsFile(AtlassianCredentials.defaultPath()),
                SerialPorts.SYSTEM);
    }
}
