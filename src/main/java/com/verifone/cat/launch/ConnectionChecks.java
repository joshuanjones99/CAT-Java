package com.verifone.cat.launch;

import java.util.Optional;
import java.util.function.Function;

import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.connections.ProbeResult;
import com.verifone.cat.connections.Target;
import com.verifone.cat.connections.XrayProbe;
import com.verifone.cat.provision.transport.SshLogin;
import com.verifone.cat.provision.transport.SshTarget;

/**
 * The two checks Connections makes that reach outside this PC: the SSH login to each provisioning
 * target, and the Xray sign-in. The launcher passes the real ones; a test passes fakes, so no test
 * signs in to a box or to Atlassian.
 *
 * @param ssh  signs in to one target; empty when the login works, else what went wrong
 * @param xray answers the Xray row
 */
record ConnectionChecks(
        Function<SshTarget, Optional<SshLogin.Problem>> ssh,
        Function<Target, ProbeResult> xray) {

    /** The real checks: the JSch login, and the Xray sign-in with the credentials file in the home folder. */
    static ConnectionChecks real() {
        return new ConnectionChecks(SshLogin::check, XrayProbe.forCredentialsFile(AtlassianCredentials.defaultPath()));
    }
}
