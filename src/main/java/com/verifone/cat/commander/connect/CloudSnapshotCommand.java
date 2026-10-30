package com.verifone.cat.commander.connect;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.CgiReplies;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.csite.CSiteClient;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettleContext;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLog;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLogException;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.rig.Rig;

/**
 * {@code cat-java cloud-snapshot capture|diff|restore --rig R --service-id ID [--suites DIR] [--dir DIR]}: the
 * Commander Connect config datasets the suites use, captured, compared or put back. The Python's
 * {@code petrotest cloud-snapshot} and {@code cloud_snapshot_cli} ({@code petrotest.cloud_snapshot}), over
 * {@link CloudSnapshot}.
 *
 * <p>{@code --dir} defaults to the committed golden, the Python checkout's {@code src/petrotest/cloud_golden}
 * ({@link #goldenDir}): the one golden both CATs restore to. {@code --suites} (capture) defaults to
 * {@code tests/suites}.
 *
 * <p>A restore marks the Commander's log first when it is onboarded ({@code _mark_commander}), and after accepted
 * writes waits for the Commander to receive and apply every dataset written ({@code _wait_applied}: {@link
 * CloudSettle#settle}, its budget the larger of {@link CloudSettleContext#APPLY_BUDGET} and {@link #PUSH_LAG} plus
 * {@link #SECONDS_PER_WRITE} a write). A restore that is not applied fails.
 *
 * <p>Deviation: the {@code FAIL} lines go to the error stream (the Python prints them to stdout), so step 4's row
 * carries the reason.
 *
 * <p>Exit codes: 0 done (capture, a diff with nothing different, a restore applied), 1 otherwise, 2 usage or a rig
 * that does not load. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CloudSnapshotCommand {

    /** The cloud pushes about one write every 2 s, about 100 s after the writes (2026-10-22). */
    public static final Duration SECONDS_PER_WRITE = Duration.ofSeconds(3);
    public static final Duration PUSH_LAG = Duration.ofSeconds(120);

    static final RigCommand.Spec SPEC = new RigCommand.Spec("cloud-snapshot",
            "usage: cat-java cloud-snapshot {capture,diff,restore} --rig <name|file.yaml> --service-id ID "
                    + "[--suites DIR] [--dir DIR]",
            Map.of("--service-id", "", "--suites", "tests/suites", "--dir", ""), Set.of("--service-id"), Set.of(),
            Set.of(), List.of("capture", "diff", "restore"));

    /** What a run needs from the rig: the cloud, and the Commander's log marked (null when not onboarded). */
    public interface Wiring {
        /** @throws DeviceError when C-Site cannot be set up for the rig */
        CloudSnapshot.Cloud cloud(Rig rig, String serviceId, Map<String, String> environ);

        /** {@code _mark_commander}: the log marked, or null (said why) when nothing will be pushed or it is unread. */
        CommanderLog markCommander(Rig rig, Map<String, String> environ, Say say);

        /** The settle's budgets and clock. */
        CloudSettle settle();
    }

    /** The production wiring: C-Site from the rig's Commander, its log over SSH. */
    public static final Wiring LIVE = new Wiring() {
        @Override
        public CloudSnapshot.Cloud cloud(Rig rig, String serviceId, Map<String, String> environ) {
            CSiteClient client = CSiteReset.LIVE.open(rig, environ);
            return CloudSnapshot.Cloud.live(client, serviceId);
        }

        @Override
        public CommanderLog markCommander(Rig rig, Map<String, String> environ, Say say) {
            CommanderAccess access = new CommanderAccess(rig, JschTransport::new, environ);
            return CloudSnapshotCommand.markCommander(() -> {
                try (PdkShim shim = access.pdkShim()) {
                    return shim.cloudConnectStatus();
                }
            }, () -> CommanderLogs.over(access.connectedTransport()), say);
        }

        @Override
        public CloudSettle settle() {
            return new CloudSettle(CloudSettleContext.of(null));
        }
    };

    private final RigCommand.Loader loader;
    private final Wiring wiring;

    /** The production command. */
    public CloudSnapshotCommand() {
        this(RigCommand.Loader.RIGS, LIVE);
    }

    /** A command over given seams; tests use this so no cloud or Commander is reached. */
    public CloudSnapshotCommand(RigCommand.Loader loader, Wiring wiring) {
        this.loader = loader;
        this.wiring = wiring;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, (rig, args, environ, say) -> {
            String dir = args.value("--dir");
            Path folder = dir == null || dir.isBlank() ? goldenDir(environ) : Path.of(dir);
            return RigCommand.exit(cli(args.action(), rig, args.value("--service-id"),
                    Path.of(args.value("--suites")), folder, environ, wiring, say));
        });
    }

    /** The committed golden every Commander's service is put back to: the Python checkout's. */
    public static Path goldenDir(Map<String, String> environ) {
        return ProvisionPaths.pythonRoot(environ).resolve("src").resolve("petrotest").resolve("cloud_golden");
    }

    /** {@code cloud_snapshot_cli}. */
    public static boolean cli(String action, Rig rig, String serviceId, Path suites, Path directory,
            Map<String, String> environ, Wiring wiring, Say say) {
        try {
            CloudSnapshot.Cloud cloud = wiring.cloud(rig, serviceId, environ);
            if (action.equals("capture")) {
                JsonNode manifest = CloudSnapshot.capture(cloud, CloudSnapshot.suiteDatasets(suites), directory);
                List<String> kinds = new ArrayList<>();
                manifest.path("datasets").forEach(entry -> kinds.add(entry.path("kind").asText()));
                List<String> counts = new ArrayList<>();
                for (String kind : List.of("list", "setting", "unreadable")) {
                    counts.add(kinds.stream().filter(kind::equals).count() + " " + kind);
                }
                say.out("cloud-snapshot: captured " + kinds.size() + " datasets into " + directory + ": "
                        + String.join(", ", counts));
                return true;
            }
            Optional<JsonNode> loaded = CloudSnapshot.load(directory);
            if (loaded.isEmpty()) {
                say.err("cloud-snapshot: FAIL no snapshot in " + directory);
                return false;
            }
            JsonNode manifest = loaded.get();
            if (action.equals("diff")) {
                Map<String, List<String>> differs = CloudSnapshot.diff(cloud, directory, manifest);
                differs.forEach((name, lines) -> say.out("DIFFERS " + name + ": "
                        + String.join(", ", lines.subList(0, Math.min(12, lines.size())))));
                say.out("cloud-snapshot: " + differs.size() + " of " + manifest.path("datasets").size()
                        + " datasets differ");
                return differs.isEmpty();
            }
            CommanderLog log = wiring.markCommander(rig, environ, say);
            try {
                Map<String, Integer> wrote = new java.util.LinkedHashMap<>();
                Map<String, List<String>> left = CloudSnapshot.restore(cloud, directory, manifest, say::out, wrote);
                left.forEach((name, lines) -> say.out("cloud-snapshot: WARN " + name + " still differs: "
                        + String.join(", ", lines.subList(0, Math.min(12, lines.size())))));
                int writes = wrote.values().stream().mapToInt(Integer::intValue).sum();
                say.out("cloud-snapshot: restored service " + serviceId + " to the golden ("
                        + text(manifest.get("service_id")) + ", " + text(manifest.get("captured_at")) + "); "
                        + writes + " write(s) to " + wrote.size() + " dataset(s), " + left.size() + " still differ");
                return wrote.isEmpty() || waitApplied(log, wrote, wiring.settle(), say);
            } finally {
                if (log != null) {
                    log.close();
                }
            }
        } catch (DeviceError e) {
            say.err("cloud-snapshot: FAIL " + action + " not done: " + e.getMessage());
            return false;
        }
    }

    /** Python's {@code str()} of a manifest value: {@code None} for an absent one. */
    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "None" : node.asText();
    }

    /**
     * {@code _mark_commander}: the Commander's log, marked before a restore writes, or null when it is not onboarded
     * (nothing will be pushed to it) or cannot be read.
     */
    public static CommanderLog markCommander(Supplier<CgiReplies.CloudConnectStatus> status,
            Supplier<CommanderLog> logs, Say say) {
        boolean registered;
        try {
            registered = status.get().registered();
        } catch (RuntimeException e) {
            say.out("cloud-snapshot: Commander Connect status unread: " + e.getMessage());
            return null;
        }
        if (!registered) {
            return null;
        }
        CommanderLog log;
        try {
            log = logs.get();
        } catch (RuntimeException e) {
            say.out("cloud-snapshot: the Commander's log unread: " + e.getMessage());
            return null;
        }
        try {
            log.mark();
        } catch (RuntimeException e) {
            log.close();
            say.out("cloud-snapshot: the Commander's log unread: " + e.getMessage());
            return null;
        }
        return log;
    }

    /**
     * {@code _wait_applied}: after a restore's accepted writes, the Commander has received every dataset written and
     * applied everything the cloud pushed. No log (not onboarded) waits for nothing.
     */
    static boolean waitApplied(CommanderLog log, Map<String, Integer> wrote, CloudSettle settle, Say say) {
        if (log == null) {
            return true;
        }
        int writes = wrote.values().stream().mapToInt(Integer::intValue).sum();
        Duration budget = PUSH_LAG.plus(SECONDS_PER_WRITE.multipliedBy(writes));
        if (budget.compareTo(CloudSettleContext.APPLY_BUDGET) < 0) {
            budget = CloudSettleContext.APPLY_BUDGET;
        }
        CloudSettle.Settled done;
        try {
            done = settle.settle(log, "the Commander to apply the golden", budget, wrote.keySet());
        } catch (CommanderLogException e) {
            say.err("cloud-snapshot: FAIL the Commander's log could not be read while waiting: " + e.getMessage());
            return false;
        }
        if (!done.done()) {
            say.err("cloud-snapshot: FAIL the Commander has not applied the restored " + sorted(wrote.keySet())
                    + ": received " + orElse(sorted(done.received()), "nothing") + ", not applied "
                    + orElse(sorted(done.outstanding()), "none"));
            return false;
        }
        say.out("cloud-snapshot: the Commander applied " + sorted(done.received()));
        return true;
    }

    private static String sorted(Set<String> names) {
        return String.join(", ", new TreeSet<>(names));
    }

    private static String orElse(String text, String fallback) {
        return text.isEmpty() ? fallback : text;
    }
}
