package com.verifone.cat.pos.driver;

import com.verifone.cat.rig.Rig;

/**
 * One register from the rig file's {@code registers:} block (DESIGN.md 8). Port of the Python
 * {@code RegisterEndpoint}. The host comes from the rig file and nowhere else (DESIGN.md 4.2 rule 5).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record RegisterEndpoint(String id, String host, int cdpPort) {

    /** The default CDP port, the one Playwright attaches to (DESIGN.md 5.2). */
    public static final int DEFAULT_CDP_PORT = 9222;

    public RegisterEndpoint {
        id = id == null ? "" : id;
        host = host == null ? "" : host;
        if (cdpPort <= 0) {
            cdpPort = DEFAULT_CDP_PORT;
        }
    }

    /** The endpoint of one register from a loaded rig. */
    public static RegisterEndpoint of(Rig.Register register) {
        return new RegisterEndpoint(register.id(), register.host(), register.cdpPort());
    }

    /** The CDP endpoint URL. Refuses an empty host: there is no default rig (rule 5). */
    public String cdpUrl() {
        if (host.isBlank()) {
            throw new DriverError("register has no host; rigs/*.yaml is the only source (DESIGN.md 8, rule 5)");
        }
        return "http://" + host + ":" + cdpPort;
    }
}
