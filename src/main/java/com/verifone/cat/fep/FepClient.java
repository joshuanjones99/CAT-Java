package com.verifone.cat.fep;

import java.nio.file.Path;
import java.util.Map;

import com.verifone.cat.devices.commander.CgiReplies.CloudConnectStatus;
import com.verifone.cat.devices.commander.CgiReplies.FepDetail;
import com.verifone.cat.devices.commander.PdkShim;

/**
 * The part of the Commander's CGILink client the secondary-FEP step sends through: the FEP table read, the FEP
 * configs read and posted back, one petroapps import, the config refresh and Commander Connect's status. A port of
 * the Python {@code PDKShim} calls {@code petrotest.secondary_fep} makes. {@link #of} is {@link PdkShim}; a test
 * passes a recorder.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface FepClient {

    /** {@code vfepdetails}: each FEP the Commander's table holds, by name. */
    Map<String, FepDetail> fepDetails();

    /** One configuration read ({@code vfepcfg}-style), by command, e.g. {@code fepcfg&fep=PropFleet}. */
    String viewConfiguration(String command);

    /** One configuration write ({@code ufepcfg}-style) with its payload. */
    void updateConfiguration(String command, String payload);

    /** Imports every file of {@code folder}; the result is keyed by command ({@code "u" + name minus ".xml"}). */
    Map<String, String> importConfigurationFolder(Path folder);

    /** {@code crefreshcfg}: the Commander re-reads the FEP configuration it was given. */
    void refreshConfiguration();

    /** {@code vcloudconnectstatus}: whether the Commander is onboarded to Commander Connect. */
    CloudConnectStatus cloudConnectStatus();

    /** The real client: CGILink through {@link PdkShim}. */
    static FepClient of(PdkShim shim) {
        return new FepClient() {
            @Override
            public Map<String, FepDetail> fepDetails() {
                return shim.fepDetails();
            }

            @Override
            public String viewConfiguration(String command) {
                return shim.viewConfiguration(command);
            }

            @Override
            public void updateConfiguration(String command, String payload) {
                shim.updateConfiguration(command, payload);
            }

            @Override
            public Map<String, String> importConfigurationFolder(Path folder) {
                return shim.importConfigurationFolder(folder);
            }

            @Override
            public void refreshConfiguration() {
                shim.refreshConfiguration();
            }

            @Override
            public CloudConnectStatus cloudConnectStatus() {
                return shim.cloudConnectStatus();
            }
        };
    }
}
