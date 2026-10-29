package com.verifone.cat.commander.connect;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.csite.CSiteClient;
import com.verifone.cat.devices.csite.CSiteDatasets;
import com.verifone.cat.devices.csite.CSiteSettings;
import com.verifone.cat.devices.csite.CommanderCloudConfig;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.rig.Rig;

/**
 * {@code cat-java csite-reset --rig R --service-id ID}: every smart suggestion on a Commander Connect service deleted,
 * and every tip turned off (a stopped Smart Suggestion or Tipping run's leftovers). The Python's
 * {@code petrotest csite-reset} and {@code csite_reset_cli} in {@code petrotest.devices.csite}.
 *
 * <p>The C-Site login is {@code $CAT_CSITE_*}; its addresses are the rig's Commander's ({@link CommanderCloudConfig}).
 * A cloud CAT cannot reach is a WARN and success: the plans that need C-Site fail on it themselves, and the others do
 * not need it. So it always exits 0 once the rig loads.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CSiteReset {

    static final RigCommand.Spec SPEC = new RigCommand.Spec("csite-reset",
            "usage: cat-java csite-reset --rig <name|file.yaml> --service-id ID",
            Map.of("--service-id", ""), Set.of("--service-id"), Set.of(), Set.of(), List.of());

    /** Builds the C-Site client for a rig, or throws {@link DeviceError} when it cannot. */
    @FunctionalInterface
    public interface Clients {
        CSiteClient open(Rig rig, Map<String, String> environ);
    }

    /** The production client: {@code CsiteSettings.from_env(rig=rig)}. */
    public static final Clients LIVE = (rig, environ) -> {
        CommanderAccess access = new CommanderAccess(rig, JschTransport::new, environ);
        return CSiteClient.of(CSiteSettings.fromEnvironment(environ, CommanderCloudConfig.forCommander(
                rig.commander().host(), access::connectedTransport, access.sshTimeout())));
    };

    private final RigCommand.Loader loader;
    private final Clients clients;

    /** The production command. */
    public CSiteReset() {
        this(RigCommand.Loader.RIGS, LIVE);
    }

    /** A command over given seams; tests use this so no cloud is reached. */
    public CSiteReset(RigCommand.Loader loader, Clients clients) {
        this.loader = loader;
        this.clients = clients;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, (rig, args, environ, say) -> RigCommand.exit(
                cli(() -> clients.open(rig, environ), args.value("--service-id"), say)));
    }

    /**
     * {@code csite_reset_cli}: {@link CSiteDatasets#clearSmartSuggestions} and {@link CSiteDatasets#clearTips}, each
     * said; a fault is a WARN line. Always true.
     */
    public static boolean cli(Supplier<CSiteClient> clients, String serviceId, Say say) {
        CSiteClient client;
        try {
            client = clients.get();
        } catch (DeviceError e) {
            say.out("csite-reset: WARN nothing checked: " + e.getMessage());
            return true;
        }
        clear(say, client, serviceId, "smart suggestion", false, CSiteDatasets::clearSmartSuggestions);
        clear(say, client, serviceId, "tip", true, CSiteDatasets::clearTips);
        return true;
    }

    private static void clear(Say say, CSiteClient client, String serviceId, String what, boolean tips,
            BiFunction<CSiteClient, String, List<Long>> clear) {
        String done = tips ? "turned off" : "deleted";
        List<Long> deleted;
        try {
            deleted = clear.apply(client, serviceId);
        } catch (DeviceError e) {
            say.out("csite-reset: WARN " + what + "s not checked: " + e.getMessage());
            return;
        }
        if (!deleted.isEmpty()) {
            say.out("csite-reset: " + done + " " + deleted.size() + " " + what + "(s) on service " + serviceId
                    + ": sysid " + deleted.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        } else {
            say.out("csite-reset: service " + serviceId + " has no " + what + "s" + (tips ? " on" : ""));
        }
    }
}
