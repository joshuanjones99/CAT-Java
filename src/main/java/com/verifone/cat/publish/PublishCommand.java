package com.verifone.cat.publish;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.CollectException;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.provision.ProvisionPaths;

/**
 * {@code cat-java publish --suite tests/suites/<suite> [--petroapps <dir>] [--base <branch>] [--branch <branch>] [--dist
 * <dist>] [--no-fetch] [--cat-root <dir>] [--dry-run] [--replace-pats-tlogs] [--prune] [--plan <key>] [--push]}: commit
 * a suite's tests and baselines to a local petroapps branch. The Python's {@code petrotest publish} ({@link Publisher}).
 *
 * <p>It never pushes unless {@code --push} is given, and then only onto origin's existing {@code --base} as a
 * fast-forward. Defaults as the Python's: {@code --petroapps} {@code $PETROAPPS_ROOT} (else {@code C:\gitrepos\petroapps}),
 * {@code --base master}, {@code --branch cat/tests} ({@code --base} with {@code --push}), {@code --cat-root} the Python
 * CAT checkout ({@code CAT_PYTHON_ROOT}, else {@code C:\gitrepos\CAT}), whose {@code tests/suites} CAT-Java runs. {@code
 * --plan} publishes only that plan's cached cases: {@code ~/.cat/java/cases-<plan>.json}, else the Python CAT's
 * {@code ~/.cat}, else the shipped seed.
 *
 * <p>Exit 0 with the report printed; 2 when nothing was written (a refusal names why) or for a usage error.
 */
public final class PublishCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java publish --suite <tests/suites/x> [--petroapps <dir>] [--base <branch>] [--branch <branch>]",
            "                        [--dist <dist>] [--no-fetch] [--cat-root <dir>] [--dry-run]",
            "                        [--replace-pats-tlogs] [--prune] [--plan <key>] [--push]",
            "",
            "Commit a suite's tests and baselines to a local petroapps branch; never pushes without --push.",
            "  --suite               e.g. tests/suites/charity",
            "  --petroapps           petroapps checkout; default $PETROAPPS_ROOT",
            "  --base                branch to build on; default master",
            "  --branch              local branch to write; default cat/tests",
            "  --dist                write into <case>/<dist>/ instead of the case folder",
            "  --no-fetch            do not fetch origin/<base>",
            "  --cat-root            the CAT checkout whose HEAD is published; default the Python CAT's",
            "  --dry-run             report only; write nothing",
            "  --replace-pats-tlogs  let a CAT -Tlog.xml replace petroapps' differing copy (PATS's reference too)",
            "  --prune               remove CAT files in each written case folder that this publish no longer",
            "                        produces, e.g. a -TestCase.py its -TestCase.feature replaces; never a PATS file",
            "  --plan                publish only this Xray plan's cases (its cached case list)",
            "  --push                put the commit straight onto origin/<base> as a fast-forward (never --force,",
            "                        never a new branch); no local branch is written");

    private final Function<String, Optional<List<String>>> caseLists;
    private final Path tmpRoot;

    public PublishCommand() {
        this(PublishCommand::caseList, Path.of(System.getProperty("user.home"), ".cat", "java", "tmp"));
    }

    /** A command whose plan case lists and throwaway-index folder are injected (tests). */
    public PublishCommand(Function<String, Optional<List<String>>> caseLists, Path tmpRoot) {
        this.caseLists = caseLists;
        this.tmpRoot = tmpRoot;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "publish".equals(args.get(0))) {
            args.remove(0);
        }
        String suite = null;
        String petroapps = null;
        String base = "master";
        String branch = null;
        String dist = "";
        String catRoot = null;
        String plan = null;
        boolean noFetch = false;
        boolean dryRun = false;
        boolean replace = false;
        boolean prune = false;
        boolean push = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--no-fetch" -> noFetch = true;
                case "--dry-run" -> dryRun = true;
                case "--replace-pats-tlogs" -> replace = true;
                case "--prune" -> prune = true;
                case "--push" -> push = true;
                case "-h", "--help" -> {
                    out.println(USAGE);
                    return EXIT_OK;
                }
                case "--suite", "--petroapps", "--base", "--branch", "--dist", "--cat-root", "--plan" -> {
                    if (i + 1 >= args.size()) {
                        err.println(arg + " needs a value");
                        err.println(USAGE);
                        return EXIT_USAGE;
                    }
                    String value = args.get(++i);
                    switch (arg) {
                        case "--suite" -> suite = value;
                        case "--petroapps" -> petroapps = value;
                        case "--base" -> base = value;
                        case "--branch" -> branch = value;
                        case "--dist" -> dist = value;
                        case "--cat-root" -> catRoot = value;
                        default -> plan = value;
                    }
                }
                default -> {
                    err.println("unknown argument: " + arg);
                    err.println(USAGE);
                    return EXIT_USAGE;
                }
            }
        }
        if (suite == null) {
            err.println("--suite is required");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        String repo = petroapps != null ? petroapps : Collect.defaultPetroappsRoot();
        Set<String> only = null;
        if (plan != null) {
            Optional<List<String>> keys;
            try {
                keys = caseLists.apply(plan);
            } catch (CollectException e) {
                err.println(e.getMessage());
                return EXIT_USAGE;
            }
            if (keys.isEmpty()) {
                err.println("no cached case list for " + plan + "; open the plan in cat first");
                return EXIT_USAGE;
            }
            only = new HashSet<>(keys.get());
        }
        Publication result;
        try {
            result = Publisher.publish(suite, Path.of(repo), new Publisher.Options(base,
                    branch != null ? branch : push ? base : Publisher.DEFAULT_BRANCH, dist,
                    catRoot != null ? Path.of(catRoot) : ProvisionPaths.pythonRoot(env), !noFetch, dryRun, prune,
                    replace, push, only, tmpRoot));
        } catch (PublishException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        out.println(String.join("\n", result.lines(repo)));
        return EXIT_OK;
    }

    /** {@code ~/.cat/java/cases-<plan>.json}, else the Python CAT's {@code ~/.cat}, else the shipped seed. */
    static Optional<List<String>> caseList(String plan) {
        Path home = Path.of(System.getProperty("user.home"), ".cat");
        Optional<Path> file = Collect.defaultCaseFile(plan, home.resolve("java"), home);
        if (file.isPresent()) {
            return Optional.of(Collect.readCaseList(file.get()));
        }
        List<String> seeded = new SeedPlanSource().cases(plan).stream().map(PlanCase::key).toList();
        return seeded.isEmpty() ? Optional.empty() : Optional.of(seeded);
    }
}
