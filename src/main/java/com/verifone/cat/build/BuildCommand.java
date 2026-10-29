package com.verifone.cat.build;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.verifone.cat.launch.UpgradeMode;

/**
 * {@code cat-java build --dist <dist> (--branch <b> [--build <n>] | --solutions <n>) [--mode new_install|classic]
 * [--foundation-branch <b> --foundation-build <n>] [--flavour <f>]}: download a nightly build's {@code overlay.zip}
 * and installer jars from Artifactory into the builds root. The Python's {@code petrotest build} (its flag checks in
 * {@code petrotest.cli._build}, its body {@code petrotest.build.cli.build_cli}).
 *
 * <p>{@code --solutions <n>} is {@code --branch Solutions --build <n>}. With no {@code --build}, the branch's newest
 * build that carries {@code --dist} ({@link BuildCatalogue#newestBuildFor}). A viper-only build is merged onto
 * {@code --foundation-branch}/{@code --foundation-build}, or the foundation it pairs with ({@link FoundationPair}).
 * It does not read a rig: where builds come from has nothing to do with any one rig.
 *
 * <p>Output is the Python's: progress to stdout, then {@code build ready: <dir>}; a missing login, build or file, or a
 * failed download, is one {@code ERROR cat-java build <branch> <build>: <why>} line on stdout (which a launch step
 * reads as its reason), never a stack trace. Exit 0 when the build is ready, 1 when it is not, 2 for a usage error
 * (nothing was asked of Artifactory). Deviation: the program name in its lines is {@code cat-java}.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port): tested against {@code FakeArtifactory}.
 */
public final class BuildCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    /** The Python's {@code ArtifactoryClient(ARTIFACTORY_URL, timeout=600.0)} for a download. */
    static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(600);

    static final String USAGE = String.join("\n",
            "usage: cat-java build --dist <dist> (--branch <branch> [--build <n>] | --solutions <n>)",
            "                      [--mode {new_install,classic}] [--foundation-branch <b> --foundation-build <n>]",
            "                      [--flavour {plain,foundation,sc,retail,viper}]",
            "",
            "Download a nightly build's overlay.zip and installer jars from Artifactory.",
            "  --dist               distribution, e.g. 'buypass'",
            "  --branch             nightly branch under RMS_Nightly_CLW/Petro: Solutions, master, PETROCORE-<n>...",
            "  --build              build number on --branch; default: the newest one that carries --dist",
            "  --solutions          a Solutions build number: the same as --branch Solutions --build <n>",
            "  --mode               which installer jars to fetch beside overlay.zip: PSI and AU, or AU",
            "  --foundation-branch  for a viper-only build: the branch of the foundation build to merge it onto",
            "  --foundation-build   for a viper-only build: that foundation build's number",
            "  --flavour            which of a build's folders for --dist to install, where it holds two");

    /** Makes the Artifactory client a command uses; a test returns one against its fake. */
    @FunctionalInterface
    public interface Clients {
        ArtifactoryClient open(Map<String, String> env, Duration timeout);

        /** The real server, with the environment's or the saved login. */
        Clients REAL = (env, timeout) -> new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                StoredArtifactoryCredentials.forEnvironment(env), timeout);
    }

    private final Clients clients;
    private final OverlayMerge.Merger merger;

    public BuildCommand() {
        this(Clients.REAL, null);
    }

    /** @param merger the merge for a viper-only build; null for {@link OverlayMerge} with the environment's resources */
    public BuildCommand(Clients clients, OverlayMerge.Merger merger) {
        this.clients = clients;
        this.merger = merger;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "build".equals(args.get(0))) {
            args.remove(0);
        }
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> known = List.of("--dist", "--branch", "--build", "--solutions", "--mode", "--foundation-branch",
                "--foundation-build", "--flavour");
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--help".equals(arg) || "-h".equals(arg)) {
                out.println(USAGE);
                return EXIT_OK;
            }
            if (!known.contains(arg)) {
                return usage(err, "unrecognized arguments: " + arg);
            }
            if (i + 1 >= args.size()) {
                return usage(err, "argument " + arg + ": expected one argument");
            }
            flags.put(arg, args.get(++i));
        }
        String dist = flags.get("--dist");
        if (dist == null) {
            return usage(err, "the following arguments are required: --dist");
        }
        String mode = flags.getOrDefault("--mode", "new_install");
        if (!List.of("new_install", "classic").contains(mode)) {
            return usage(err, "argument --mode: invalid choice: '" + mode + "' (choose from 'new_install', 'classic')");
        }
        String flavour = flags.get("--flavour");
        if (flavour != null && !BuildContents.FLAVOURS.contains(flavour)) {
            return usage(err, "argument --flavour: invalid choice: '" + flavour
                    + "' (choose from 'plain', 'foundation', 'sc', 'retail', 'viper')");
        }
        String branch = blankToNull(flags.get("--branch"));
        String number = blankToNull(flags.get("--build"));
        String solutions = blankToNull(flags.get("--solutions"));
        if (solutions != null) {
            if ((branch != null && !BuildDiscovery.SOLUTIONS.equals(branch)) || number != null) {
                return usage(err, "--solutions <n> is --branch Solutions --build <n>; give one or the other");
            }
            branch = BuildDiscovery.SOLUTIONS;
            number = solutions;
        }
        if (branch == null) {
            return usage(err, "name a --branch (or --solutions <n>); `cat-java builds` lists them");
        }
        String foundationBranch = blankToNull(flags.get("--foundation-branch"));
        String foundationBuild = blankToNull(flags.get("--foundation-build"));
        if ((foundationBranch == null) != (foundationBuild == null)) {
            return usage(err, "--foundation-branch and --foundation-build go together");
        }
        List<String> foundation = foundationBranch == null ? null : List.of(foundationBranch, foundationBuild);
        return build(branch, number, dist, UpgradeMode.of(mode), foundation, flavour, env,
                BuildDiscovery.buildsRoot(env), line -> {
                    out.println(line);
                    out.flush();
                });
    }

    /**
     * The download, every line to {@code out}: the Python's {@code build_cli}.
     *
     * @param build      null for the branch's newest build carrying {@code dist}
     * @param foundation {@code [branch, build]} for a viper-only build, or null for its pair
     * @return 0 when the build is ready under {@code root}, else 1
     */
    public int build(String branch, String build, String dist, UpgradeMode mode, List<String> foundation,
            String flavour, Map<String, String> env, Path root, Consumer<String> out) {
        String number = build;
        Path dest;
        try {
            ArtifactoryClient client = clients.open(env, DOWNLOAD_TIMEOUT);
            if (number == null || number.isEmpty()) {
                number = new BuildCatalogue(client).newestBuildFor(branch, dist);
                out.accept("no --build given: " + branch + "'s newest build with " + dist + " is " + number);
            }
            OverlayMerge.Merger merge = merger != null ? merger
                    : new OverlayMerge(OverlayMerge.transitionDir(env)).merger();
            dest = CommanderBuildFetch.fetchCommanderBuild(client, branch, number, dist, mode, foundation, flavour,
                    root, out, merge);
        } catch (BuildException | IllegalArgumentException | UncheckedIOException e) {
            out.accept("ERROR cat-java build " + branch + " " + (number == null ? "" : number) + ": "
                    + reason(e));
            return EXIT_FAILED;
        }
        out.accept("build ready: " + dest);
        return EXIT_OK;
    }

    /** What a failure says: an Artifactory status as one actionable sentence, anything else its own message. */
    static String reason(RuntimeException e) {
        if (e instanceof ArtifactoryException && ((ArtifactoryException) e).status() != 404) {
            return ArtifactoryErrors.describe(e, "");
        }
        return e.getMessage();
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static int usage(PrintStream err, String message) {
        err.println("cat-java build: " + message);
        err.println(USAGE);
        return EXIT_USAGE;
    }
}
