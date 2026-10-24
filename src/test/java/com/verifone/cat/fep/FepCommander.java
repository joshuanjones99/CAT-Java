package com.verifone.cat.fep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.FakeCommander.Reply;
import com.verifone.cat.devices.commander.FakeCommander.Request;

/**
 * A CGILink endpoint with a FEP table, as far as the secondary-FEP step uses one: the Python tests' {@code FakeCommander}
 * ({@code tests/unit/test_secondary_fep.py}). A {@code ufepcfg} sets {@code <enabled>}, except on a Commercial Fleet
 * FEP, whose handler never writes it. Evidence about this repository; no Commander.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class FepCommander implements Function<Request, Reply> {

    static final String OK = FakeCommander.NS_OK;

    private static final Pattern ENABLED = Pattern.compile("<enabled>\\s*(\\w+)\\s*</enabled>");

    /** Each FEP's enabled state, in table order. */
    final Map<String, Boolean> enabled = new LinkedHashMap<>();

    /** Commercial Fleet FEPs: a {@code ufepcfg} sets their host, never {@code <enabled>}. */
    final Set<String> licensed = new HashSet<>();

    /** Onboarded to Commander Connect: {@code vcloudconnectstatus} carries a timestamp. */
    boolean onboarded = true;

    /** When set, what was posted is never read back (the Python's {@code _ForgetfulDict}). */
    boolean forgetPosts;

    /** Every command sent except the login, in order, as {@code cmd} plus {@code &fep=<name>}. */
    final List<String> commands = new ArrayList<>();

    /** What was posted, keyed by command, with its payload. */
    final Map<String, String> posted = new HashMap<>();

    FepCommander(Map<String, Boolean> enabled, Set<String> licensed) {
        this.enabled.putAll(enabled);
        this.licensed.addAll(licensed);
    }

    /** The command a request carries: {@code cmd} and its {@code fep}, joined as the Python's handlers read them. */
    static String command(Request request) {
        Map<String, String> fields = request.fields();
        String cmd = fields.getOrDefault("cmd", "?");
        return fields.containsKey("fep") ? cmd + "&fep=" + fields.get("fep") : cmd;
    }

    /** The text after the head: the payload of an update. */
    static String payload(Request request) {
        String[] parts = request.body().split("\n\n", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    @Override
    public Reply apply(Request request) {
        String command = command(request);
        if (command.equals("validate")) {
            return Reply.ok(FakeCommander.FRESH_COOKIE);
        }
        commands.add(command);
        if (command.equals("vfepdetails")) {
            return Reply.ok(details());
        }
        if (command.equals("vcloudconnectstatus")) {
            String status = onboarded
                    ? "<registrationTimeStamp>2026-10-07T12:00:00Z</registrationTimeStamp>"
                    : "<cloudConnectStatus code=\"2\">NOT REGISTERED</cloudConnectStatus>";
            return Reply.ok("<status>" + status + "</status>");
        }
        if (command.startsWith("vfepcfg&fep=")) {
            String name = command.substring("vfepcfg&fep=".length());
            String postedText = forgetPosts ? null : posted.get("u" + command.substring(1));
            if (postedText != null) {
                return Reply.ok(postedText);
            }
            return Reply.ok(fepConfig(name, enabled.getOrDefault(name, false), "<domainName>host.invalid</domainName>"));
        }
        if (command.startsWith("u")) {
            String text = payload(request);
            posted.put(command, text);
            if (command.startsWith("ufepcfg&fep=")) {
                String name = command.substring("ufepcfg&fep=".length());
                if (!licensed.contains(name)) {
                    Matcher match = ENABLED.matcher(text);
                    if (match.find()) {
                        enabled.put(name, match.group(1).equals("true"));
                    }
                }
            }
        }
        return Reply.ok(OK);
    }

    /** {@code vfepdetails}, the shape PATS's reference reads. */
    String details() {
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, Boolean> fep : enabled.entrySet()) {
            boolean primary = fep.getKey().equals("buypass");
            rows.append("  <fepDetail fep=\"").append(fep.getKey()).append("\" fepType=\"")
                    .append(primary ? "Primary" : "Secondary").append("\">\n")
                    .append("    <allowedCommand name=\"vfepcfg\" type=\"CONFIG_VIEW\" />\n")
                    .append("    <enabled>").append(fep.getValue() ? "true" : "false").append("</enabled>\n")
                    .append("    <isPrimary>").append(primary).append("</isPrimary>\n")
                    .append("  </fepDetail>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                + "<viper:fepDetails xmlns:viper=\"core.viper.verifone.com.2011-11-08\">\n"
                + "  <primaryFep>buypass</primaryFep>\n" + rows + "</viper:fepDetails>\n";
    }

    /** A {@code viper:fepConfig} shaped like PETROCORE-37412's {@code vfepcfg} references. */
    static String fepConfig(String name, boolean on, String address) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"
                + "<viper:fepConfig xmlns:viper=\"core.viper.verifone.com.2011-11-08\""
                + " xmlns:vs=\"urn:vfi-sapphire:vs.2001-10-01\">\n"
                + "  <config>\n"
                + "    <fepName terminalId=\"\" dealerId=\"123456\">" + name + "</fepName>\n"
                + "    <enabled>" + (on ? "true" : "false") + "</enabled>\n"
                + "    <enabledInsidePrompting>false</enabledInsidePrompting>\n"
                + "    <commOptions maxRecords=\"1\">\n"
                + "      <commOption sysId=\"1\">\n"
                + "        <ip><address>" + address + "</address><port>80</port></ip>\n"
                + "      </commOption>\n"
                + "    </commOptions>\n"
                + "  </config>\n"
                + "  <editableConfig>\n"
                + "    <editableEntry elementName=\"enabled\" enabled=\"true\" visible=\"true\" />\n"
                + "  </editableConfig>\n"
                + "</viper:fepConfig>\n";
    }
}
