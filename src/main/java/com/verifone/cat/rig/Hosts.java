package com.verifone.cat.rig;

import java.util.regex.Pattern;

/**
 * Validation of the {@code host:} values in a rig file (DESIGN.md §8).
 *
 * <p>A host is an IPv4 or IPv6 literal, or a DNS name, and nothing else. A scheme, a port or a
 * path is refused: those have their own fields ({@code eps_port}, {@code cdp_port}), and a port
 * smuggled into {@code host} would be a connection to the wrong place.
 *
 * <p>Nothing here resolves a name. The check is purely lexical, so loading a rig never touches
 * the network, and {@link java.net.InetAddress} is not used for it because it would look up a
 * name.
 */
final class Hosts {

    /** RFC-1123-ish: labels of letters, digits and hyphens, no leading or trailing hyphen. */
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private static final Pattern HEX_GROUP = Pattern.compile("^[0-9A-Fa-f]{1,4}$");

    private Hosts() {}

    /**
     * Returns the host stripped of surrounding whitespace.
     *
     * @throws IllegalArgumentException with the message the Python loader gives for the same input
     */
    static String validate(Object value) {
        if (!(value instanceof String raw)) {
            throw new IllegalArgumentException("expected a hostname or IP address as a string, got " + value);
        }
        String host = raw.strip();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("must not be empty — a rig cannot be reached at ''");
        }
        if (host.contains("://") || host.contains("/") || host.contains(" ")) {
            throw new IllegalArgumentException("expected a bare hostname or IP address, got '" + raw
                    + "' (no scheme, port or path — ports have their own fields)");
        }
        if (IPV4.matcher(host).matches() || isIpv6(host)) {
            return host;
        }
        if (host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))) {
            // Looks like an IPv4 literal but is not one (a dotted quad with an octet above 255). It would pass the DNS
            // grammar, resolve to nothing and fail later as a connection timeout.
            throw new IllegalArgumentException("expected a valid IPv4 address, got '" + raw + "'");
        }
        if (!HOSTNAME.matcher(host).matches()) {
            throw new IllegalArgumentException("expected a hostname or IP address, got '" + raw + "'");
        }
        return host;
    }

    /**
     * An IPv6 literal: eight 16-bit groups, one {@code ::} standing for a run of zero groups, and
     * an optional dotted IPv4 tail. Written out rather than parsed by {@code InetAddress}, which
     * would do a name lookup on anything that is not a literal.
     */
    static boolean isIpv6(String host) {
        if (!host.contains(":")) {
            return false;
        }
        String head = host;
        int tailGroups = 0;
        int lastColon = host.lastIndexOf(':');
        String tail = host.substring(lastColon + 1);
        if (tail.contains(".")) {
            if (!IPV4.matcher(tail).matches()) {
                return false;
            }
            // The IPv4 tail is two 16-bit groups. Drop the colon that joins it to the rest,
            // but keep a "::" that ends the rest, which is still a compression.
            head = host.substring(0, lastColon + 1);
            tailGroups = 2;
            if (head.endsWith(":") && !head.endsWith("::")) {
                head = head.substring(0, head.length() - 1);
            }
        }
        int compressions = countOccurrences(head, "::");
        if (compressions > 1) {
            return false;
        }
        String[] groups = head.isEmpty() ? new String[0] : head.split(":", -1);
        int present = 0;
        for (String g : groups) {
            if (g.isEmpty()) {
                continue; // the empty halves of a "::"
            }
            if (!HEX_GROUP.matcher(g).matches()) {
                return false;
            }
            present++;
        }
        present += tailGroups;
        return compressions == 1 ? present < 8 : present == 8;
    }

    private static int countOccurrences(String s, String needle) {
        int count = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
