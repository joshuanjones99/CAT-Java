package com.verifone.cat.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.verifone.cat.collect.Blob;
import com.verifone.cat.collect.CaseResult.Pulled;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.CollectException;
import com.verifone.cat.collect.GitUtil;
import com.verifone.cat.collect.Selection;

/**
 * Put a suite's tests into petroapps: the other half of {@link Collect}. Port of {@code petrotest.publish}.
 *
 * <p>The files land in exactly the layout {@code collect} reads:
 * <pre>
 *   AutomationTests/testcases/&lt;KEY&gt;/[&lt;dist&gt;/]&lt;KEY&gt;-TestCase.py       (or .feature)
 *   AutomationTests/testcases/&lt;KEY&gt;/[&lt;dist&gt;/]&lt;KEY&gt;-CATReceipt*.txt
 * </pre>
 *
 * <p><b>What is published is a CAT commit, not a working tree.</b> The files are read from the CAT checkout's
 * {@code HEAD} objects (the Python CAT's, whose {@code tests/suites} CAT-Java runs), so a petroapps commit names the CAT
 * commit it came from. A suite with uncommitted changes is refused.
 *
 * <p><b>petroapps is written through git objects only.</b> The commit is built with a throwaway index and lands on a local
 * branch ({@code cat/tests} by default) whose parent is the fetched {@code origin/<base>} (or the branch itself, when an
 * earlier publish made it). The working tree, the index and {@code HEAD} are never touched, and <b>nothing is pushed</b>
 * unless {@code push} is asked for: then the commit goes straight onto origin's existing {@code base} as a fast-forward,
 * never {@code --force}, never a new branch.
 *
 * <p><b>Refused, each by name:</b> a test whose case petroapps does not hold; a {@code .py} that reads files beside itself
 * ({@code __file__}); a {@code -Tlog.xml} that differs from PATS's own reference (unless {@code replacePatsTlogs}). With
 * {@code prune}, CAT-owned files this publish no longer produces are removed from the case folders it wrote into, never a
 * PATS file ({@link #isCatOwned}).
 *
 * <p>Deviation: the throwaway index is made under {@code tmpRoot} ({@code ~/.cat/java/tmp} in production: CAT-Java
 * writes only under {@code ~/.cat/java}) where the Python uses the system temp folder.
 *
 * <p>Hardware-free: two local git repositories and one fetch. Its tests use throwaway repositories only.
 */
public final class Publisher {

    /** The local branch a publish writes by default. */
    public static final String DEFAULT_BRANCH = "cat/tests";

    /** CAT's test: a pytest module, or an authored case. */
    private static final Pattern TEST_NAME = Pattern.compile("PETROCORE-(\\d+)-TestCase\\.(?:py|feature)");

    /** The directory the pre-2026-09-22 layout kept baselines in; only {@code prune} names it. */
    private static final String SNAPSHOTS = "__snapshots__";

    /** PATS's own TLog reference, which CAT reads rather than duplicating. */
    private static final String TLOG = "-tlog.xml";

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private Publisher() {}

    /** Every publish option, as the Python's keyword arguments. */
    public record Options(String base, String branch, String dist, Path catRoot, boolean fetch, boolean dryRun,
            boolean prune, boolean replacePatsTlogs, boolean push, Set<String> only, Path tmpRoot) {

        public Options {
            base = base == null ? "master" : base;
            branch = branch == null ? DEFAULT_BRANCH : branch;
            dist = dist == null ? "" : dist;
            only = only == null ? null : Set.copyOf(only);
        }
    }

    /** {@code content} with CRLF folded to LF, for comparing a TLog across two repositories. */
    static byte[] eol(byte[] content) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(content.length);
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\r' && i + 1 < content.length && content[i + 1] == '\n') {
                continue;
            }
            out.write(content[i]);
        }
        return out.toByteArray();
    }

    /** {@code <KEY>}'s folder as git spells it, {@code testcases/} before {@code TestCases/} (collect's A1). */
    static String caseFolder(Map<String, List<Blob>> index, String key) {
        String prefix = Selection.CASES + "/" + Selection.casefold(key) + "/";
        TreeSet<String> folders = new TreeSet<>();
        for (Map.Entry<String, List<Blob>> entry : index.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                for (Blob blob : entry.getValue()) {
                    String[] parts = blob.path().split("/", -1);
                    folders.add(String.join("/", Arrays.asList(parts).subList(0, Math.min(3, parts.length))));
                }
            }
        }
        return folders.stream()
                .min(Comparator.comparing((String f) -> !"testcases".equals(f.split("/", -1).length > 1
                        ? f.split("/", -1)[1] : "")).thenComparing(f -> f))
                .orElse(null);
    }

    /**
     * Whether {@code rel}, a path inside a case folder, is a file CAT publishes rather than PATS's: a deliberate
     * whitelist of CAT's own kinds, because the cost of being wrong is deleting a PATS reference. {@code -Tlog.xml} is
     * not CAT's and is never pruned.
     */
    static boolean isCatOwned(String rel) {
        String low = Selection.casefold(rel);
        if (("/" + low).contains("/" + SNAPSHOTS + "/")) {
            return true;
        }
        String name = low.substring(low.lastIndexOf('/') + 1);
        if (name.endsWith(".py")) {
            return name.endsWith("-testcase.py") || name.startsWith("test_");
        }
        if (name.endsWith(".feature")) {
            return name.endsWith("-testcase.feature");
        }
        return (name.contains("-catreceipt") && name.endsWith(".txt"))
                || (name.contains("-cattlog") && name.endsWith(".xml"))
                || (name.contains("-catpackets") && name.endsWith(".json"))
                || name.endsWith("-catpacket.xml")
                || name.endsWith("-catreport.xml");
    }

    /** CAT-owned paths under {@code folder} that this publish did not write or confirm. */
    static List<String> stale(Map<String, List<Blob>> index, String folder, Set<String> keep) {
        String prefix = Selection.casefold(folder) + "/";
        TreeSet<String> removals = new TreeSet<>();
        for (Map.Entry<String, List<Blob>> entry : index.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            for (Blob blob : entry.getValue()) {
                if (!keep.contains(blob.path()) && isCatOwned(blob.path().substring(folder.length() + 1))) {
                    removals.add(blob.path());
                }
            }
        }
        return new ArrayList<>(removals);
    }

    private static byte[] git(Path repo, String... args) {
        return GitUtil.git(repo, TIMEOUT, null, args);
    }

    private static byte[] git(Path repo, Map<String, String> env, String... args) {
        return GitUtil.git(repo, TIMEOUT, env, args);
    }

    private static String text(byte[] out) {
        return new String(out, StandardCharsets.UTF_8).strip();
    }

    /** {@code git cat-file blob}, failing as a {@link PublishException} like every other git call here. */
    static byte[] blob(Path repo, String sha) {
        try {
            return git(repo, "cat-file", "blob", sha);
        } catch (CollectException e) {
            throw new PublishException(e.getMessage(), e);
        }
    }

    /** One commit on {@code parent} adding {@code files} and removing {@code removals}, via a throwaway index. */
    static String writeCommit(Path repo, String parent, Map<String, byte[]> files, String message, List<String> removals,
            Path tmpRoot) {
        Path tmp;
        try {
            Files.createDirectories(tmpRoot);
            tmp = Files.createTempDirectory(tmpRoot, "cat-publish-");
        } catch (IOException e) {
            throw new PublishException("cannot make a throwaway index: " + e.getMessage(), e);
        }
        try {
            Map<String, String> env = new HashMap<>(GitUtil.NO_PROMPT);
            env.put("GIT_INDEX_FILE", tmp.resolve("index").toString());
            run(repo, env, null, "read-tree", parent);
            StringBuilder entries = new StringBuilder();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                String sha = run(repo, env, file.getValue(), "hash-object", "-w", "--stdin");
                entries.append("100644 ").append(sha).append('\t').append(file.getKey()).append('\n');
            }
            if (entries.length() > 0) {
                run(repo, env, entries.toString().getBytes(StandardCharsets.UTF_8), "update-index", "--add",
                        "--index-info");
            }
            for (String path : removals) {
                run(repo, env, null, "update-index", "--force-remove", path);
            }
            String tree = run(repo, env, null, "write-tree");
            return run(repo, env, null, "commit-tree", tree, "-p", parent, "-m", message);
        } finally {
            deleteTree(tmp);
        }
    }

    private static String run(Path repo, Map<String, String> env, byte[] stdin, String... args) {
        List<String> argv = new ArrayList<>(List.of("-c", "core.ignorecase=false"));
        argv.addAll(List.of(args));
        GitUtil.Output done;
        try {
            done = GitUtil.run(repo, argv, env, stdin, TIMEOUT);
        } catch (CollectException e) {
            throw new PublishException(e.getMessage(), e);
        }
        if (done.exitCode() != 0) {
            String detail = new String(done.stderr(), StandardCharsets.UTF_8).strip();
            throw new PublishException("git " + args[0] + " failed in " + repo + ": " + detail);
        }
        return new String(done.stdout(), StandardCharsets.US_ASCII).strip();
    }

    /**
     * Commits {@code suite}'s tests and baselines to petroapps' local {@code branch} (or, with {@code push}, onto origin's
     * {@code base}).
     *
     * @param suite the suite's path in the CAT checkout, e.g. {@code tests/suites/charity}
     * @throws PublishException with nothing written to petroapps
     */
    public static Publication publish(String suite, Path petroapps, Options options) {
        Path catRoot = options.catRoot();
        Path repo = petroapps;
        String suiteRel = strip(suite.replace('\\', '/'), '/');
        String base = options.base();
        String branch = options.branch();
        if (options.push() && !branch.equals(base)) {
            throw new PublishException("push publishes onto " + base + " itself; it writes no branch " + branch);
        }
        if (options.push() && !options.fetch()) {
            throw new PublishException("push builds on a fresh fetch of origin; drop --no-fetch");
        }
        String catCommit;
        Map<String, String> tracked = new TreeMap<>();
        String fetchError;
        String parentRef;
        String parent;
        Map<String, List<Blob>> index;
        try {
            if (!text(git(catRoot, "status", "--porcelain", "--", suiteRel)).isEmpty()) {
                throw new PublishException(suiteRel + " has uncommitted changes; publish reads HEAD");
            }
            catCommit = text(git(catRoot, "rev-parse", "HEAD"));
            byte[] listing = git(catRoot, "ls-tree", "-r", "-z", "HEAD", "--", suiteRel + "/");
            for (String entry : new String(listing, StandardCharsets.UTF_8).split("\0")) {
                if (entry.isEmpty()) {
                    continue;
                }
                int tab = entry.indexOf('\t');
                String[] meta = entry.substring(0, tab).strip().split("\\s+");
                tracked.put(entry.substring(tab + 1), meta[2]);
            }
            String head;
            try {
                head = text(git(repo, "symbolic-ref", "--quiet", "HEAD"));
            } catch (CollectException e) {
                head = ""; // detached: no branch is checked out by name
            }
            if (head.equals("refs/heads/" + branch) && !options.push()) {
                throw new PublishException(branch + " is checked out in " + repo + "; publish moves no working tree");
            }
            fetchError = options.fetch() ? Collect.fetchBranch(repo, base) : "";
            if (options.push()) {
                if (!fetchError.isEmpty()) {
                    throw new PublishException("cannot push: git fetch origin " + base + " failed: " + fetchError);
                }
                Collect.Ref ref = Collect.resolveCommit(repo, base);
                if (!ref.ref().equals("origin/" + base)) {
                    throw new PublishException("origin has no branch " + base + "; push never creates one");
                }
                parentRef = ref.ref();
                parent = ref.commit();
            } else {
                String found;
                try { // an earlier publish is built on, so one branch carries the whole hand-over
                    found = text(git(repo, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch));
                } catch (CollectException e) {
                    found = null;
                }
                if (found != null) {
                    parentRef = branch;
                    parent = found;
                } else {
                    Collect.Ref ref = Collect.resolveCommit(repo, base);
                    parentRef = ref.ref();
                    parent = ref.commit();
                }
            }
            index = Collect.readIndex(repo, parent);
        } catch (CollectException e) {
            throw new PublishException(e.getMessage(), e);
        }

        Publication result = new Publication(branch, parentRef, parent, catCommit, fetchError, options.dryRun());
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Set<String>> keep = new LinkedHashMap<>();
        for (String path : tracked.keySet()) {
            String name = path.substring(suiteRel.length() + 1);
            Matcher match = TEST_NAME.matcher(name);
            if (!match.matches()) {
                continue;
            }
            String key = "PETROCORE-" + match.group(1);
            if (options.only() != null && !options.only().contains(key)) {
                continue;
            }
            if (name.endsWith(".py") && tracked.containsKey(suiteRel + "/" + key + "-TestCase.feature")) {
                continue;
            }
            String folder = caseFolder(index, key);
            if (folder == null) {
                result.refused.add(new Pulled(path, null, "petroapps " + parentRef + " has no " + key));
                continue;
            }
            if (name.endsWith(".py") && new String(blob(catRoot, tracked.get(path)), StandardCharsets.UTF_8)
                    .contains("__file__")) {
                result.refused.add(new Pulled(path, null,
                        "reads files beside itself (__file__), which collect does not pull"));
                continue;
            }
            List<String[]> sources = new ArrayList<>();
            sources.add(new String[] {path, name});
            for (String p : tracked.keySet()) {
                String tail = p.substring(suiteRel.length() + 1);
                if (!tail.contains("/") && Selection.isBaseline(tail, key)) {
                    sources.add(new String[] {p, tail});
                }
            }
            for (String[] pair : sources) {
                String source = pair[0];
                String tail = pair[1];
                String dest = Selection.join(folder, options.dist(), tail);
                List<Blob> existing = index.getOrDefault(Selection.casefold(dest), List.of());
                keep.computeIfAbsent(folder, k -> new java.util.HashSet<>()).add(dest);
                List<Blob> here = existing.stream().filter(b -> b.path().equals(dest)).toList();
                if (here.stream().anyMatch(b -> b.sha().equals(tracked.get(source)))) {
                    result.unchanged.add(new Pulled(source, dest, null));
                    continue;
                }
                byte[] content = blob(catRoot, tracked.get(source));
                if (Selection.casefold(tail).endsWith(TLOG) && !existing.isEmpty()) {
                    // The same TLog stored with different line endings is the same reference, and its bytes are
                    // PATS's to keep: leave them.
                    if (!here.isEmpty() && Arrays.equals(eol(blob(repo, here.get(0).sha())), eol(content))) {
                        result.unchanged.add(new Pulled(source, dest, null));
                        continue;
                    }
                    if (options.replacePatsTlogs()) {
                        files.put(dest, content);
                        result.replaced.add(new Pulled(source, dest, null));
                        continue;
                    }
                    result.refused.add(new Pulled(source, null, tail + " already exists in petroapps with different "
                            + "content, and it is PATS's reference too — publishing would change what PATS asserts. "
                            + "Reconcile it deliberately (DESIGN.md §5.4)."));
                    continue;
                }
                files.put(dest, content);
                result.written.add(new Pulled(source, dest, null));
            }
        }

        List<String> removals = new ArrayList<>();
        if (options.prune()) {
            for (Map.Entry<String, Set<String>> entry : keep.entrySet()) {
                removals.addAll(stale(index, entry.getKey(), entry.getValue()));
            }
            removals.sort(null);
            result.removed.addAll(removals);
        }

        if ((!files.isEmpty() || !removals.isEmpty()) && !options.dryRun()) {
            String message = "CAT: " + files.size() + " translated test file(s) from " + suiteRel;
            if (!removals.isEmpty()) {
                message += ", " + removals.size() + " stale file(s) removed";
            }
            message += "\n\nCAT commit " + catCommit;
            result.commit = writeCommit(repo, parent, files, message, removals, options.tmpRoot());
            try {
                if (options.push()) {
                    pushOnto(repo, base, parent, result.commit);
                    result.pushed = true;
                    Collect.fetchBranch(repo, base); // origin/<base> now names the pushed commit
                } else {
                    git(repo, "update-ref", "refs/heads/" + branch, result.commit);
                }
            } catch (CollectException e) {
                throw new PublishException(e.getMessage(), e);
            }
        }
        return result;
    }

    /** Pushes {@code commit} onto origin's existing {@code base}, as a fast-forward from {@code parent} only. */
    static void pushOnto(Path repo, String base, String parent, String commit) {
        String[] heads = text(git(repo, GitUtil.NO_PROMPT, "ls-remote", "--heads", "origin", "refs/heads/" + base))
                .split("\\s+");
        if (heads.length == 0 || heads[0].isEmpty()) {
            throw new PublishException("origin has no branch " + base + "; push never creates one");
        }
        if (!heads[0].equals(parent)) {
            throw new PublishException("origin/" + base + " moved to " + heads[0].substring(0, Math.min(11,
                    heads[0].length())) + " since the fetch; publish again");
        }
        // No --force and no '+': git refuses anything but a fast-forward.
        git(repo, GitUtil.NO_PROMPT, "push", "--quiet", "origin", commit + ":refs/heads/" + base);
    }

    private static String strip(String text, char c) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == c) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == c) {
            end--;
        }
        return text.substring(start, end);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // best effort
        }
    }
}
