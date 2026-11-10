package com.verifone.cat.tools;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.rig.Hosts;

/**
 * The register the POS page shows and Push shim to register provisions: from the rig when one loads, else the Settings
 * page's register address (user, 2026-11-10: the POS page needs no rig file).
 *
 * @param id       what the page calls it: the rig's register id, or the address itself
 * @param host     the register's address, validated as a rig file's {@code host} is
 * @param shimPort the pos-shim's port ({@code --shim-port})
 * @param cdpPort  the register's DevTools port, through the CDP relay
 */
public record RegisterTarget(String id, String host, int shimPort, int cdpPort) {

    /** The register's DevTools port when no rig names one (a rig file's {@code cdp_port} default). */
    public static final int DEFAULT_CDP_PORT = 9222;

    public RegisterTarget {
        host = Hosts.validate(host);
        id = id == null || id.isBlank() ? host : id.strip();
        if (shimPort < 1 || shimPort > 65535 || cdpPort < 1 || cdpPort > 65535) {
            throw new IllegalArgumentException("ports must be 1 to 65535");
        }
    }

    /** A register known only by its address (the Settings page's), on the default ports. */
    public static RegisterTarget ofAddress(String host) {
        return new RegisterTarget(null, host, PosShimClient.DEFAULT_SHIM_PORT, DEFAULT_CDP_PORT);
    }

    /** The register's DevTools endpoint, as the POS driver and the POS page's screen attach to it. */
    public String cdpUrl() {
        return "http://" + host + ":" + cdpPort;
    }

    /** "Reg101 (host)", or the address alone when it is the id. */
    public String describe() {
        return id.equals(host) ? host : id + " (" + host + ")";
    }
}
