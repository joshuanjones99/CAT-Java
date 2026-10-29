package com.verifone.cat.build;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@code cat-java builds [--branch <b> [--build <n>] [--dist <dist>] [--limit N]]}: Artifactory's nightly branches,
 * a branch's builds newest first, or what one build holds. The Python's {@code petrotest builds}
 * ({@code petrotest.build.cli.builds_cli}).
 *
 * <ul>
 *   <li>No flags: every branch, Solutions first ({@link BuildCatalogue#listBranches}).</li>
 *   <li>{@code --branch}: its builds, newest first by date, {@code <build>  <yyyy-MM-dd HH:mm> UTC}, at most
 *       {@code --limit} (30).</li>
 *   <li>{@code --branch --dist}: only the builds that carry the dist, each with the folder(s) it has it in.</li>
 *   <li>{@code --branch --build}: what the build holds ({@link BuildContents#describe}), for {@code --dist} or each of
 *       its dists, then its {@code simulators/}, {@code automation/} and other folders.</li>
 * </ul>
 * A failure is one {@code ERROR cat-java builds: <why>} line, exit 1; {@code --build} without {@code --branch} is a
 * usage error, exit 2.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port): tested against {@code FakeArtifactory}.
 */
public final class BuildsCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    /** The Python's {@code ArtifactoryClient} default timeout, for a listing. */
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_LIMIT = 30;

    static final String USAGE = String.join("\n",
            "usage: cat-java builds [--branch <branch>] [--build <n>] [--dist <dist>] [--limit N]",
            "",
            "List Artifactory's nightly branches, a branch's builds, or one build's contents.",
            "  --branch  list this branch's builds, newest first",
            "  --build   with --branch: show what this build holds",
            "  --dist    with --branch: only the builds that carry this dist",
            "  --limit   at most this many builds; 30");

    private final BuildCommand.Clients clients;

    public BuildsCommand() {
        this(BuildCommand.Clients.REAL);
    }

    public BuildsCommand(BuildCommand.Clients clients) {
        this.clients = clients;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "builds".equals(args.get(0))) {
            args.remove(0);
        }
        String branch = null;
        String build = null;
        String dist = null;
        int limit = DEFAULT_LIMIT;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--help".equals(arg) || "-h".equals(arg)) {
                out.println(USAGE);
                return EXIT_OK;
            }
            if (!List.of("--branch", "--build", "--dist", "--limit").contains(arg)) {
                return usage(err, "unrecognized arguments: " + arg);
            }
            if (i + 1 >= args.size()) {
                return usage(err, "argument " + arg + ": expected one argument");
            }
            String value = args.get(++i);
            switch (arg) {
                case "--branch" -> branch = value;
                case "--build" -> build = value;
                case "--dist" -> dist = value;
                default -> {
                    try {
                        limit = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        return usage(err, "argument --limit: invalid int value: '" + value + "'");
                    }
                }
            }
        }
        if (build != null && !build.isEmpty() && (branch == null || branch.isEmpty())) {
            err.println("cat-java builds: --build needs --branch");
            return EXIT_USAGE;
        }
        return list(branch, build, dist, limit, env, line -> {
            out.println(line);
            out.flush();
        });
    }

    /** The listing, every line to {@code out}: the Python's {@code builds_cli}. */
    public int list(String branch, String build, String dist, int limit, Map<String, String> env,
            Consumer<String> out) {
        try {
            BuildCatalogue catalogue = new BuildCatalogue(clients.open(env, LIST_TIMEOUT));
            if (branch == null || branch.isEmpty()) {
                catalogue.listBranches().forEach(out);
            } else if (build != null && !build.isEmpty()) {
                printContents(catalogue, branch, build, dist, out);
            } else if (dist != null && !dist.isEmpty()) {
                List<BuildCatalogue.BuildWith> found = catalogue.buildsWith(branch, dist);
                for (BuildCatalogue.BuildWith row : found.subList(0, Math.min(Math.max(limit, 0), found.size()))) {
                    String names = row.folders().stream().map(DistFolder::folder).collect(Collectors.joining(", "));
                    out.accept(pad(row.build().build()) + "  " + when(row.build().created()) + "  " + names);
                }
            } else {
                List<BuildCatalogue.NightlyBuild> entries = catalogue.listBuildEntries(branch);
                for (BuildCatalogue.NightlyBuild entry : entries.subList(0,
                        Math.min(Math.max(limit, 0), entries.size()))) {
                    out.accept(pad(entry.build()) + "  " + when(entry.created()));
                }
            }
        } catch (BuildException | IllegalArgumentException e) {
            out.accept("ERROR cat-java builds: " + BuildCommand.reason(e));
            return EXIT_FAILED;
        }
        return EXIT_OK;
    }

    private static void printContents(BuildCatalogue catalogue, String branch, String build, String dist,
            Consumer<String> out) {
        BuildContents contents = catalogue.contents(branch, build);
        // With no dists at all, describe says what the build holds instead.
        List<String> names = dist != null && !dist.isEmpty() ? List.of(dist)
                : contents.dists().isEmpty() ? List.of("") : contents.dists();
        for (String name : names) {
            out.accept(contents.describe(name));
        }
        out.accept("simulators/: " + (contents.simulators() ? "yes" : "no"));
        out.accept("automation/: " + (contents.automation() ? "yes" : "no"));
        if (!contents.other().isEmpty()) {
            out.accept("other: " + String.join(", ", contents.other()));
        }
    }

    /** {@code 2026-10-07T15:18:12.345Z} becomes {@code 2026-10-07 15:18 UTC}. */
    static String when(String created) {
        if (created != null && created.length() >= 16) {
            return created.substring(0, 10) + " " + created.substring(11, 16) + " UTC";
        }
        return created == null || created.isEmpty() ? "?" : created;
    }

    private static String pad(String build) {
        return String.format("%6s", build);
    }

    private static int usage(PrintStream err, String message) {
        err.println("cat-java builds: " + message);
        err.println(USAGE);
        return EXIT_USAGE;
    }
}
