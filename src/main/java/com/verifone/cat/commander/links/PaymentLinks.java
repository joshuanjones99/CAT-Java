package com.verifone.cat.commander.links;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * {@code petrotest payment-links --rig <rig>}: {@link CommanderLinks#ensurePaymentLinks} for real. A port of
 * {@code petrotest.commander_links.payment_links_cli}.
 *
 * <p>Two rounds, not one: on 2026-09-22 the first import and restart left PMC on the production host, and the same
 * call, repeated, brought the link up. Each round restarts PMC at most once. POP 1 is checked as configuration
 * (it is not a held socket), and the Buypass FEP's protocol is checked too ({@link CommanderLinks#FEP_LINK_FIELDS}).
 *
 * <p>Step 4's caller is not ported: the Python calls this from the Commander configuration launch step, before
 * {@code emv-keys}. {@code TODO(launch step 4): call PaymentLinks.run before the EMV keys, as the Python does}. This
 * class does not edit the launch steps.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PaymentLinks {

    /** The config command the drift check reads back. */
    static final String FEP_COMMAND = "fepcfg&fep=buypass";

    /** The rounds. */
    static final int ROUNDS = 2;

    private PaymentLinks() {}

    /** The CGILink calls the flow makes: the base-style import and the FEP's read-back. */
    public interface ConfigPort {

        /** Imports every file of {@code folder}; the result is keyed by command. */
        Map<String, String> importConfigurationFolder(Path folder);

        /** One configuration read, by command name. */
        String viewConfiguration(String command);

        /** The real client: CGILink through {@link PdkShim}. */
        static ConfigPort of(PdkShim shim) {
            return new ConfigPort() {
                @Override
                public Map<String, String> importConfigurationFolder(Path folder) {
                    return shim.importConfigurationFolder(folder);
                }

                @Override
                public String viewConfiguration(String command) {
                    return shim.viewConfiguration(command);
                }
            };
        }
    }

    /**
     * Makes the Commander's payment links up, as the Python's {@code payment_links_cli}.
     *
     * @param petroappsRoot the petroapps checkout whose {@code AutomationTests/configurations} holds the config
     * @param commander     a connected transport to the Commander, closed by this call
     * @param pauser        the bring-up's waits
     * @return whether the links were up, and POP 1 and the FEP config read back, after the last round
     */
    public static boolean run(Rig rig, Path petroappsRoot, ConfigPort client, Supplier<Transport> commander,
            Pauser pauser, Say say) {
        Path simulator = CommanderLinks.simulatorConfigFolder(petroappsRoot);
        if (!Files.isDirectory(simulator)) {
            say.err("payment-links: " + simulator + " is not a directory");
            return false;
        }
        Path staging;
        Path folder;
        List<String> files;
        try {
            staging = Files.createTempDirectory("cat-payment-config-");
            folder = CommanderLinks.stagePaymentConfig(petroappsRoot, rig, staging.resolve("payment-config"));
            try (Stream<Path> listed = Files.list(folder)) {
                files = listed.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted()
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            say.err("payment-links: cannot stage the payment config: " + e.getMessage());
            return false;
        }
        String wantedFep;
        try {
            wantedFep = java.nio.file.Files.readString(folder.resolve("fepcfg&fep=buypass.xml"));
        } catch (IOException e) {
            deleteQuietly(staging);
            say.err("payment-links: " + e.getMessage());
            return false;
        }

        Runnable importConfig = () -> {
            // PATS's import skips a file that keeps faulting; for the two files behind the payment link a skip
            // is the failure, so say so here.
            say.out("importing " + folder.getFileName() + " (" + files.size() + " files)");
            Map<String, String> applied = client.importConfigurationFolder(folder);
            List<String> missing = new ArrayList<>();
            for (String name : files) {
                if (!applied.containsKey("u" + name.substring(0, Math.max(0, name.length() - 4)))) {
                    missing.add(name);
                }
            }
            if (!missing.isEmpty()) {
                throw new DeviceError(folder.getFileName() + ": not applied: " + String.join(", ", missing));
            }
        };

        Transport transport;
        try {
            transport = commander.get();
        } catch (RuntimeException e) {
            deleteQuietly(staging);
            say.err("payment-links on " + rig.commander().host() + " failed: " + e.getMessage());
            return false;
        }
        LinkPlan plan = null;
        List<String> drifted = new ArrayList<>();
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                String want = CommanderLinks.pinpadHost(rig);
                Optional<String> have = CommanderLinks.popAddress(transport, CommanderLinks.COMMAND_TIMEOUT);
                boolean wrongPop = !Objects.equals(have.orElse(null), want);
                say.out("POP 1 -> " + have.orElse("unreadable") + "; this rig's pinpad is at " + want);
                List<String> drift = fepDrift(client, wantedFep, say);
                for (String d : drift) {
                    if (!drifted.contains(d)) {
                        drifted.add(d);
                    }
                }
                try {
                    plan = CommanderLinks.ensurePaymentLinks(CommanderLinks.paymentLinks(rig), transport,
                            importConfig, CommanderLinks.RESTART_PMC, pauser, wrongPop || !drift.isEmpty());
                } catch (RuntimeException e) {
                    say.out("payment-links round " + round + ": " + e.getMessage());
                    continue;
                }
                for (String note : plan.notes()) {
                    say.out(note);
                }
                say.out(plan.summary());
                boolean popRight = Objects.equals(
                        CommanderLinks.popAddress(transport, CommanderLinks.COMMAND_TIMEOUT).orElse(null), want);
                if (plan.missing().isEmpty() && popRight && fepDrift(client, wantedFep, say).isEmpty()) {
                    if (!drifted.isEmpty()) {
                        say.out(CommanderLinks.FEP_DRIFT_FIXED + " (" + String.join("; ", drifted) + ")");
                    }
                    return true;
                }
                say.out("payment-links round " + round + ": still missing; trying once more");
            }
        } finally {
            transport.close();
            deleteQuietly(staging);
        }
        String summary = plan == null ? "no round completed" : plan.summary();
        say.err("payment-links: still missing after two rounds: " + summary);
        return false;
    }

    /**
     * The Buypass FEP config's drift from the simulator's. Unreadable is not drift: the link check stands on its own.
     */
    static List<String> fepDrift(ConfigPort client, String wantedFep, Say say) {
        String live;
        try {
            live = client.viewConfiguration(FEP_COMMAND);
        } catch (DeviceError e) {
            say.out("Buypass FEP config: unreadable (" + e.getMessage() + ")");
            return List.of();
        }
        List<String> drift = CommanderLinks.fepConfigDrift(live, wantedFep);
        say.out("Buypass FEP config: " + (drift.isEmpty() ? "matches the simulator" : String.join("; ", drift)));
        return drift;
    }

    private static void deleteQuietly(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a staging copy that will not delete is left for the OS temp cleaner
                }
            });
        } catch (IOException ignored) {
            // nothing staged, or already gone
        }
    }
}
