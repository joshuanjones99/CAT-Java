package com.verifone.cat.cloudtunnel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * Carrying a Commander's outbound TCP through the controller: endpoint discovery, route planning, and the commands
 * that run on the Commander. A port of the Python {@code petrotest.cloud_tunnel} minus its serve loop (see the
 * class's TODO below).
 *
 * <p>What is here: the endpoint files and bridge parameters read off the Commander, the loopback aliases and
 * host/redirect rules (the hosts lines, the iptables DNAT pairs, the host routes), the sshd forwarding rule written
 * in an SELinux window, the learned-endpoint file, and gateway mode (the {@code CAT_GATEWAY} NAT chain, the relay
 * started on the Commander, and the Commander's {@code resolv.conf}). Every Commander-side step takes a
 * {@link Transport}, so a fake can answer it.
 *
 * <p>TODO (not ported): the serve loop, Python's {@code cloud_tunnel.serve}. It needs the SSH session's remote
 * (reverse) port forwards, which {@link Transport} does not expose; JSch's {@code setPortForwardingR} would have to
 * sit behind it. Until then {@link CloudTunnelCommand} can stop a tunnel, and report whether the Commander needs one,
 * but cannot start one. The step-4 caller ({@code cat.launch.tunnel}) is not ported.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Commander-side command strings are the Python's, read off the lab
 * Commander in 2026-10; none has run from this port.
 */
public final class CloudTunnel {

    /** The bridge's connection parameters, a Java properties file, on the Commander. */
    public static final String BRIDGE_PARAMS = "/home/coresvcs/config/mqtt/bridgeConnectionParams.properties";
    /** The profile file the day's Commander Connect lab is read from. */
    public static final String PROFILE_FILE = "/home/common/config/cloud_connect_profile.prop";
    static final String SSHD_CONFIG = "/etc/openssh/sshd_config";
    static final String HOSTS = "/etc/hosts";
    /** Backups, the host routes added, the learned endpoints and the relay live here on the Commander. */
    public static final String STATE_DIR = "/home/pmc/cat-cloud-tunnel";
    static final String ROUTES_FILE = STATE_DIR + "/host-routes";
    /** Endpoints learned at run time, one {@code https://host:port} a line. */
    public static final String LEARNED_FILE = STATE_DIR + "/learned-endpoints";
    /** The Commander's own line for a cloud URL it could not reach. */
    public static final String UNREACHED_LOG = "/var/log/messages";
    /** As a grep basic-regex: {@code Couldn't connect to https\?://host}. */
    static final String UNREACHED_PATTERN = "Couldn't connect to https\\?://[A-Za-z0-9.:-]*";

    /** The tag every line and rule this module writes carries, so {@code --stop} removes exactly those. */
    public static final String TAG = "# cloud-tunnel";
    /** Forward ports on the Commander's loopback: FORWARD_BASE, FORWARD_BASE + 1, ... */
    public static final int FORWARD_BASE = 20000;
    static final int MAX_ROUTES = 50;
    static final String LOOPBACK = "127.0.0.1";
    /** The last octet of the first loopback alias a name gets: the Python's loopback address plus 16, then + 1. */
    private static final int FIRST_ALIAS_OCTET = 17;
    private static final Set<String> NOT_CLOUD = Set.of("localhost", "sitecontroller");
    private static final Pattern URL = Pattern.compile("\\bhttps?://([A-Za-z0-9.-]+)(?::(\\d+))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final String LISTENER_PID = "ps -eo pid,args | grep '[s]shd: /usr/sbin/sshd \\[listener\\]'"
            + " | sed -E 's/^ *([0-9]+).*/\\1/'";

    /** How long the Commander's direct connect to the cloud may take before the WAN is called down. */
    static final int DIRECT_PROBE_SECONDS = 10;

    /** {@code CatNetRelay} listens here on the Commander's loopback: the REDIRECT target. */
    public static final int GATEWAY_LISTEN = 20090;
    /** The SSH reverse forward the relay dials; each channel starts {@code CONNECT <host> <port>}. */
    public static final int GATEWAY_FORWARD = 20091;
    public static final String GATEWAY_CHAIN = "CAT_GATEWAY";
    public static final String RELAY_REMOTE = STATE_DIR + "/CatNetRelay.java";
    static final String RELAY_PID = STATE_DIR + "/net-relay.pid";
    public static final String RELAY_LOG = STATE_DIR + "/net-relay.log";
    /** The Commander's JDK (Corretto 17, read off the lab Commander 2026-10-15); not on PATH. */
    public static final String COMMANDER_JAVA = "/usr/java/j2se/bin/java";
    static final String RESOLV = "/etc/resolv.conf";
    static final String RESOLV_BACKUP = STATE_DIR + "/resolv.conf.orig";
    /** Written when this tunnel added a default route via {@code lo} (a Commander with none). */
    static final String DEFAULT_ROUTE_FILE = STATE_DIR + "/default-route";
    /** How long {@code CatNetRelay} may take to compile and listen (Java runs it as source). */
    static final int RELAY_START_SECONDS = 40;
    /**
     * Controller ports the relay carries although the controller is on the Commander's own subnet: 443 is Petro's
     * HTTPSIM, which serves NACS TruAge's {@code /register} (2026-10-19).
     */
    static final List<Integer> CONTROLLER_CARRIED_PORTS = List.of(443);

    private CloudTunnel() {}

    /** The profile and its endpoints, as {@link #discover} reads them. */
    public record Discovery(String profile, List<Endpoint> endpoints) {}

    // ------------------------------------------------------------------------------------------------
    // Pure: what the Commander's files say
    // ------------------------------------------------------------------------------------------------

    /** {@code key=value} lines of a Java properties file; comments and blank lines are skipped. */
    public static Map<String, String> parseProperties(String text) {
        Map<String, String> props = new LinkedHashMap<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq >= 0) {
                props.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return props;
    }

    /** {@code profile=<lab>} from {@code cloud_connect_profile.prop}; a Commander without one is prod. */
    public static String profileOf(String text) {
        String profile = parseProperties(text).getOrDefault("profile", "").toLowerCase(Locale.ROOT);
        return profile.isEmpty() ? "prod" : profile;
    }

    static boolean isCloudHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (NOT_CLOUD.contains(lower)) {
            return false;
        }
        return !(lower.startsWith("127.") || lower.equals("::1"));
    }

    /**
     * The endpoint files for {@code profile}: prod's carry no suffix ({@code cloudEndpoints.prop}); every other lab's
     * do ({@code cloudEndpoints_qat.prop}). Read off the lab Commander, 2026-10-12.
     */
    public static List<String> endpointFiles(String profile) {
        String suffix = "prod".equals(profile) ? "" : "_" + profile;
        return List.of(
                "/home/coresvcs/config/cloudEndpoints" + suffix + ".prop",
                "/home/coresvcs/config/cloud_connect" + suffix + ".prop");
    }

    /**
     * Every cloud endpoint a Commander on {@code profile} dials, bridge first, in file order. The files are
     * properties files, which escape a colon ({@code https\://host}); the escapes are removed before the URLs are
     * found.
     */
    public static List<Endpoint> endpointsFor(String profile, String bridgeText, List<String> endpointTexts) {
        List<Endpoint> found = new ArrayList<>();
        Map<String, String> bridge = parseProperties(bridgeText);
        String host = bridge.getOrDefault(profile + ".host", "");
        int port = portOf(bridge.getOrDefault(profile + ".port", ""));
        if (!host.isEmpty() && port >= 0 && isCloudHost(host)) {
            found.add(new Endpoint(host, port));
        }
        for (String text : endpointTexts) {
            Matcher m = URL.matcher(text.replaceAll("\\\\(.)", "$1"));
            while (m.find()) {
                String name = m.group(1);
                boolean explicit = m.group(2) != null;
                int scheme = m.group(0).toLowerCase(Locale.ROOT).startsWith("https") ? 443 : 80;
                int value = explicit ? portOf(m.group(2)) : scheme;
                Endpoint endpoint = new Endpoint(name, value);
                if (value >= 0 && isCloudHost(name) && !found.contains(endpoint)) {
                    found.add(endpoint);
                }
            }
        }
        return found;
    }

    /** A decimal port in range, or -1. */
    private static int portOf(String text) {
        if (!text.matches("\\d+")) {
            return -1;
        }
        try {
            long value = Long.parseLong(text);
            return value <= 65535 ? (int) value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Routes for {@code endpoints}: a name gets its own loopback alias, an IP keeps its address. */
    public static List<Route> planRoutes(List<Endpoint> endpoints) {
        if (endpoints.size() > MAX_ROUTES) {
            throw new DeviceError(endpoints.size() + " cloud endpoints; at most " + MAX_ROUTES + " are relayed");
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        List<Route> routes = new ArrayList<>();
        for (int index = 0; index < endpoints.size(); index++) {
            Endpoint endpoint = endpoints.get(index);
            String localIp = endpoint.isIp() ? endpoint.host()
                    : aliases.computeIfAbsent(endpoint.host(),
                            h -> "127.0.0." + (FIRST_ALIAS_OCTET + aliases.size()));
            routes.add(new Route(endpoint, localIp, FORWARD_BASE + index));
        }
        return routes;
    }

    // ------------------------------------------------------------------------------------------------
    // Command builders
    // ------------------------------------------------------------------------------------------------

    /** {@code command} with SELinux permissive for its duration only, if it was enforcing. */
    public static String selinuxWindow(String command) {
        return "m=$(getenforce 2>/dev/null); [ \"$m\" = Enforcing ] && setenforce 0; "
                + "{ " + command + " ; }; rc=$?; [ \"$m\" = Enforcing ] && setenforce 1; exit $rc";
    }

    static String natRule(String action, Route route) {
        return "iptables -t nat -" + action + " OUTPUT -d " + route.localIp() + " -p tcp --dport "
                + route.endpoint().port() + " -j DNAT --to-destination " + LOOPBACK + ":" + route.forwardPort();
    }

    /** The {@code /etc/hosts} lines for named endpoints: one per loopback alias, tagged. */
    public static List<String> hostsLines(List<Route> routes) {
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (Route route : routes) {
            String host = route.endpoint().host();
            if (!route.endpoint().isIp()) {
                List<String> hosts = names.computeIfAbsent(route.localIp(), k -> new ArrayList<>());
                if (!hosts.contains(host)) {
                    hosts.add(host);
                }
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : names.entrySet()) {
            lines.add(entry.getKey() + " " + String.join(" ", entry.getValue()) + "  " + TAG);
        }
        return lines;
    }

    /** The sshd {@code Match} block allowing remote forwards from the controller to {@code ports}. */
    public static List<String> sshdBlock(String controllerIp, List<Integer> ports) {
        StringBuilder listen = new StringBuilder();
        for (int port : ports) {
            listen.append(listen.length() == 0 ? "" : " ").append(LOOPBACK).append(':').append(port);
        }
        return List.of(
                "Match Address " + controllerIp + " User root " + TAG,
                "\tAllowTcpForwarding remote " + TAG,
                "\tPermitListen " + listen + " " + TAG);
    }

    /** The gateway chain: loopback and the Commander's own subnets return; other TCP goes to the relay. */
    static String gatewayRules(List<String> subnets, List<Endpoint> carried) {
        String add = "iptables -t nat -A " + GATEWAY_CHAIN;
        List<String> steps = new ArrayList<>();
        steps.add("iptables -t nat -F " + GATEWAY_CHAIN);
        steps.add(add + " -o lo -j RETURN");
        for (Endpoint hop : carried) {
            steps.add(add + " -d " + hop.host() + " -p tcp --dport " + hop.port()
                    + " -j REDIRECT --to-ports " + GATEWAY_LISTEN);
        }
        for (String net : subnets) {
            steps.add(add + " -d " + net + " -j RETURN");
        }
        steps.add(add + " -p tcp -j REDIRECT --to-ports " + GATEWAY_LISTEN);
        steps.add("{ iptables -t nat -C OUTPUT -j " + GATEWAY_CHAIN + " 2>/dev/null || iptables -t nat -A OUTPUT -j "
                + GATEWAY_CHAIN + "; }");
        return "iptables -t nat -N " + GATEWAY_CHAIN + " 2>/dev/null; " + String.join(" && ", steps);
    }

    /**
     * {@code resolv.conf} for the controller's DNS servers, over TCP ({@code use-vc}): the relay carries TCP only, and
     * glibc then asks over TCP, which is redirected like the rest.
     */
    public static String resolvText(List<String> servers) {
        StringBuilder text = new StringBuilder();
        for (String server : servers) {
            text.append("nameserver ").append(server).append('\n');
        }
        return text.append("options use-vc  ").append(TAG).append('\n').toString();
    }

    static String relayRunning() {
        return "[ -f " + RELAY_PID + " ] && kill -0 $(cat " + RELAY_PID + ") 2>/dev/null && grep -q CatNetRelay /proc/$(cat "
                + RELAY_PID + ")/cmdline";
    }

    /** Exit 0 when something listens on 127.0.0.1:{@code port} ({@code /proc/net/tcp}, state 0A). */
    static String listening(int port) {
        return String.format("grep -qi ' 0100007F:%04X 00000000:0000 0A' /proc/net/tcp", port);
    }

    /**
     * Starts {@code CatNetRelay} unless it runs, then waits for it to listen. Only the {@code nohup} simple command is
     * backgrounded, so the channel's stdout is not held open; the relay's IPv4 stack is forced, as its listener is
     * in {@code /proc/net/tcp}.
     */
    static String startRelayCommand() {
        return "{ " + relayRunning() + "; } || { cd " + STATE_DIR + " || exit 1; nohup " + COMMANDER_JAVA
                + " -Djava.net.preferIPv4Stack=true " + RELAY_REMOTE + " " + GATEWAY_LISTEN + " " + GATEWAY_FORWARD
                + " >> " + RELAY_LOG + " 2>&1 < /dev/null & echo $! > " + RELAY_PID + "; }; n=0; until "
                + listening(GATEWAY_LISTEN) + "; do [ $n -ge " + RELAY_START_SECONDS
                + " ] && exit 1; sleep 1; n=$((n+1)); done";
    }

    /** Exit 0 when the relay listens, the chain is hooked, and {@code resolv.conf} is this tunnel's. */
    static String gatewayHealthyCommand(List<String> servers) {
        List<String> checks = new ArrayList<>();
        checks.add(listening(GATEWAY_LISTEN));
        checks.add("iptables -t nat -C OUTPUT -j " + GATEWAY_CHAIN);
        if (!servers.isEmpty()) {
            checks.add("grep -q 'use-vc  " + TAG + "' " + RESOLV);
        }
        return selinuxWindow(String.join(" && ", checks));
    }

    // ------------------------------------------------------------------------------------------------
    // Commander-side steps
    // ------------------------------------------------------------------------------------------------

    /** Runs {@code command}; a non-zero exit is a {@link DeviceError} naming {@code what}. */
    static String must(Transport transport, String command, Duration timeout, String what) {
        CommandResult result = transport.run(command, timeout);
        if (!result.ok()) {
            String detail = result.stderr().isEmpty() ? result.stdout() : result.stderr();
            throw new DeviceError(what + " failed: " + detail.strip());
        }
        return result.stdout();
    }

    /** A file's text, or empty when it is absent. */
    static String read(Transport transport, String path, Duration timeout) {
        CommandResult result = transport.run("cat " + path + " 2>/dev/null", timeout);
        return result.ok() ? result.stdout() : "";
    }

    /** The default route's presence in {@code route -n}'s output. */
    static boolean hasDefaultRoute(String routeN) {
        for (String line : routeN.split("\n")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length > 0 && fields[0].equals("0.0.0.0")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the Commander reaches {@code endpoint} itself, so needs no tunnel. A default route alone does not say so
     * (the lab LAN's gateway forwards nothing to the internet): the Commander connects to the endpoint, bounded by
     * {@link #DIRECT_PROBE_SECONDS}.
     */
    public static boolean wanUp(Transport transport, Endpoint endpoint, Duration timeout) {
        String out = transport.run("/sbin/route -n", timeout).stdout();
        if (!hasDefaultRoute(out)) {
            return false;
        }
        String probe = "( exec 3<>/dev/tcp/" + endpoint.host() + "/" + endpoint.port() + " ) 2>/dev/null & p=$!; n=0; "
                + "while kill -0 $p 2>/dev/null; do [ $n -ge " + DIRECT_PROBE_SECONDS + " ] && "
                + "{ kill $p; exit 1; }; sleep 1; n=$((n+1)); done; wait $p";
        return transport.run(probe, timeout.plusSeconds(DIRECT_PROBE_SECONDS)).ok();
    }

    static List<Endpoint> readLearned(Transport transport, Duration timeout) {
        return endpointsFor("", "", List.of(read(transport, LEARNED_FILE, timeout)));
    }

    /**
     * The profile and its endpoints: the Commander's files, then the learned file, then {@code remembered} (what this
     * tunnel learned while it ran; a PSI install wipes the learned file).
     */
    public static Discovery discover(Transport transport, Duration timeout, List<Endpoint> remembered) {
        String profile = profileOf(read(transport, PROFILE_FILE, timeout));
        List<String> texts = new ArrayList<>();
        for (String path : endpointFiles(profile)) {
            texts.add(read(transport, path, timeout));
        }
        List<Endpoint> endpoints = new ArrayList<>(endpointsFor(profile,
                read(transport, BRIDGE_PARAMS, timeout), texts));
        if (endpoints.isEmpty()) {
            throw new DeviceError("no cloud endpoints found for profile '" + profile + "'");
        }
        List<Endpoint> extras = new ArrayList<>(readLearned(transport, timeout));
        extras.addAll(remembered);
        for (Endpoint extra : extras) {
            if (!endpoints.contains(extra)) {
                endpoints.add(extra);
            }
        }
        return new Discovery(profile, endpoints);
    }

    /** {@code discover} with nothing remembered. */
    public static Discovery discover(Transport transport, Duration timeout) {
        return discover(transport, timeout, List.of());
    }

    /**
     * Cloud hosts the Commander logged it could not reach that have no tunnel line yet. The tunnel writes a host's
     * {@code /etc/hosts} line only once its forward is up, so an empty set means a retry will be relayed.
     */
    public static Set<String> unrelayedHosts(Transport transport, Duration timeout) {
        String logged = transport.run("grep -o \"" + UNREACHED_PATTERN + "\" " + UNREACHED_LOG + " 2>/dev/null",
                timeout).stdout();
        Set<String> wanted = new TreeSet<>();
        for (Endpoint endpoint : endpointsFor("", "", List.of(logged))) {
            wanted.add(endpoint.host());
        }
        String lines = transport.run("grep '" + TAG + "' " + HOSTS + " 2>/dev/null", timeout).stdout();
        Set<String> relayed = new LinkedHashSet<>();
        for (String line : lines.split("\n")) {
            String[] words = line.strip().split("\\s+");
            if (words.length > 1) {
                relayed.add(words[1]);
            }
        }
        wanted.removeAll(relayed);
        return wanted;
    }

    /**
     * Cloud endpoints the Commander logged it could not reach and that are not yet in {@code known}. Each is saved to
     * {@link #LEARNED_FILE}, which {@link #discover} reads, so the re-plan relays it.
     */
    public static List<Endpoint> learnEndpoints(Transport transport, List<Endpoint> known, Duration timeout) {
        String out = transport.run("grep -o \"" + UNREACHED_PATTERN + "\" " + UNREACHED_LOG
                + " 2>/dev/null | awk '!s[$0]++'", timeout).stdout();
        List<Endpoint> fresh = new ArrayList<>();
        for (Endpoint endpoint : endpointsFor("", "", List.of(out))) {
            if (!known.contains(endpoint)) {
                fresh.add(endpoint);
            }
        }
        if (!fresh.isEmpty()) {
            StringBuilder lines = new StringBuilder();
            for (Endpoint endpoint : fresh) {
                lines.append("https://").append(endpoint.host()).append(':').append(endpoint.port()).append("\\n");
            }
            must(transport, "mkdir -p " + STATE_DIR + "; printf '" + lines + "' >> " + LEARNED_FILE, timeout,
                    "recording learned endpoints");
        }
        return fresh;
    }

    /**
     * Allows the forwards in sshd_config; reloads sshd only when the block changed. Validates before the reload and
     * restores the backup when sshd refuses the rule.
     *
     * @return whether sshd was reloaded
     */
    public static boolean ensureSshd(Transport transport, String controllerIp, List<Integer> ports, Duration timeout) {
        List<String> current = new ArrayList<>();
        for (String line : read(transport, SSHD_CONFIG, timeout).split("\n")) {
            if (line.contains(TAG)) {
                current.add(line.strip());
            }
        }
        List<String> wanted = new ArrayList<>();
        for (String line : sshdBlock(controllerIp, ports)) {
            wanted.add(line.strip());
        }
        if (current.equals(wanted)) {
            return false;
        }
        String backup = STATE_DIR + "/sshd_config.orig";
        must(transport, "mkdir -p " + STATE_DIR + "; [ -e " + backup + " ] || cp -p " + SSHD_CONFIG + " " + backup,
                timeout, "backing up sshd_config");
        String body = String.join("\\n", sshdBlock(controllerIp, ports));
        must(transport, selinuxWindow("sed -i '/" + TAG + "/d' " + SSHD_CONFIG + " && printf '\\n" + body
                + "\\n' >> " + SSHD_CONFIG), timeout, "writing the sshd forwarding rule");
        CommandResult check = transport.run("/usr/sbin/sshd -t -f " + SSHD_CONFIG, timeout);
        if (!check.ok()) {
            transport.run(selinuxWindow("cp -p " + backup + " " + SSHD_CONFIG), timeout);
            throw new DeviceError("sshd rejected the forwarding rule, restored: " + check.stderr().strip());
        }
        must(transport, "kill -HUP $(" + LISTENER_PID + ")", timeout, "reloading sshd");
        return true;
    }

    /** Hosts lines, host routes and redirects for {@code routes}. Idempotent. */
    public static void applyRoutes(Transport transport, List<Route> routes, Duration timeout) {
        String lines = String.join("\\n", hostsLines(routes));
        String hosts = "sed -i '/" + TAG + "/d' " + HOSTS;
        if (!lines.isEmpty()) {
            hosts += " && printf '" + lines + "\\n' >> " + HOSTS;
        }
        must(transport, selinuxWindow(hosts), timeout, "writing /etc/hosts");
        Set<String> ips = new TreeSet<>();
        for (Route route : routes) {
            if (route.endpoint().isIp()) {
                ips.add(route.localIp());
            }
        }
        must(transport, "mkdir -p " + STATE_DIR + "; printf '%s\\n' " + String.join(" ", ips) + " > " + ROUTES_FILE,
                timeout, "recording host routes");
        for (String ip : ips) {
            transport.run("/sbin/route add -host " + ip + " dev lo 2>/dev/null", timeout);
        }
        for (Route route : routes) {
            must(transport, selinuxWindow(natRule("C", route) + " 2>/dev/null || " + natRule("A", route)), timeout,
                    "redirecting " + route.endpoint());
        }
    }

    /** Undoes {@link #applyRoutes}: hosts lines, every redirect to a forward port, the host routes. */
    public static void removeRoutes(Transport transport, Duration timeout) {
        transport.run(selinuxWindow("sed -i '/" + TAG + "/d' " + HOSTS), timeout);
        removeForwardRules(transport, timeout);
        transport.run("[ -e " + ROUTES_FILE + " ] && while read -r ip; do [ -n \"$ip\" ] && "
                + "/sbin/route del -host $ip dev lo 2>/dev/null; done < " + ROUTES_FILE + "; rm -f " + ROUTES_FILE,
                timeout);
    }

    /**
     * Deletes every NAT redirect to a forward port. The grep's pattern begins with {@code --}, so it is passed with
     * {@code -e}, or grep reads it as an option and matches nothing.
     */
    static void removeForwardRules(Transport transport, Duration timeout) {
        String loopback = LOOPBACK.replace(".", "\\.");
        String forwards = "--to-destination " + loopback + ":" + (FORWARD_BASE / 100) + "[0-9]{2}$";
        transport.run(selinuxWindow(
                "iptables -t nat -S OUTPUT | grep -E -e '" + forwards + "' | sed 's/^-A /-D /' "
                        + "| while read -r rule; do eval iptables -t nat $rule; done"),
                timeout);
    }

    /** The Commander's connected subnets: the routes with no gateway. Never redirected. */
    public static List<String> connectedSubnets(String routeN) {
        List<String> subnets = new ArrayList<>();
        for (String line : routeN.split("\n")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length < 3 || !fields[1].equals("0.0.0.0") || fields[0].equals("0.0.0.0")) {
                continue;
            }
            String net = network(fields[0], fields[2]);
            if (net != null && !subnets.contains(net)) {
                subnets.add(net);
            }
        }
        return subnets;
    }

    /** {@code dest/prefix} for a dotted netmask, or null when either is not a valid IPv4 address or mask. */
    static String network(String dest, String mask) {
        long address = ipv4(dest);
        long netmask = ipv4(mask);
        if (address < 0 || netmask < 0) {
            return null;
        }
        // A contiguous mask: its inverse is 2^k - 1, so adding one clears every bit that is set.
        long inverse = ~netmask & 0xffffffffL;
        if (((inverse + 1) & inverse) != 0) {
            return null;
        }
        int prefix = Long.bitCount(netmask);
        long base = address & netmask;
        return ((base >>> 24) & 0xff) + "." + ((base >>> 16) & 0xff) + "." + ((base >>> 8) & 0xff) + "." + (base & 0xff)
                + "/" + prefix;
    }

    /** A dotted quad as an unsigned 32-bit value in a long, or -1. (An int went negative for a first octet of 128 or more.) */
    private static long ipv4(String text) {
        if (!IPV4.matcher(text).matches()) {
            return -1;
        }
        long value = 0;
        for (String octet : text.split("\\.")) {
            int part = Integer.parseInt(octet);
            if (part > 255) {
                return -1;
            }
            value = (value << 8) | part;
        }
        return value;
    }

    /** CRLF to LF, as the relay source is pushed. */
    static byte[] lfOnly(byte[] source) {
        byte[] out = new byte[source.length];
        int n = 0;
        for (int i = 0; i < source.length; i++) {
            if (source[i] == '\r' && i + 1 < source.length && source[i + 1] == '\n') {
                continue;
            }
            out[n++] = source[i];
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Gateway mode on the Commander: the relay pushed and started, the default route added if there is none, the
     * chain hooked, and {@code resolv.conf} pointed at the controller's DNS.
     *
     * @param servers     the controller's IPv4 DNS servers; empty leaves {@code resolv.conf} alone
     * @param relaySource the bytes of {@code CatNetRelay.java}
     * @param say         where the "cloud endpoints only" line goes
     * @return true when the gateway is on; false leaves the routes alone
     */
    public static boolean applyGateway(Transport transport, List<String> servers, byte[] relaySource,
            Duration timeout, Consumer<String> say) {
        byte[] source = lfOnly(relaySource);
        Optional<byte[]> current = transport.get(RELAY_REMOTE, timeout);
        if (current.isEmpty() || !Arrays.equals(current.get(), source)) {
            must(transport, "mkdir -p " + STATE_DIR, timeout, "making the state directory");
            transport.put(RELAY_REMOTE, source, 0644, timeout);
            transport.run(relayRunning() + " && kill $(cat " + RELAY_PID + ")", timeout);
        }
        CommandResult started = transport.run(startRelayCommand(), timeout.plusSeconds(RELAY_START_SECONDS));
        if (!started.ok()) {
            List<String> log = new ArrayList<>();
            for (String line : read(transport, RELAY_LOG, timeout).strip().split("\n")) {
                if (!line.isBlank()) {
                    log.add(line);
                }
            }
            List<String> tail = log.subList(Math.max(0, log.size() - 3), log.size());
            say.accept("gateway: CatNetRelay did not listen; cloud endpoints only. " + String.join(" | ", tail));
            return false;
        }
        String routeN = transport.run("/sbin/route -n", timeout).stdout();
        if (!hasDefaultRoute(routeN)) {
            must(transport, "/sbin/route add default dev lo && echo lo > " + DEFAULT_ROUTE_FILE, timeout,
                    "adding a default route");
        }
        String controllerIp = transport.run("echo ${SSH_CLIENT%% *}", timeout).stdout().strip();
        List<Endpoint> carried = new ArrayList<>();
        if (!controllerIp.isEmpty()) {
            for (int port : CONTROLLER_CARRIED_PORTS) {
                carried.add(new Endpoint(controllerIp, port));
            }
        }
        must(transport, selinuxWindow(gatewayRules(connectedSubnets(routeN), carried)), timeout,
                "redirecting outbound TCP to CatNetRelay");
        if (!servers.isEmpty()) {
            String body = resolvText(servers).replace("\n", "\\n");
            must(transport, selinuxWindow("{ [ -e " + RESOLV_BACKUP + " ] || cp -p " + RESOLV + " " + RESOLV_BACKUP
                    + "; } && printf '" + body + "' > " + RESOLV), timeout, "writing " + RESOLV);
        }
        return true;
    }

    /**
     * For a tunnel adopted rather than started: applies this checkout's gateway rules when the Commander's NAT table
     * hooks the gateway chain, and leaves the Commander alone otherwise.
     *
     * @return what it did, for the step's log
     */
    public static String refreshGateway(Transport transport, List<String> servers, byte[] relaySource,
            Duration timeout, Consumer<String> say) {
        boolean hooked = transport.run(selinuxWindow("iptables -t nat -C OUTPUT -j " + GATEWAY_CHAIN), timeout).ok();
        if (!hooked) {
            return "gateway not on: left alone";
        }
        boolean on = applyGateway(transport, servers, relaySource, timeout, say);
        return on ? "gateway rules re-applied" : "gateway re-apply FAILED";
    }

    /** Undoes {@link #applyGateway}: the hook and chain, the relay, {@code resolv.conf}, an added route. */
    public static void removeGateway(Transport transport, Duration timeout) {
        transport.run(selinuxWindow("while iptables -t nat -D OUTPUT -j " + GATEWAY_CHAIN + " 2>/dev/null; do :; done; "
                + "iptables -t nat -F " + GATEWAY_CHAIN + " 2>/dev/null; "
                + "iptables -t nat -X " + GATEWAY_CHAIN + " 2>/dev/null; "
                + "if grep -q '" + TAG + "' " + RESOLV + " 2>/dev/null; then "
                + "if [ -e " + RESOLV_BACKUP + " ]; then cp -p " + RESOLV_BACKUP + " " + RESOLV + "; "
                + "else : > " + RESOLV + "; fi; fi; true"), timeout);
        transport.run(relayRunning() + " && kill $(cat " + RELAY_PID + "); rm -f " + RELAY_PID, timeout);
        transport.run("[ -e " + DEFAULT_ROUTE_FILE + " ] && /sbin/route del default dev lo 2>/dev/null; "
                + "rm -f " + DEFAULT_ROUTE_FILE + "; true", timeout);
    }

    /**
     * The controller's IPv4 DNS servers: those of its default route's interface on Windows, else the resolv.conf
     * nameservers. Read only; nothing on this machine is changed.
     */
    public static List<String> controllerDns() {
        List<String> found = new ArrayList<>();
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "$r = Get-NetRoute -DestinationPrefix 0.0.0.0/0 | Sort-Object RouteMetric"
                        + " | Select-Object -First 1; (Get-DnsClientServerAddress -AddressFamily IPv4"
                        + " -InterfaceIndex $r.ifIndex).ServerAddresses")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                process.getOutputStream().close();
                // Bounded first, read after: a read before the wait would block for as long as PowerShell hangs.
                // The answer is a line or two, well inside the pipe's buffer.
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return List.of();
                }
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                for (String token : out.strip().split("\\s+")) {
                    if (!token.isEmpty()) {
                        found.add(token);
                    }
                }
            } else {
                for (String line : Files.readAllLines(Path.of(RESOLV))) {
                    String[] parts = line.strip().split("\\s+");
                    if (parts[0].equals("nameserver") && parts.length > 1) {
                        found.add(parts[1]);
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        List<String> servers = new ArrayList<>();
        for (String item : found) {
            if (IPV4.matcher(item).matches() && ipv4(item) >= 0 && !item.startsWith("127.") && !item.isBlank()) {
                servers.add(item);
            }
        }
        return servers;
    }
}
