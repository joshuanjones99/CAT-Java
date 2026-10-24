package com.verifone.cat.cloudtunnel;

import java.util.regex.Pattern;

/**
 * A cloud host and port the Commander dials. A port of the Python {@code petrotest.cloud_tunnel.Endpoint}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param host a host name or an IP literal
 * @param port the TCP port
 */
public record Endpoint(String host, int port) {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Whether {@link #host} is an IP literal rather than a name. */
    public boolean isIp() {
        return IPV4.matcher(host).matches() || host.contains(":");
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
