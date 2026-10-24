package com.verifone.cat.closeday;

import java.nio.file.Path;

import com.verifone.cat.devices.commander.PdkShim;

/**
 * {@link CommanderAccess.CgiClient} over the production {@link PdkShim}. Each call is the shim's own.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class PdkCgiClient implements CommanderAccess.CgiClient {

    private final PdkShim shim;

    PdkCgiClient(PdkShim shim) {
        this.shim = shim;
    }

    @Override
    public String viewConfiguration(String command) {
        return shim.viewConfiguration(command);
    }

    @Override
    public void closeDay() {
        // The shim's own OTP reader answers an OTPRequired fault, so none is passed here.
        shim.closeDay(null);
    }

    @Override
    public void importConfigurationFolder(Path folder) {
        shim.importConfigurationFolder(folder);
    }

    @Override
    public void refreshConfiguration() {
        shim.refreshConfiguration();
    }

    @Override
    public void close() {
        shim.close();
    }
}
