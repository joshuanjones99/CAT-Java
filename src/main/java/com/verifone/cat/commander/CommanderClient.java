package com.verifone.cat.commander;

import java.nio.file.Path;
import java.util.Map;

import com.verifone.cat.devices.commander.PdkShim;

/**
 * The part of the Commander's CGILink client the bring-up sends through: the base import, the init
 * commands and one view. {@link #of} is {@link PdkShim}; a test passes a recorder.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface CommanderClient extends AutoCloseable {

    /** {@code PdkShim.importConfigurationFolder}: every file of {@code folder}, a failed one skipped. */
    Map<String, String> importConfigurationFolder(Path folder);

    /** {@code PdkShim.controlFunction}: one init command, sent once; a fault raises {@code DeviceError}. */
    void controlFunction(String command);

    /** {@code PdkShim.sendCommand} with no payload: the reply's text. */
    String sendCommand(String command);

    @Override
    void close();

    /** The real client. */
    static CommanderClient of(PdkShim shim) {
        return new CommanderClient() {
            @Override
            public Map<String, String> importConfigurationFolder(Path folder) {
                return shim.importConfigurationFolder(folder);
            }

            @Override
            public void controlFunction(String command) {
                shim.controlFunction(command);
            }

            @Override
            public String sendCommand(String command) {
                return shim.sendCommand(command, "");
            }

            @Override
            public void close() {
                shim.close();
            }
        };
    }
}
