package com.verifone.cat.commander.snapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.PdkShim;

/**
 * The Python tests' {@code FakeCommander}: a CGILink endpoint on loopback with a config store keyed by area, a
 * funcList and OTP-gated views, answering the way {@code docs/commander-config-surface.md} says the real one does
 * (faults as HTTP 200, the OTP minted by the fault). The real {@link PdkShim} talks to it over HTTP.
 *
 * <p>Evidence about this repository only: no Commander has run any of this.
 */
class SnapshotCommander implements AutoCloseable {

    static final String NS = "xmlns:domain=\"urn:vfi-sapphire:np.domain.2001-07-01\"";
    static final String OTP = "4321";
    static final String OK = "<VFI:Response xmlns:VFI=\"urn:vfi-sapphire:np.domain.2001-07-01\"/>";

    final Map<String, String> state = new LinkedHashMap<>();
    final Set<String> otpRequired = new HashSet<>(Set.of("vpairingsecret"));
    final Set<String> faulting = new HashSet<>(Set.of("vMaintenance"));
    final Set<String> ignoredUpdates = new HashSet<>();
    final List<String> posted = new CopyOnWriteArrayList<>();
    final List<Object[]> functions = new ArrayList<>(List.of(
            new Object[] {"vfuelcfg", false}, new Object[] {"ufuelcfg", false},
            new Object[] {"vfepcfg", false}, new Object[] {"ufepcfg", false},
            new Object[] {"vtaxratecfg", false}, new Object[] {"utaxratecfg", false},
            new Object[] {"vnetworkcfg", false}, new Object[] {"unetworkcfg", false},
            new Object[] {"vmysterycfg", false}, new Object[] {"umysterycfg", false},
            new Object[] {"vpairingsecret", true}, new Object[] {"upairingsecret", true},
            new Object[] {"vMaintenance", false}, new Object[] {"uMaintenance", false},
            new Object[] {"vtransset", false}, new Object[] {"cfuelinit", false},
            new Object[] {"ureportonly", false}));
    /** Called before every request, as the Python test's wrapped {@code send_command} (the Commander's clock). */
    volatile java.util.function.Consumer<String> beforeEach = command -> {};

    private final FakeCommander server;

    SnapshotCommander() {
        state.put("fuelcfg", "<domain:fuelConfig " + NS + "><site>S1</site><fuel collect=\"0\"/>"
                + "<grade>1</grade></domain:fuelConfig>");
        state.put("taxratecfg", "<domain:taxRateConfig " + NS + " stamp=\"1\"><name>primary</name>"
                + "</domain:taxRateConfig>");
        state.put("fepcfg&fep=buypass", "<domain:fepConfig " + NS + "><name>buypass</name></domain:fepConfig>");
        state.put("fepcfg&fep=incomm", "<domain:fepConfig " + NS + "><name>incomm</name></domain:fepConfig>");
        state.put("networkcfg", "<domain:net " + NS + "><ip>lan</ip></domain:net>");
        state.put("mysterycfg", "<domain:mystery " + NS + "><x>1</x></domain:mystery>");
        state.put("pairingsecret", "<domain:p " + NS + "><y>1</y></domain:p>");
        // As the lab Commander answers it (2026-10-21): each FEP and the views it allows.
        state.put("fepdetails", "<viper:fepDetails>"
                + "<fepDetail fep=\"buypass\"><allowedCommand name=\"vfepcfg\" type=\"CONFIG_VIEW\"/></fepDetail>"
                + "<fepDetail fep=\"incomm\"><allowedCommand name=\"vfepcfg\" type=\"CONFIG_VIEW\"/></fepDetail>"
                + "</viper:fepDetails>");
        server = new FakeCommander(request -> FakeCommander.Reply.ok(answer(request.body())));
    }

    static String fault(String code) {
        return FakeCommander.fault(code);
    }

    /** The request's head split as the Python handler splits it. */
    record Head(Map<String, String> fields, String command, String payload) {}

    static Head head(String body) {
        String[] parts = body.split("\n\n", 2);
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> extra = new ArrayList<>();
        for (String part : parts[0].split("&")) {
            String[] kv = part.split("=", 2);
            String value = kv.length > 1 ? kv[1] : "";
            fields.put(kv[0], value);
            if (!Set.of("cmd", "cookie", "otp", "user", "passwd").contains(kv[0])) {
                extra.add(kv[0] + "=" + value);
            }
        }
        List<String> command = new ArrayList<>();
        command.add(fields.getOrDefault("cmd", ""));
        command.addAll(extra);
        return new Head(fields, String.join("&", command), parts.length > 1 ? parts[1] : "");
    }

    /** The Python's {@code handler}; a subclass overrides it for NAXML's own semantics. */
    String answer(String body) {
        Head head = head(body);
        String command = head.command();
        beforeEach.accept(command);
        posted.add(head.fields().containsKey("otp") ? command + " (otp)" : command);
        String name = head.fields().get("cmd");
        if (name.equals("validate")) {
            StringBuilder funcs = new StringBuilder();
            for (Object[] f : functions) {
                funcs.append("<Function><FunctionDisplay>").append(f[0]).append("</FunctionDisplay><FunctionCmd>")
                        .append(f[0]).append("</FunctionCmd><OTPRequired>").append(f[1])
                        .append("</OTPRequired></Function>");
            }
            return "<domain:credential " + NS + "><cookie>c00k1e</cookie><funcList>" + funcs
                    + "</funcList></domain:credential>";
        }
        if (otpRequired.contains(name) && !OTP.equals(head.fields().get("otp"))) {
            return fault("CGIPortal.OTPRequired");
        }
        if (faulting.contains(name)) {
            return fault("CGIPortal.InternalError");
        }
        String area = command.substring(1);
        if (name.startsWith("c")) { // crefreshcfg and the other inits
            return OK;
        }
        if (name.startsWith("v") && state.containsKey(area)) {
            return state.get(area);
        }
        if (name.startsWith("u") && state.containsKey(area)) {
            if (!ignoredUpdates.contains(area)) {
                state.put(area, head.payload());
            }
            return OK;
        }
        return fault("CGIPortal.UnknownCommand");
    }

    /** A real {@link PdkShim} on this fake, with the OTP read "over SSH". */
    PdkShim shim() {
        return PdkShim.onPort(server.host(), server.port(), Duration.ofSeconds(10), () -> OTP);
    }

    CommanderConfig client() {
        return CommanderConfig.of(shim());
    }

    List<String> updates() {
        return posted.stream().filter(p -> p.startsWith("u")).toList();
    }

    /** Every request but the logins, as the Python's {@code p != "validate"}. */
    List<String> postedWithoutLogins() {
        return posted.stream().filter(p -> !p.equals("validate")).toList();
    }

    @Override
    public void close() {
        server.close();
    }
}
