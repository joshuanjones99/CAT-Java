package com.verifone.cat.commander.snapshot;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.commander.Pauser;
import com.verifone.cat.devices.commander.CgiReplies.CgiFunction;
import com.verifone.cat.pats.PatsCaseLoader;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java commander-snapshot ACTION ...}: capture, diff and restore a golden snapshot of the Commander's
 * configuration, and say which plan-imported areas it can restore. The Python's {@code petrotest commander-snapshot}
 * ({@code commander_snapshot.add_parser} and {@code run}), with the same actions, flags and exit codes:
 *
 * <ul>
 * <li>{@code areas --rig R [--petroapps P]}: the funcList's areas and their policy.</li>
 * <li>{@code capture --rig R [--base-level L] [--dir D] [--petroapps P] [--twice [S]] [--force] [--if-missing]
 * [--build-label X]}: exit 0 published (or, with {@code --if-missing}, this build already has a usable one), 1 not
 * published, 2 no base level or a snapshot already there.</li>
 * <li>{@code diff --rig R [--base-level L] [--dir D] [--all] [--merge]}: exit 1 when any area is not the same.</li>
 * <li>{@code restore --rig R [--base-level L] [--dir D] [--area A]... [--merge]}: exit 0 only when every area
 * matches afterwards.</li>
 * <li>{@code coverage [--base-level L] [--dir D] [--plans K...] [--petroapps P] [--all] [--suites S]}: exit 1 when a
 * plan imports an area the snapshot cannot restore, or a case cannot be read. {@code --suites} (Java only) names
 * the {@code tests/suites} whose {@code .feature} files are read first, the Python CAT's by default. A case with no
 * {@code .feature} is read from the petroapps working tree; {@code --rev} and {@code --dist} are accepted and
 * ignored (the git-object case store is not ported).</li>
 * </ul>
 *
 * <p>Main does not dispatch to it yet; {@link #run(String[])} takes the arguments after the program name, the
 * {@code commander-snapshot} word optional.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderSnapshotCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    /** The Python CAT's suites, whose {@code .feature} files the coverage reads first. */
    public static final Path DEFAULT_SUITES = Path.of("C:\\gitrepos\\CAT", "tests", "suites");

    static final String USAGE = String.join("\n",
            "usage: cat-java commander-snapshot ACTION ...",
            "",
            "  areas    --rig R [--petroapps P]",
            "  capture  --rig R [--base-level L] [--dir D] [--petroapps P] [--twice [SECONDS]] [--force]",
            "           [--if-missing] [--build-label X]",
            "  diff     --rig R [--base-level L] [--dir D] [--all] [--merge]",
            "  restore  --rig R [--base-level L] [--dir D] [--area A]... [--merge]",
            "  coverage [--base-level L] [--dir D] [--plans K...] [--petroapps P] [--all] [--suites S]",
            "",
            "Snapshots live under $CAT_SNAPSHOTS_ROOT (default C:\\CICD\\Snapshots), never in this repository.",
            "Procedure and policy: the Python CAT's docs/commander-snapshot.md.");

    private static final Map<String, Set<String>> FLAGS = Map.of(
            "areas", Set.of("--rig", "--petroapps"),
            "capture", Set.of("--rig", "--base-level", "--dir", "--petroapps", "--twice", "--force", "--if-missing",
                    "--build-label"),
            "diff", Set.of("--rig", "--base-level", "--dir", "--all", "--merge"),
            "restore", Set.of("--rig", "--base-level", "--dir", "--area", "--merge"),
            "coverage", Set.of("--base-level", "--dir", "--plans", "--petroapps", "--rev", "--dist", "--all",
                    "--suites"));
    private static final Set<String> SWITCHES = Set.of("--force", "--if-missing", "--all", "--merge");

    /** The Commander as one action reaches it: its CGILink client and its {@code suiteinfo.dat}. */
    public record Connection(CommanderConfig client, Supplier<Map<String, String>> suiteinfo, String host) {}

    /** Opens the {@link Connection} for a rig: the real Commander in production, a fake in tests. */
    @FunctionalInterface
    public interface Connector {
        Connection open(Rig rig, Map<String, String> env);
    }

    private final Connector connector;
    private final Pauser pauser;
    private final Function<String, Optional<List<String>>> caseLists;

    /** The production command. */
    public CommanderSnapshotCommand() {
        this(CommanderSnapshotCommand::connect, Pauser.REAL, CommanderSnapshotCommand::caseList);
    }

    /**
     * A command with its own seams; tests use this so no Commander is reached.
     *
     * @param caseLists plan key -> its case keys, or empty when no case list is cached or shipped
     */
    public CommanderSnapshotCommand(Connector connector, Pauser pauser,
            Function<String, Optional<List<String>>> caseLists) {
        this.connector = connector;
        this.pauser = pauser;
        this.caseLists = caseLists;
    }

    private static Connection connect(Rig rig, Map<String, String> env) {
        CommanderAccess access = new CommanderAccess(rig, JschTransport::new, env);
        return new Connection(CommanderConfig.of(access.pdkShim()), () -> SnapshotRestore.suiteinfo(access),
                rig.commander().host());
    }

    /** {@code ~/.cat/java/cases-<plan>.json}, else the Python CAT's {@code ~/.cat}, else the shipped seed. */
    private static Optional<List<String>> caseList(String plan) {
        Path home = Path.of(System.getProperty("user.home"), ".cat");
        Optional<Path> file = Collect.defaultCaseFile(plan, home.resolve("java"), home);
        if (file.isPresent()) {
            return Optional.of(Collect.readCaseList(file.get()));
        }
        List<String> seeded = new SeedPlanSource().cases(plan).stream().map(PlanCase::key).toList();
        return seeded.isEmpty() ? Optional.empty() : Optional.of(seeded);
    }

    /** A SystemExit: a message for stderr, and the exit code. */
    private static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Exit(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** The parsed flags of one action. */
    static final class Args {
        String action;
        String rig;
        String baseLevel = "";
        String dir = "";
        String petroapps = "";
        Duration twice;
        boolean force;
        boolean ifMissing;
        String buildLabel = "";
        boolean all;
        boolean merge;
        List<String> areas;
        final List<String> plans = new ArrayList<>();
        Path suites = DEFAULT_SUITES;
    }

    /** Runs the command with the process environment and standard streams. */
    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    /** Parses {@code argv}, runs the action, and returns the exit code. */
    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        Args args;
        try {
            args = parse(argv);
        } catch (Exit e) {
            if (e.code == EXIT_OK) {
                out.println(USAGE);
            } else {
                err.println(e.getMessage());
                err.println(USAGE);
            }
            return e.code;
        }
        try {
            return dispatch(args, env, out, err);
        } catch (Exit e) {
            err.println(e.getMessage());
            return e.code;
        } catch (RuntimeException e) {
            err.println("commander-snapshot " + args.action + " FAILED (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")");
            return EXIT_FAILED;
        } catch (IOException e) {
            err.println("commander-snapshot " + args.action + " FAILED (" + e.getMessage() + ")");
            return EXIT_FAILED;
        }
    }

    static Args parse(String[] argv) {
        List<String> rest = new ArrayList<>(List.of(argv));
        if (!rest.isEmpty() && rest.get(0).equals("commander-snapshot")) {
            rest.remove(0);
        }
        if (rest.isEmpty() || rest.get(0).equals("--help") || rest.get(0).equals("-h")) {
            throw new Exit(rest.isEmpty() ? EXIT_USAGE : EXIT_OK, "an ACTION is required");
        }
        Args args = new Args();
        args.action = rest.remove(0);
        Set<String> allowed = FLAGS.get(args.action);
        if (allowed == null) {
            throw new Exit(EXIT_USAGE, "unknown action: " + args.action);
        }
        for (int i = 0; i < rest.size(); i++) {
            String flag = rest.get(i);
            if (flag.equals("--help") || flag.equals("-h")) {
                throw new Exit(EXIT_OK, "");
            }
            if (!allowed.contains(flag)) {
                throw new Exit(EXIT_USAGE, "unrecognized argument for " + args.action + ": " + flag);
            }
            if (SWITCHES.contains(flag)) {
                switch (flag) {
                    case "--force" -> args.force = true;
                    case "--if-missing" -> args.ifMissing = true;
                    case "--all" -> args.all = true;
                    default -> args.merge = true;
                }
                continue;
            }
            if (flag.equals("--twice")) {
                double seconds = 5.0;
                if (i + 1 < rest.size() && !rest.get(i + 1).startsWith("--")) {
                    try {
                        seconds = Double.parseDouble(rest.get(i + 1));
                        i++;
                    } catch (NumberFormatException e) {
                        throw new Exit(EXIT_USAGE, "--twice: not a number of seconds: " + rest.get(i + 1));
                    }
                }
                args.twice = Duration.ofMillis(Math.round(seconds * 1000));
                continue;
            }
            if (flag.equals("--plans")) {
                while (i + 1 < rest.size() && !rest.get(i + 1).startsWith("--")) {
                    args.plans.add(rest.get(++i));
                }
                continue;
            }
            if (i + 1 >= rest.size()) {
                throw new Exit(EXIT_USAGE, flag + " needs a value");
            }
            String value = rest.get(++i);
            switch (flag) {
                case "--rig" -> args.rig = value;
                case "--base-level" -> args.baseLevel = value;
                case "--dir" -> args.dir = value;
                case "--petroapps" -> args.petroapps = value;
                case "--build-label" -> args.buildLabel = value;
                case "--suites" -> args.suites = Path.of(value);
                case "--area" -> {
                    if (args.areas == null) {
                        args.areas = new ArrayList<>();
                    }
                    args.areas.add(value);
                }
                default -> { } // --rev, --dist: accepted, not used (see the class comment)
            }
        }
        if (allowed.contains("--rig") && args.rig == null) {
            throw new Exit(EXIT_USAGE, "--rig is required");
        }
        return args;
    }

    private int dispatch(Args args, Map<String, String> env, PrintStream out, PrintStream err) throws IOException {
        Path root = Snapshots.snapshotsRoot(env);
        if (args.action.equals("coverage")) {
            return coverage(args, env, root, out, err);
        }
        Rig rig;
        try {
            rig = RigLoader.load(args.rig, RigLoader.rigsDir(env));
        } catch (RuntimeException e) {
            err.println("cannot load rig " + args.rig + ": " + e.getMessage());
            return EXIT_USAGE;
        }
        Connection connection = connector.open(rig, env);
        CommanderConfig client = connection.client();
        switch (args.action) {
            case "areas" -> {
                List<CgiFunction> functions = client.functionList();
                List<String> names = Areas.areasOf(functions, configurationsRoot(args.petroapps, env));
                out.println(functions.size() + " commands, " + names.size() + " areas");
                for (String name : names) {
                    String why = Areas.exclusion(name);
                    out.println(String.format(Locale.ROOT, "  %-40s %s", name, why == null ? "restorable" : why));
                }
                return EXIT_OK;
            }
            case "capture" -> {
                return capture(args, env, root, connection, out, err);
            }
            case "diff" -> {
                Resolved resolved = resolveDir(args, connection, root, out);
                List<Restore.AreaDiff> results = Restore.diff(client, resolved.directory(), null, args.all,
                        resolved.merge(), Rules.DEFAULT);
                int differing = 0;
                for (Restore.AreaDiff d : results) {
                    if (!d.status().equals("same")) {
                        differing++;
                        String mark = d.excluded() != null ? " (excluded)" : "";
                        out.println(d.status().toUpperCase(Locale.ROOT) + " " + d.area() + mark + "\n  "
                                + String.join("\n  ", d.detail()));
                    }
                }
                out.println(results.size() + " areas compared with " + resolved.directory() + ", " + differing
                        + " not the same");
                return differing > 0 ? EXIT_FAILED : EXIT_OK;
            }
            default -> { // restore
                Resolved resolved = resolveDir(args, connection, root, out);
                Restore.RestoreReport report = Restore.restore(client, resolved.directory(), args.areas,
                        out::println, resolved.merge(), Rules.DEFAULT);
                return report.ok() ? EXIT_OK : EXIT_FAILED;
            }
        }
    }

    /** {@code <petroapps>/AutomationTests/configurations}; the default checkout as step 4 resolves it. */
    static Path configurationsRoot(String petroapps, Map<String, String> env) {
        if (petroapps == null || petroapps.isEmpty()) {
            return CommanderInit.baseConfigFolder(env).getParent();
        }
        return Path.of(petroapps).resolve("AutomationTests").resolve("configurations");
    }

    private record Resolved(Path directory, boolean merge) {}

    /**
     * The snapshot folder, and whether to merge-restore it. {@code --dir} is taken as named (merge only with
     * {@code --merge}). With a rig, the Commander's build chooses, under {@code --base-level} when given. Without one
     * (coverage), the newest snapshot of {@code --base-level}, else the only one there is.
     */
    private Resolved resolveDir(Args args, Connection connection, Path root, PrintStream out) {
        if (!args.dir.isEmpty()) {
            return new Resolved(Path.of(args.dir), args.merge);
        }
        if (connection != null) {
            Map<String, String> suite = connection.suiteinfo().get();
            String level = !args.baseLevel.isEmpty() ? args.baseLevel : suite.get("Base_Level");
            Path base = Snapshots.snapshotDir(level, root);
            Snapshots.Choice choice = Snapshots.chooseSnapshot(base, suite);
            if (choice == null) {
                throw new Exit(EXIT_FAILED, "no snapshot this build may use under " + base);
            }
            out.println("snapshot " + choice.directory() + ": " + choice.note());
            return new Resolved(choice.directory(), args.merge || !choice.exact());
        }
        if (!args.baseLevel.isEmpty()) {
            Path base = Snapshots.snapshotDir(args.baseLevel, root);
            List<Snapshots.Found> found = Snapshots.baseSnapshots(base);
            if (found.isEmpty()) {
                return new Resolved(base, args.merge);
            }
            Snapshots.Found newest = found.stream().max(Comparator.comparing((Snapshots.Found f) ->
                    Snapshots.builtAt(Snapshots.snapshotBuild(f.manifest())).orElse(Instant.MIN))).orElseThrow();
            return new Resolved(newest.directory(), args.merge);
        }
        List<Path> all = Snapshots.allSnapshots(root);
        if (all.size() == 1) {
            return new Resolved(all.get(0), args.merge);
        }
        throw new Exit(EXIT_FAILED, all.isEmpty() ? "no snapshot under " + root
                : all.size() + " snapshots under " + root + ": name one with --base-level or --dir");
    }

    private int capture(Args args, Map<String, String> env, Path root, Connection connection, PrintStream out,
            PrintStream err) throws IOException {
        CommanderConfig client = connection.client();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("commander", connection.host());
        Map<String, String> suite = Map.of();
        try {
            suite = connection.suiteinfo().get();
            info.put("suiteinfo", suite);
            info.put("base_level", suite.get("Base_Level"));
        } catch (RuntimeException e) { // recorded; --base-level can stand in for it
            info.put("suiteinfo_error", String.valueOf(e.getMessage()));
        }
        if (!args.baseLevel.isEmpty()) {
            info.put("base_level", args.baseLevel);
        }
        String level = info.get("base_level") instanceof String s ? s : "";
        if (level.isEmpty() && args.dir.isEmpty()) {
            err.println("no Base_Level (" + info.get("suiteinfo_error") + "): pass --base-level");
            return EXIT_USAGE;
        }
        try { // the build as CGILink reports it, for the record (its element names are unread)
            String appInfo = client.sendCommand("vAppInfo", "");
            info.put("vAppInfo", appInfo.substring(0, Math.min(4000, appInfo.length())));
        } catch (RuntimeException e) {
            info.put("vAppInfo_error", String.valueOf(e.getMessage()));
        }
        Path base = args.petroapps.isEmpty() ? CommanderInit.baseConfigFolder(env)
                : configurationsRoot(args.petroapps, env).resolve("base");
        info.put("petroapps_base", base.toString());
        info.put("petroapps_base_digest", Capture.folderDigest(base));
        if (!args.buildLabel.isEmpty()) {
            info.put("build_label", args.buildLabel);
        }
        Path directory;
        List<Snapshots.Found> mine = new ArrayList<>();
        if (!args.dir.isEmpty()) {
            directory = Path.of(args.dir);
            Map<String, Object> existing = Snapshots.loadManifest(directory);
            if (existing != null) {
                mine.add(new Snapshots.Found(directory, existing));
            }
        } else {
            // One snapshot per build (rig owner, 2026-10-20). A Commander whose suiteinfo has no Commit_Hash cannot
            // name its build: the base level's own folder, as before.
            try {
                directory = Snapshots.buildDir(level, suite, root);
            } catch (IllegalArgumentException e) {
                directory = Snapshots.snapshotDir(level, root);
            }
            String commit = suite.getOrDefault(Snapshots.BUILD_HASH, "");
            for (Snapshots.Found f : Snapshots.baseSnapshots(Snapshots.snapshotDir(level, root))) {
                Object theirs = Snapshots.snapshotBuild(f.manifest()).get(Snapshots.BUILD_HASH);
                if (f.directory().equals(directory) || (!commit.isEmpty() && commit.equals(theirs))) {
                    mine.add(f);
                }
            }
        }
        if (args.ifMissing) {
            for (Snapshots.Found f : mine) {
                if (Capture.snapshotErrors(f.directory(), f.manifest()).isEmpty()) {
                    out.println("a snapshot of this build (" + Snapshots.buildName(f.manifest()) + ") exists: "
                            + f.directory());
                    return EXIT_OK;
                }
            }
        }
        List<CgiFunction> functions = client.functionList();
        List<String> names = Areas.areasOf(functions, configurationsRoot(args.petroapps, env));
        out.println(functions.size() + " commands in the funcList, " + names.size() + " areas -> " + directory);
        Map<String, Object> manifest;
        try {
            manifest = Capture.capture(client, names, directory, new Capture.Options().functions(functions)
                    .info(info).twice(args.twice).overwrite(args.force || args.ifMissing).say(out::println)
                    .pauser(pauser));
        } catch (FileAlreadyExistsException e) {
            err.println(e.getMessage() + " (--force)");
            return EXIT_USAGE;
        }
        return manifest.equals(Snapshots.loadManifest(directory)) ? EXIT_OK : EXIT_FAILED;
    }

    private int coverage(Args args, Map<String, String> env, Path root, PrintStream out, PrintStream err) {
        Path directory = !args.dir.isEmpty() || !args.baseLevel.isEmpty()
                ? resolveDir(args, null, root, out).directory() : anySnapshot(root);
        Path configurations = configurationsRoot(args.petroapps, env);
        Map<String, List<String>> plans = new LinkedHashMap<>();
        for (String plan : args.plans) {
            Optional<List<String>> keys = caseLists.apply(plan);
            if (keys.isEmpty()) {
                err.println("no case list for " + plan + ": fetch it in CAT first");
                return EXIT_USAGE;
            }
            plans.put(plan, keys.get());
        }
        Function<String, List<String>> reader = Coverage.caseFolderReader(args.suites,
                new WorkingTreeCases(configurations.getParent()));
        Coverage.Report report = Coverage.coverage(directory, configurations, plans, reader);
        out.println("snapshot " + directory + (report.hasSnapshot() ? "" : " (none there)"));
        for (Coverage.Row row : report.rows()) {
            if (!(args.all || !row.cases().isEmpty() || args.plans.isEmpty())) {
                continue;
            }
            String mark = row.ok() ? "ok  " : "GAP ";
            String who = !row.cases().isEmpty() ? row.cases().size() + " case(s)" : row.folders().size() + " folder(s)";
            out.println(String.format(Locale.ROOT, "%s%-40s %-14s %s", mark, row.area(), who, row.status()));
            if (!row.cases().isEmpty() && !row.ok()) {
                List<String> cases = row.cases();
                out.println("      imported by: " + String.join(", ", cases.subList(0, Math.min(8, cases.size())))
                        + (cases.size() > 8 ? " and " + (cases.size() - 8) + " more" : ""));
            }
        }
        report.unresolved().forEach((label, why) -> out.println("UNREAD " + label + ": " + why));
        report.missingFolders().forEach((label, folders) -> out.println("NO FOLDER " + label + ": "
                + String.join(", ", folders) + " not under " + configurations));
        if (!args.plans.isEmpty()) {
            out.println(report.blocking().size() + " area(s) the plans import that the snapshot cannot restore");
            return report.blocking().isEmpty() && report.unresolved().isEmpty() ? EXIT_OK : EXIT_FAILED;
        }
        return EXIT_OK;
    }

    /**
     * The only snapshot under the root; a path holding none when there is none, so the report says so; refused when
     * there are several to choose from.
     */
    private static Path anySnapshot(Path root) {
        List<Path> found = Snapshots.allSnapshots(root);
        if (found.size() > 1) {
            throw new Exit(EXIT_FAILED, found.size() + " snapshots under " + root
                    + ": name one with --base-level or --dir");
        }
        return found.isEmpty() ? root.resolve("(none)") : found.get(0);
    }

    /**
     * A case's IMPORT_CONFIGURATION folders from the petroapps working tree ({@code AutomationTests}), where the
     * Python reads git objects at {@code --rev}: the first {@code <key>-TestCase.xml} or {@code .scenario} found.
     */
    static final class WorkingTreeCases implements Function<String, List<String>> {
        private final Path automationTests;
        private Map<String, Path> index;

        WorkingTreeCases(Path automationTests) {
            this.automationTests = automationTests;
        }

        @Override
        public List<String> apply(String key) {
            Path file = index().get(key);
            if (file == null) {
                throw new IllegalStateException(key + ": no -TestCase.xml or .scenario under " + automationTests);
            }
            try {
                List<String> folders = new ArrayList<>();
                for (RunnableAction action : PatsCaseLoader.load(file).actions()) {
                    if (action.name().equals("IMPORT_CONFIGURATION")) {
                        folders.add(action.get("SELECT_FOLDER"));
                    }
                }
                return folders;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private synchronized Map<String, Path> index() {
            if (index != null) {
                return index;
            }
            Map<String, Path> found = new LinkedHashMap<>();
            if (automationTests != null && Files.isDirectory(automationTests)) {
                try (Stream<Path> walk = Files.walk(automationTests)) {
                    walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                        String name = p.getFileName().toString();
                        for (String suffix : List.of("-TestCase.xml", "-TestCase.scenario")) {
                            if (name.endsWith(suffix)) {
                                found.putIfAbsent(name.substring(0, name.length() - suffix.length()), p);
                            }
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            index = found;
            return index;
        }
    }
}
