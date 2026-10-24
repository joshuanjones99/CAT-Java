package com.verifone.cat.commander.connect;

/**
 * The Commander's Commander Connect calls, as {@link ConnectService} makes them. The production implementation is
 * {@link com.verifone.cat.devices.commander.PdkShim} (CGILink over HTTP); tests use a fake.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface ConnectClient extends AutoCloseable {

    /** {@code ucloudconnectprofile}: the profile name as the handler's enum takes it ({@code QAT}, ...). */
    void setCloudConnectProfile(String profile);

    /** {@code ccloudconnect}: starts onboarding; a clean return means started, not registered. */
    void cloudConnect(String email, String password);

    /** {@code vcloudconnectstatus}: whether the Commander is registered, and the detail for a failure. */
    Status cloudConnectStatus();

    /** {@code cloudConnectivityStatus}: "Online", "Offline", or "". */
    String cloudConnectivity();

    @Override
    void close();

    /**
     * Whether the Commander is registered, and the status detail (a failed state's code and text, or
     * {@code registered}).
     */
    record Status(boolean registered, String detail) {}
}
