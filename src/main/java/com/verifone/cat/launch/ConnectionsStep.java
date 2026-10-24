package com.verifone.cat.launch;

/**
 * Step 2, Connections: probe the run's devices, then re-probe for the rest of the run.
 *
 * <p>Unavailable. The probes themselves are ported ({@code connections}), but the Python step also
 * signs in to each provisioning target over SSH ({@code petrotest.provision.transport}) and probes
 * the rig's own endpoints, which {@code connections.Endpoints} does not take from the rig model yet.
 */
final class ConnectionsStep extends UnportedStep {

    ConnectionsStep() {
        super("src/cat/launch/steps/connections.py",
                "the SSH login check (petrotest.provision.transport) and the rig's probe targets "
                        + "(connections.Endpoints takes plain hosts, not the rig model)");
    }

    @Override
    public String name() {
        return LaunchStepNames.CONNECTIONS;
    }
}
