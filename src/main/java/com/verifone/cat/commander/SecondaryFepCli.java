package com.verifone.cat.commander;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.verifone.cat.commander.links.PaymentLinksCommand;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.csite.CSiteClient;
import com.verifone.cat.devices.csite.CSiteResponse;
import com.verifone.cat.devices.csite.CSiteSettings;
import com.verifone.cat.devices.csite.CommanderCloudConfig;
import com.verifone.cat.fep.FepClient;
import com.verifone.cat.fep.SecondaryFep;
import com.verifone.cat.fep.SecondaryFeps;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ToolsRoot;

/**
 * {@code cat-java secondary-fep --rig R --fep NAME [--fep NAME ...] [--petroapps DIR] [--service-id ID]}: the given
 * secondary FEPs enabled (or {@code none}), any other a past run left on switched off. The Python's
 * {@code petrotest secondary-fep} and {@code secondary_fep_cli}, which step 4 runs on every rig run
 * ({@code CommanderStages.Real.secondaryFep} calls {@link #select}).
 *
 * <p>With {@code --service-id} and a C-Site login ({@code $CAT_CSITE_*}), each chosen Commercial Fleet FEP is also
 * pointed at the controller in Commander Connect ({@link SecondaryFeps#pushCloudProviders}, the Python's
 * {@code _cloud_sender}), through the C-Site client {@code CLOUD_HTTP_REQUEST} uses, its endpoints read from the rig's
 * Commander ({@link CommanderCloudConfig}). Deviation: the Python's {@code _cloud_sender} calls
 * {@code CsiteSettings.from_env()} with no rig, which always refuses ("no rig: C-Site's addresses are read from the
 * rig's Commander"), so its cloud push never runs; this port reads the endpoints from the rig's Commander, as every
 * other C-Site caller does.
 *
 * <p>With nothing chosen, a fault is a warning (only the switching-off failed, and the run needs none of it).
 *
 * <p>Exit codes: 0 done, 1 failed, 2 usage or a rig that does not load. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class SecondaryFepCli {

    static final RigCommand.Spec SPEC = new RigCommand.Spec("secondary-fep",
            "usage: cat-java secondary-fep --rig <name|file.yaml> --fep NAME [--fep NAME ...] [--petroapps DIR] "
                    + "[--service-id ID]",
            Map.of("--petroapps", "", "--service-id", ""), Set.of(), Set.of(), Set.of("--fep"), List.of());

    private final RigCommand.Loader loader;
    private final RigCommand.Body body;

    /** The production command. */
    public SecondaryFepCli() {
        this(RigCommand.Loader.RIGS, (rig, args, env, say) -> RigCommand.exit(select(rig, args.all("--fep"),
                PaymentLinksCommand.petroapps(args.value("--petroapps"), env), args.value("--service-id"), env,
                new CommanderAccess(rig, JschTransport::new, env), say)));
    }

    /** A command over given seams; tests use this so no Commander is reached. */
    public SecondaryFepCli(RigCommand.Loader loader, RigCommand.Body body) {
        this.loader = loader;
        this.body = body;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        for (int i = 0; i < argv.length; i++) {
            if (argv[i].equals("--fep") || argv[i].startsWith("--fep=")) {
                return RigCommand.run(SPEC, argv, env, out, err, loader, body);
            }
        }
        err.println("secondary-fep: the following arguments are required: --fep");
        err.println(SPEC.usage());
        return RigCommand.EXIT_USAGE;
    }

    /**
     * {@code secondary_fep_cli}: the table, the selection, a route read and a trust over SSH, and, with a service id
     * and a C-Site login, the Commander Connect push.
     *
     * @param environ the environment, with the run's {@code CAT_CSITE_*} account merged in by the caller
     */
    public static boolean select(Rig rig, List<String> feps, Path petroapps, String serviceId,
            Map<String, String> environ, CommanderAccess access, Say say) {
        List<SecondaryFep> catalogue;
        List<SecondaryFep> chosen;
        try {
            catalogue = SecondaryFeps.loadCatalogue();
            chosen = SecondaryFeps.resolve(feps, catalogue);
        } catch (RuntimeException e) {
            say.err("secondary-fep: " + e.getMessage());
            return false;
        }
        Supplier<String> route = () -> {
            try {
                Transport transport = access.connectedTransport();
                try {
                    return SecondaryFeps.fleetRoute(transport, access.sshTimeout());
                } finally {
                    transport.close();
                }
            } catch (RuntimeException e) {
                return "";
            }
        };
        String service = serviceId == null ? "" : serviceId;
        SecondaryFeps.CloudSend cloud = chosen.stream().anyMatch(row -> !row.cloudProvider().isEmpty())
                ? cloudSender(rig, service, environ, access, say) : null;
        SecondaryFeps.Context context = new SecondaryFeps.Context(petroapps, rig.controller().host(),
                ToolsRoot.resolve(environ.get(ToolsRoot.ENV), null), cloud, service, route,
                SecondaryFeps.Trust.viaCommander(access, say));
        try (PdkShim shim = access.pdkShim()) {
            return SecondaryFeps.select(FepClient.of(shim), chosen, catalogue, context, say);
        } catch (RuntimeException e) {
            String why = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (chosen.isEmpty()) {
                say.out("WARNING secondary FEPs not checked: " + why);
                return true;
            }
            say.err("secondary-fep " + chosen.stream().map(SecondaryFep::name).collect(Collectors.joining(", "))
                    + " on " + rig.commander().host() + " failed: " + why);
            return false;
        }
    }

    /**
     * The Python's {@code _cloud_sender}: {@link SecondaryFeps#pushCloudProviders}' send through the C-Site client,
     * or null (said why) when no service id or no C-Site account is configured.
     */
    static SecondaryFeps.CloudSend cloudSender(Rig rig, String serviceId, Map<String, String> environ,
            CommanderAccess access, Say say) {
        if (serviceId.isEmpty()) {
            return null;
        }
        CSiteSettings settings;
        try {
            settings = CSiteSettings.fromEnvironment(environ, CommanderCloudConfig.forCommander(
                    rig.commander().host(), access::connectedTransport, access.sshTimeout()));
            settings.baseUrl(); // fail here, before anything is sent
        } catch (DeviceError e) {
            say.out("WARNING Commander Connect not reachable from CAT: " + e.getMessage());
            return null;
        }
        if (settings.username().isEmpty()) {
            return null;
        }
        return sender(CSiteClient.of(settings), serviceId);
    }

    /** {@code send} over {@code client}: JSON headers and the service id, no retry; a fault is status 0. */
    static SecondaryFeps.CloudSend sender(CSiteClient client, String serviceId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("X-ServiceId", serviceId);
        return (method, endpoint, body) -> {
            try {
                CSiteResponse answer = client.send(method, endpoint, headers, body, false);
                return new SecondaryFeps.Reply(answer.status(),
                        new String(answer.body(), StandardCharsets.UTF_8));
            } catch (DeviceError e) {
                return new SecondaryFeps.Reply(0, String.valueOf(e.getMessage()));
            }
        };
    }
}
