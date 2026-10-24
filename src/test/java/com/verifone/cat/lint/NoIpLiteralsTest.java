package com.verifone.cat.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The Java counterpart of the Python CAT's no-ip-literals gate (DESIGN.md 4.2 rule 5; {@code tools/lint/no_ip_literals.py}):
 * a rig's address belongs in one rig file, never in source.
 *
 * <p>Line based and over the whole text, comments included, as the Python is. It reports an IPv4 or IPv6 literal, a
 * hostname in a non-web URL ({@code ssl://}, {@code mqtt://} and the like), and a hostname in host position
 * ({@code host = "..."}, {@code host: ...}). Loopback and wildcard binds, RFC 5737 and RFC 3849 documentation
 * addresses, and RFC 2606 / RFC 6761 names are allowed, as the Python allows them.
 *
 * <p>Scope is {@code src/main}, as the task set it. Tests may use the documentation ranges freely.
 */
class NoIpLiteralsTest {

    private static final Path MAIN = Path.of("src", "main");

    /** Dotted quad not embedded in a longer dotted or word token; the Python's _IPV4. */
    private static final Pattern IPV4 =
            Pattern.compile("(?<![\\w.])(?<![A-Za-z]-)(\\d{1,3}(?:\\.\\d{1,3}){3})(?![\\w.])(?!-[A-Za-z])");

    /** Colon-separated hex groups, two or more; checked by {@link #isIpv6}. */
    private static final Pattern IPV6 = Pattern.compile("(?<![\\w:])((?:[0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f]{0,4})(?![\\w:])");

    private static final Pattern DEVICE_URL = Pattern.compile(
            "\\b(?:ssl|tcp|udp|mqtt|mqtts|amqp|amqps|ssh|sftp|ftp|telnet|rmi|jdbc|ws|wss)://([^/\\s'\"<>)\\\\]+)");

    private static final String HOST_KEY =
            "(?:[A-Za-z][A-Za-z0-9]*_)?(?:hostname|host|ip_address|ip|address|server|broker)";

    /** A quoted value in host position: the kwarg, JSON-key and quoted-YAML spellings. */
    private static final Pattern QUOTED_HOST = Pattern.compile(
            "\\b" + HOST_KEY + "\\b['\"]?\\s*[:=]\\s*['\"]([^'\"]*)['\"]");

    /** An unquoted {@code key: value} line, for the non-code files (YAML, .prop, .ini). */
    private static final Pattern BARE_HOST = Pattern.compile(
            "^\\s*" + HOST_KEY + "\\s*[:=]\\s*([^\\s'\"#,}\\]]+)\\s*(?:#.*)?$");

    private static final Pattern HOSTNAME = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9\\-]+)*$");

    private static final Pattern PLACEHOLDER = Pattern.compile("[{}$%<>*]");

    /** Names that are never a rig: loopback, the wildcard and the RFC 2606 / 6761 reserved names. */
    private static final List<String> LOCAL_NAMES = List.of("localhost", "127.0.0.1", "0.0.0.0", "255.255.255.255",
            "::1", "::");

    private static final List<String> RESERVED_SUFFIXES = List.of(".test", ".invalid", ".example", ".localhost");

    private static final List<String> RESERVED_NAMES = List.of("example.com", "example.org", "example.net");

    private static final List<String> DOCUMENTATION_IPV4 = List.of("192.0.2.", "198.51.100.", "203.0.113.");

    @Test
    void noMainSourceCarriesARigAddress() throws IOException {
        List<Path> files = LintFiles.files(MAIN, NoIpLiteralsTest::scanned, skip -> false);
        assertFalse(files.isEmpty(), "no files under " + MAIN.toAbsolutePath() + ": the gate would pass on nothing");

        List<String> found = new ArrayList<>();
        for (Path file : files) {
            found.addAll(findingsIn(LintFiles.rel(file), Files.readString(file)));
        }
        assertTrue(found.isEmpty(), "address or host outside rigs/*.yaml; read it from the rig file instead:\n  "
                + String.join("\n  ", found));
    }

    /** The text files a rig address could be written into. Binary files are not read. */
    private static boolean scanned(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".xml") || lower.endsWith(".properties") || lower.endsWith(".js")
                || lower.endsWith(".html") || lower.endsWith(".css") || lower.endsWith(".txt")
                || lower.endsWith(".md") || lower.endsWith(".prop") || lower.endsWith(".ini");
    }

    /** Each address or host in {@code text}, as {@code path:line literal (what)}. */
    static List<String> findingsIn(String path, String text) {
        boolean code = path.endsWith(".java") || path.endsWith(".js");
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\\r?\\n", -1);
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            for (String finding : scanLine(line, !code)) {
                found.add(path + ":" + (k + 1) + " " + finding);
            }
        }
        return found;
    }

    private static List<String> scanLine(String line, boolean bare) {
        List<String> out = new ArrayList<>();
        Matcher v4 = IPV4.matcher(line);
        while (v4.find()) {
            String token = v4.group(1);
            if (isIpv4(token) && !allowedIpv4(token)) {
                out.add(token + " (IPv4 literal)");
            }
        }
        Matcher v6 = IPV6.matcher(line);
        while (v6.find()) {
            String token = v6.group(1);
            if (isIpv6(token) && !allowedIpv6(token)) {
                out.add(token + " (IPv6 literal)");
            }
        }
        Matcher url = DEVICE_URL.matcher(line);
        while (url.find()) {
            String host = authority(url.group(1));
            if (!host.isEmpty() && !isIpv4(host) && HOSTNAME.matcher(host).matches() && !allowedName(host)) {
                out.add(host + " (hostname in a device URL)");
            }
        }
        List<Pattern> patterns = bare ? List.of(QUOTED_HOST, BARE_HOST) : List.of(QUOTED_HOST);
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(line);
            while (m.find()) {
                String value = m.group(1).strip();
                if (value.isEmpty() || PLACEHOLDER.matcher(value).find() || isIpv4(value) || isIpv6(value)) {
                    continue;
                }
                if (HOSTNAME.matcher(value).matches() && !allowedName(value)) {
                    out.add(value + " (hostname in host position)");
                }
            }
        }
        return out;
    }

    /** {@code user:pw@host:8883} gives {@code host}; a bracketed IPv6 host keeps its brackets' contents. */
    static String authority(String authority) {
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close < 0 ? host.substring(1) : host.substring(1, close);
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static boolean allowedName(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return LOCAL_NAMES.contains(lower) || RESERVED_NAMES.contains(lower)
                || RESERVED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private static boolean allowedIpv4(String token) {
        return LOCAL_NAMES.contains(token) || DOCUMENTATION_IPV4.stream().anyMatch(token::startsWith);
    }

    private static boolean allowedIpv6(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return LOCAL_NAMES.contains(lower) || lower.startsWith("2001:db8:") || lower.equals("2001:db8::");
    }

    /** A dotted quad of four octets, each 0 to 255. */
    static boolean isIpv4(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        return Arrays.stream(octets).allMatch(o -> o.matches("\\d{1,3}") && Integer.parseInt(o) <= 255);
    }

    /**
     * An IPv6 address, and not a false friend: two or more non-empty groups, a single {@code ::} at most, eight groups
     * when there is no {@code ::}. {@code 12:34:56} (a time) and {@code DESIGN::x} are not addresses.
     */
    static boolean isIpv6(String token) {
        int dc = token.indexOf("::");
        if (dc >= 0 && token.indexOf("::", dc + 1) >= 0) {
            return false;
        }
        List<String> groups = new ArrayList<>();
        if (dc < 0) {
            groups.addAll(Arrays.asList(token.split(":", -1)));
            if (groups.size() != 8 || groups.contains("")) {
                return false;
            }
        } else {
            String left = token.substring(0, dc);
            String right = token.substring(dc + 2);
            List<String> l = left.isEmpty() ? List.of() : Arrays.asList(left.split(":", -1));
            List<String> r = right.isEmpty() ? List.of() : Arrays.asList(right.split(":", -1));
            if (l.size() + r.size() > 7) {
                return false;
            }
            groups.addAll(l);
            groups.addAll(r);
        }
        if (groups.stream().filter(g -> !g.isEmpty()).count() < 2) {
            return false;
        }
        return groups.stream().allMatch(g -> g.isEmpty() || g.matches("[0-9A-Fa-f]{1,4}"));
    }

    @Test
    void theGateCatchesAddressesAndHostsButNotTheAllowedOnes() {
        String text = String.join("\n",
                "host = \"topaz101.lab\"",
                "String url = \"ssl://commander-box.lab:8883\";",
                "int ip = 10.0.0.11;",
                "IPv6 fe80::1ff:fe23:4567:890a is a link-local address",
                "private static final String HOST = \"topaz101.invalid\";",
                "bind 127.0.0.1 and 0.0.0.0 and localhost",
                "docs use 192.0.2.7 and 2001:db8::1",
                "a dotted version 17.0.5 and a time 12:34:56 are not addresses",
                "host: 10.1.2.3   # a YAML value");

        List<String> found = findingsIn("src/main/x.yaml", text);

        assertEquals(5, found.size(), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.contains("topaz101.lab (hostname in host position)")), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.contains("commander-box.lab (hostname in a device URL)")),
                found.toString());
        assertTrue(found.stream().anyMatch(f -> f.contains("10.0.0.11 (IPv4 literal)")), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.contains("fe80::1ff:fe23:4567:890a (IPv6 literal)")),
                found.toString());
        assertTrue(found.stream().anyMatch(f -> f.contains("10.1.2.3 (IPv4 literal)")), found.toString());
    }

    @Test
    void theHelpersKnowWhatAnAddressIs() {
        assertTrue(isIpv4("10.0.0.11"));
        assertFalse(isIpv4("10.0.0.256"));
        assertTrue(isIpv6("fe80::1ff:fe23:4567:890a"));
        assertFalse(isIpv6("12:34:56"));
        assertFalse(isIpv6("::"));
        assertEquals("commander.invalid", authority("manager:pw@commander.invalid:8883"));
        assertEquals("::1", authority("[::1]:8801"));
    }
}
