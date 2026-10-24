package com.verifone.cat.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Collects a plan's test cases from petroapps: inventories each case's PATS files, fetches {@code origin/<branch>},
 * and pulls each case's translated {@code .py} test and its baselines into CAT's suite.
 *
 * <p>Reads immutable git objects, never a working tree: {@code testcases/} and {@code TestCases/} cannot both exist in
 * a Windows checkout, so the case folders are read from the commit's tree and casefolded into one union. Fetching
 * updates only remote refs; the working tree, the index and {@code HEAD} are untouched (A6). Conflicting contents are
 * refused, never guessed (A1). {@code gitauth} and {@code publish} (pushing) are not ported.
 *
 * <p>The Python CAT's {@code petrotest.collect}, its selection in {@link Selection}, its git reads in {@link GitUtil}
 * and its freshness check in {@link Freshness}. Java port UNVERIFIED AGAINST HARDWARE; tested against throwaway git
 * repositories only.
 */
public final class Collect {

    /** Where petroapps is checked out when {@code PETROAPPS_ROOT} is unset. */
    public static final String DEFAULT_PETROAPPS_ROOT = "C:\\gitrepos\\petroapps";

    /** Where pulled tests and baselines are written, relative to a CAT checkout. */
    public static final Path PULLED_DIR = Path.of("tests", "suites", "pulled");

    /** Lists what the last collect wrote, so the next one removes only those files. */
    public static final String MANIFEST = "COLLECTED.json";

    /** {@link CaseResult.Pulled#reason()} for a file a dry run would have written. */
    public static final String DRY_RUN = "dry run";

    /** Never pulled (A5): a second {@code conftest.py} shadows the root one; an {@code __init__.py} changes imports. */
    private static final Set<String> REFUSED_NAMES = Set.of("conftest.py", "__init__.py");

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(300);
    private static final Map<String, String> NO_PROMPT = GitUtil.NO_PROMPT;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The branch a run reads: what was asked for, and the ref actually read. */
    record Ref(String ref, String commit) {}

    /** A file queued for writing: its path relative to the output folder, its destination, and its blob. */
    record Pending(String rel, Path dest, String sha) {}

    private Collect() {}

    /**
     * {@code $PETROAPPS_ROOT}, else {@link #DEFAULT_PETROAPPS_ROOT}. An empty variable counts as unset.
     */
    public static String defaultPetroappsRoot() {
        String env = System.getenv("PETROAPPS_ROOT");
        return env == null || env.isEmpty() ? DEFAULT_PETROAPPS_ROOT : env;
    }

    /**
     * Fetches {@code origin/<branch>} into its remote-tracking ref: {@code ""} on success, else why not. Writes that ref
     * and {@code FETCH_HEAD} only, never the working tree, the index or {@code HEAD}. The refspec is explicit, so the
     * remote-tracking ref moves whatever {@code remote.origin.fetch} says. Automatic gc and maintenance are off.
     */
    public static String fetchBranch(Path repo, String branch) {
        try {
            GitUtil.git(repo, FETCH_TIMEOUT, NO_PROMPT, "-c", "gc.auto=0", "-c", "maintenance.auto=false",
                    "fetch", "--quiet", "--no-tags", "origin",
                    "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        } catch (CollectException e) {
            return e.getMessage();
        }
        return "";
    }

    /** {@code origin/<revision>} if it exists, else {@code <revision>} as the checkout holds it. Never fetches. */
    static Ref resolveCommit(Path repo, String revision) {
        for (String candidate : List.of("origin/" + revision, revision)) {
            try {
                byte[] out = GitUtil.git(repo, GIT_TIMEOUT, null, "rev-parse", "--verify", "--quiet",
                        candidate + "^{commit}");
                return new Ref(candidate, new String(out, StandardCharsets.US_ASCII).strip());
            } catch (CollectException e) {
                // try the next candidate
            }
        }
        throw new CollectException("revision '" + revision + "' (or origin/" + revision + ") not found in " + repo);
    }

    /** Every blob under the collected roots, keyed by casefolded path (NTFS's view). */
    static Map<String, List<Blob>> readIndex(Path repo, String commit) {
        byte[] raw = GitUtil.git(repo, GIT_TIMEOUT, null, "ls-tree", "-r", "-z", "--full-tree", commit, "--",
                "AutomationTests", "isdApps/xml/schemas/src/schemas");
        Map<String, List<Blob>> index = new LinkedHashMap<>();
        for (String entry : new String(raw, StandardCharsets.UTF_8).split("\0")) {
            if (entry.isEmpty()) {
                continue;
            }
            int tab = entry.indexOf('\t');
            if (tab < 0) {
                throw new CollectException("git ls-tree: unexpected entry " + entry);
            }
            String[] meta = entry.substring(0, tab).strip().split("\\s+");
            String path = entry.substring(tab + 1);
            if (meta.length != 3) {
                throw new CollectException("git ls-tree: unexpected entry " + entry);
            }
            if ("blob".equals(meta[1])) {
                index.computeIfAbsent(Selection.casefold(path), k -> new ArrayList<>()).add(new Blob(path, meta[2]));
            }
        }
        return index;
    }

    /**
     * Inventories each case's PATS files, then pulls its translated {@code .py} test and baselines.
     *
     * @param caseKeys the plan's ordered case list, kept in that order
     * @param repo     the petroapps checkout
     * @throws CollectException when {@code repo} is not a checkout, or the revision does not exist. A failed fetch is
     *                          recorded on the result, never thrown.
     */
    public static CollectResult collect(List<String> caseKeys, Path repo, CollectOptions options) {
        if (!Files.exists(repo.resolve(".git"))) {
            throw new CollectException("not a git checkout: " + repo);
        }
        String fetchError = options.fetch() ? fetchBranch(repo, options.revision()) : "";
        Ref resolved = resolveCommit(repo, options.revision());
        Map<String, List<Blob>> index = readIndex(repo, resolved.commit());
        Map<String, Map<String, List<Blob>>> folders = Selection.caseFolders(index);
        Path root = options.repoRoot();
        Path target = options.outDir();
        Set<String> taken = target != null ? existingTestNames(root, target) : Set.of();
        Pulls pulls = new Pulls(target, root);
        List<CaseResult> cases = new ArrayList<>();
        for (String key : caseKeys) {
            Map<String, List<Blob>> folder = folders.getOrDefault(Selection.casefold(key), Map.of());
            List<String> trees = folder.values().stream().flatMap(List::stream)
                    .map(b -> b.path().split("/")[1]).distinct().sorted().toList();
            CaseResult result = new CaseResult(key, trees);
            for (List<Blob> blobs : folder.values()) {
                if (blobs.size() > 1) {
                    // Path is AutomationTests/<tree>/<case>/<name...>: the name is what follows the case folder.
                    String[] parts = blobs.get(0).path().split("/");
                    String name = String.join("/", Arrays.copyOfRange(parts, Math.min(3, parts.length), parts.length));
                    boolean same = blobs.stream().map(Blob::sha).distinct().count() == 1;
                    result.collisions.add(new CaseResult.Collision(name,
                            blobs.stream().map(Blob::path).toList(), same));
                }
            }
            for (Rule rule : Rules.RULES) {
                List<String> hits = Selection.apply(rule, folder, options.dist(), true);
                if (hits == null) {
                    if (rule.missing() != null && !rule.missing().isEmpty()) {
                        result.missing.add(rule.missing().replace("{id}", key));
                    }
                    continue;
                }
                for (String rel : hits) {
                    result.copies.add(new CaseResult.Copy(rule.name(), folder.get(rel).get(0).path(), rule.dest()));
                }
            }
            // A2's dist-first rule for each kind of CAT test on its own: a case keeps one or the other.
            List<String> tests = new ArrayList<>();
            for (String pattern : List.of("*.py", "*-TestCase.feature")) {
                Rule test = new Rule("test", pattern, "", Rules.DIST_FIRST, null, "", null, false, false);
                List<String> hits = Selection.apply(test, folder, options.dist(), false);
                if (hits != null) {
                    tests.addAll(hits);
                }
            }
            for (String rel : tests) {
                List<Blob> blobs = folder.get(rel);
                String name = Selection.nameOf(blobs.get(0).path());
                String reason = "";
                if (REFUSED_NAMES.contains(Selection.casefold(name))) {
                    reason = name + " is never pulled (one conftest.py, no packages)";
                } else if (taken.contains(Selection.casefold(name))) {
                    reason = "a file named " + name + " " + CaseResult.SHADOWED;
                }
                CaseResult.Pulled test = pulls.pull(blobs, name, reason);
                result.pulled.add(test);
                // A3: a test's baselines are its siblings, named for the case, pulled flat beside it.
                String caseKey = Selection.caseOf(name);
                String here = Selection.parentOf(rel);
                String why = test.dest() != null || target == null
                        ? "" : "its test was not pulled: " + test.reason();
                for (String sibling : new TreeSet<>(folder.keySet())) {
                    if (!Selection.parentOf(sibling).equals(here)) {
                        continue;
                    }
                    List<Blob> siblingBlobs = folder.get(sibling);
                    String file = Selection.nameOf(siblingBlobs.get(0).path());
                    if (Selection.isBaseline(file, caseKey)) {
                        result.baselines.add(pulls.pull(siblingBlobs, file, why));
                    }
                }
            }
            cases.add(result);
        }
        Boolean fetched = options.fetch() ? Boolean.valueOf(fetchError.isEmpty()) : null;
        CollectResult collection = new CollectResult(repo.toString(), options.revision(), resolved.ref(),
                resolved.commit(), options.dist(), cases, Selection.sharedAssets(index),
                target != null ? displayPath(target, root) : null, fetched, fetchError);
        if (target != null) {
            write(repo, target, pulls.toWrite, collection);
        }
        return collection;
    }

    /** Basenames of every {@code .py} and authored {@code .feature} under {@code tests/} outside {@code out} (A5). */
    static Set<String> existingTestNames(Path root, Path out) {
        Set<String> names = new HashSet<>();
        Path tests = root.resolve("tests");
        if (!Files.isDirectory(tests)) {
            return names;
        }
        Path outAbs = out.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(tests)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (Selection.isCatTest(name) && !p.toAbsolutePath().normalize().startsWith(outAbs)) {
                    names.add(Selection.casefold(name));
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new CollectException("cannot list " + tests + ": " + e.getMessage(), e);
        }
        return names;
    }

    /** The path relative to {@code root}, with forward slashes, or the path as given when it is not under root. */
    static String displayPath(Path path, Path root) {
        Path abs = path.toAbsolutePath().normalize();
        Path rootAbs = root.toAbsolutePath().normalize();
        if (!abs.startsWith(rootAbs)) {
            return path.toString();
        }
        return rootAbs.relativize(abs).toString().replace('\\', '/');
    }

    /**
     * Replaces the previous collection's files in {@code target} with {@code files}.
     *
     * <p>Every blob is read before anything on disk changes, and the previous files go only after the new manifest is
     * written: a {@code cat-file} that fails part-way leaves the last collection whole.
     */
    static void write(Path repo, Path target, List<Pending> files, CollectResult collection) {
        Path manifest = target.resolve(MANIFEST);
        List<String> previous = previousWritten(manifest);
        Map<String, byte[]> contents;
        try {
            contents = GitUtil.readBlobs(repo, files.stream().map(Pending::sha).toList());
        } catch (IllegalArgumentException | CollectException e) {
            throw new CollectException("git cat-file failed: " + e.getMessage(), e);
        }
        List<String> written = files.stream().map(Pending::rel).toList();
        try {
            Files.createDirectories(target);
            for (Pending file : files) {
                Files.createDirectories(file.dest().getParent());
                Files.write(file.dest(), contents.get(file.sha()));
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("repo", collection.repo);
            document.put("revision", collection.revision);
            document.put("ref", collection.ref);
            document.put("commit", collection.commit);
            document.put("dist", collection.dist);
            document.put("cases", collection.cases);
            document.put("assets", collection.assets);
            document.put("out_dir", collection.outDir);
            document.put("fetched", collection.fetched);
            document.put("fetch_error", collection.fetchError);
            document.put("written", written);
            Files.writeString(manifest, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                    StandardCharsets.UTF_8);
            Path base = target.toAbsolutePath().normalize();
            Set<Path> keep = new HashSet<>();
            for (Pending file : files) {
                keep.add(file.dest().toAbsolutePath().normalize());
            }
            for (String name : previous) {
                Path path = base.resolve(name).normalize();
                if (path.startsWith(base) && !path.equals(base) && !keep.contains(path)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            throw new CollectException("cannot write " + target + ": " + e.getMessage(), e);
        }
    }

    private static List<String> previousWritten(Path manifest) {
        List<String> previous = new ArrayList<>();
        if (!Files.isRegularFile(manifest)) {
            return previous;
        }
        try {
            JsonNode written = JSON.readTree(Files.readString(manifest, StandardCharsets.UTF_8)).path("written");
            if (written.isArray()) {
                for (JsonNode name : written) {
                    if (name.isTextual()) {
                        previous.add(name.asText());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable or foreign manifest removes nothing.
            previous.clear();
        }
        return previous;
    }

    /** Case keys, in order, from a {@code cases-<plan>.json} cache file ({@code rows[].key}). */
    public static List<String> readCaseList(Path path) {
        try {
            JsonNode rows = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8)).path("rows");
            List<String> keys = new ArrayList<>();
            for (JsonNode row : rows) {
                JsonNode key = row.get("key");
                if (key == null || !key.isTextual()) {
                    throw new CollectException("case list " + path + ": a row has no key");
                }
                keys.add(key.asText());
            }
            return keys;
        } catch (IOException e) {
            throw new CollectException("cannot read case list " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * The plan's cached case list, else the shipped seed, else empty: the same files, in the same order of preference,
     * that the plan catalogue reads cache-only.
     *
     * @param cacheDir the folder of cached case lists (the Python CAT's {@code ~/.cat})
     * @param seedDir  the folder of shipped seeds
     */
    public static Optional<Path> defaultCaseFile(String plan, Path cacheDir, Path seedDir) {
        String name = "cases-" + plan + ".json";
        for (Path candidate : List.of(cacheDir.resolve(name), seedDir.resolve(name))) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** The pulled files of one collection, with the destination of each name a pull could not write. */
    private static final class Pulls {

        private final Path target;
        private final Path root;
        /** Casefolded path below {@code target} to the blob sha already queued for it. */
        private final Map<String, String> written = new HashMap<>();
        final List<Pending> toWrite = new ArrayList<>();

        Pulls(Path target, Path root) {
            this.target = target;
            this.root = root;
        }

        /** Queues one file for {@code target/rel}, or says why not. */
        CaseResult.Pulled pull(List<Blob> blobs, String rel, String reason) {
            String why = reason;
            if (why.isEmpty() && blobs.stream().map(Blob::sha).distinct().count() > 1) {
                why = "both trees hold this name with different contents";
            }
            String sha = blobs.get(0).sha();
            String key = Selection.casefold(rel);
            if (why.isEmpty() && !sha.equals(written.getOrDefault(key, sha))) {
                why = "another case already pulled a different " + rel;
            }
            if (!why.isEmpty() || target == null) {
                return new CaseResult.Pulled(blobs.get(0).path(), null, why.isEmpty() ? DRY_RUN : why);
            }
            Path dest = target.resolve(rel);
            if (!written.containsKey(key)) { // a second case sharing the same file
                written.put(key, sha);
                toWrite.add(new Pending(rel, dest, sha));
            }
            return new CaseResult.Pulled(blobs.get(0).path(), displayPath(dest, root), "");
        }
    }
}
