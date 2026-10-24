package com.verifone.cat.commander.connect;

import java.util.Objects;

import com.verifone.cat.devices.commander.CgiReplies.CloudConnectStatus;
import com.verifone.cat.devices.commander.PdkShim;

/**
 * {@link ConnectClient} over CGILink: each call is the {@link PdkShim} call of the same name. The production client
 * {@code commander-connect} uses; the Python calls the same methods on its {@code PDKShim} directly.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PdkShimConnectClient implements ConnectClient {

    private final PdkShim shim;

    public PdkShimConnectClient(PdkShim shim) {
        this.shim = Objects.requireNonNull(shim, "shim");
    }

    @Override
    public void setCloudConnectProfile(String profile) {
        shim.setCloudConnectProfile(profile);
    }

    @Override
    public void cloudConnect(String email, String password) {
        shim.cloudConnect(email, password);
    }

    @Override
    public Status cloudConnectStatus() {
        CloudConnectStatus status = shim.cloudConnectStatus();
        return new Status(status.registered(), status.detail());
    }

    @Override
    public String cloudConnectivity() {
        return shim.cloudConnectivity();
    }

    @Override
    public void close() {
        shim.close();
    }
}
