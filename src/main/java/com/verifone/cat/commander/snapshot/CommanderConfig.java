package com.verifone.cat.commander.snapshot;

import java.util.List;

import com.verifone.cat.devices.commander.CgiReplies.CgiFunction;
import com.verifone.cat.devices.commander.PdkShim;

/**
 * What the snapshot needs of the Commander's CGILink client: the Python's {@code CommanderConfig} protocol.
 * {@link #of} is {@link PdkShim}; a test passes the same client against a loopback fake.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface CommanderConfig {

    /** {@code PdkShim.functionList}: a fresh {@code validate}'s funcList. */
    List<CgiFunction> functionList();

    /**
     * {@code PdkShim.sendCommand}: PATS's attempt loop (login, OTP and busy retries); a fault comes back in the
     * text.
     */
    String sendCommand(String command, String payload);

    /** {@code PdkShim.updateConfiguration}: {@code u<command>} with {@code payload}; a fault raises. */
    String updateConfiguration(String command, String payload);

    /** The real client. */
    static CommanderConfig of(PdkShim shim) {
        return new CommanderConfig() {
            @Override
            public List<CgiFunction> functionList() {
                return shim.functionList();
            }

            @Override
            public String sendCommand(String command, String payload) {
                return shim.sendCommand(command, payload);
            }

            @Override
            public String updateConfiguration(String command, String payload) {
                return shim.updateConfiguration(command, payload);
            }
        };
    }
}
