package com.verifone.cat.tools;

import java.util.LinkedHashSet;
import java.util.List;

import com.verifone.cat.provision.BrowserOverride;
import com.verifone.cat.rig.Hosts;

/**
 * A register the POS page shows and Push shim to register provisions: from the rig when one loads, else one of the
 * lab's registers ({@link LabRegisters}; user, 2026-11-10: the POS page needs no rig file).
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

    /** The register's DevTools endpoint, as the POS driver and the POS page's screen attach to it. */
    public String cdpUrl() {
        return cdpUrl(cdpPort);
    }

    /** The register's DevTools endpoint on {@code port}. */
    public String cdpUrl(int port) {
        return "http://" + host + ":" + port;
    }

    /**
     * The DevTools ports the POS page's screen tries, in order (user, 2026-11-10, Reg101): its own ({@link #cdpPort}),
     * then the browser override's relay ({@link BrowserOverride#DEFAULT_RELAY_PORT}), then the browser's own DevTools
     * port ({@link BrowserOverride#DEFAULT_CDP_PORT}), where a register without the override (its stock browser) answers
     * from the network: seen on Reg101 with the stock Chrome 67 on its pairing screen.
     */
    public List<Integer> cdpPorts() {
        return List.copyOf(new LinkedHashSet<>(List.of(cdpPort, BrowserOverride.DEFAULT_RELAY_PORT,
                BrowserOverride.DEFAULT_CDP_PORT)));
    }

    /** "Reg101 (host)", or the address alone when it is the id. */
    public String describe() {
        return id.equals(host) ? host : id + " (" + host + ")";
    }
}
