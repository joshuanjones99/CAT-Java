package com.verifone.cat.sims;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryErrors;
import com.verifone.cat.build.ArtifactoryException;
import com.verifone.cat.sims.SimSources.ArtifactorySource;
import com.verifone.cat.sims.base.JavaSets;
import com.verifone.cat.sims.base.NightlyBuilds;
import com.verifone.cat.sims.base.SuiteBase;
import com.verifone.cat.sims.base.Trees;
import com.verifone.cat.sims.base.Unzip;

/**
 * Replaces staged simulators with one nightly build's copies: the Python CAT's {@code cat.sims.fetch.fetch_build},
 * with {@code build_rows}, {@code _install}, {@code _place}, {@code _replace_dir}, {@code _replace_contents},
 * {@code _rename_retrying} and {@code setup}.
 *
 * <p>Rig owner, 2026-10-07: a chosen Simulator Build replaces the staged simulators completely, config files and all.
 * Each row's source is read from {@code RMS_Nightly_CLW/Petro/<branch>} ({@link ArtifactorySource#onBranch}), its
 * folder and zip unchanged. A row is refused, its staged copy untouched, when it has no source, the build has no such
 * folder, it is running, its zip is not in the build or does not download whole or does not hold the row's
 * directory, or its staged directory cannot be moved aside. Rows that share a zip (the three LoySims) download it once.
 * Each placed row runs its {@code after} setup and gets an install record ({@link SuiteBase#MARKER}).
 *
 * <p>Deviation, asked for by the orchestrator (2026-10-28): a row whose install record already names this branch and
 * build is not downloaded again; its result is a pass that says so. The Python replaces it again every run, which on
 * the lab's saved Simulator Build ({@code master 129}) re-downloads every simulator at every run and refuses (fails
 * step 1) whenever one is still running. The client is made only when a row has to be downloaded.
 *
 * <p>Not ported: {@code fetch_missing} and {@code fetch}, the never-overwrite path, which no run calls since the
 * Simulator Build replaced the Upgrade Simulators switch (2026-10-07).
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER AND HARDWARE (Java port): tested against {@code FakeArtifactory}
 * and a {@code @TempDir} tools root.
 */
public final class SimFetch {

    /** One row's outcome: replaced (or already that build), or why not. */
    public record FetchResult(String name, boolean ok, String detail) {
    }

    /** Row name to why it is running, for the rows of {@code entries} that are; the refusal test. */
    @FunctionalInterface
    public interface Running {
        Map<String, String> apply(List<SimEntry> entries, SimContext ctx);
    }

    /** Moves one file or folder; the test seam for Windows' refusals. */
    @FunctionalInterface
    interface Mover {
        void move(Path src, Path dst) throws IOException;
    }

    /** Runs a row's {@code after} setup: exit code, and stderr into {@code err}. */
    @FunctionalInterface
    interface Exec {
        int run(List<String> argv, Path cwd, Duration timeout, StringBuilder err) throws IOException;
    }

    /** A zip download that fails in transport is tried this many times in all. */
    static final int DOWNLOAD_ATTEMPTS = 3;
    static final Duration DOWNLOAD_RETRY_PAUSE = Duration.ofSeconds(3);

    /** How long a refused rename is retried, and how often: an antivirus or indexer pass over fresh files. */
    static final Duration RENAME_RETRY = Duration.ofSeconds(20);
    static final Duration RENAME_INTERVAL = Duration.ofMillis(500);

    /** The setup step's budget, as the Python's {@code timeout=120}. */
    static final Duration SETUP_TIMEOUT = Duration.ofSeconds(120);

    private static final String SHARING_VIOLATION = "being used by another process";

    private final Supplier<ArtifactoryClient> clients;
    private final Consumer<String> log;
    private ArtifactoryClient client;
    Mover mover = (src, dst) -> Files.move(src, dst);
    Exec exec = SimFetch::execute;
    Duration renameRetry = RENAME_RETRY;
    Duration renameInterval = RENAME_INTERVAL;
    Duration downloadRetryPause = DOWNLOAD_RETRY_PAUSE;

    /**
     * @param clients makes the Artifactory client, once, when a row has to be downloaded; it may throw (no login)
     * @param log     hears each progress line
     */
    public SimFetch(Supplier<ArtifactoryClient> clients, Consumer<String> log) {
        this.clients = clients;
        this.log = log == null ? line -> { } : log;
    }

    private ArtifactoryClient client() {
        if (client == null) {
            client = clients.get();
        }
        return client;
    }

    /**
     * The rows a chosen Simulator Build replaces: those {@code selected}, and every {@code stagedOnly} row, in
     * registry order. The Python's {@code build_rows}.
     */
    public static List<SimEntry> buildRows(List<SimEntry> entries, Collection<String> selected) {
        Set<String> chosen = new LinkedHashSet<>(selected);
        return entries.stream().filter(e -> chosen.contains(e.name()) || e.stagedOnly()).toList();
    }

    /**
     * Replaces each of {@code entries} with build {@code build} of {@code branch}'s copy; one result per row, in order.
     * Never throws for a row's problem. Making the client (the login) may throw, before anything is changed.
     *
     * @param olderBase the base form the entries are in, which picks each row's source
     * @param running   which rows are running; such a row is refused
     */
    public List<FetchResult> fetchBuild(List<SimEntry> entries, SimContext ctx, String branch, String build,
            boolean olderBase, Running running) {
        String number = build == null ? "" : build.strip();
        String branchName = NightlyBuilds.branchName(branch);
        Map<String, String> results = new LinkedHashMap<>();
        Map<String, String> already = new LinkedHashMap<>();
        List<SimEntry> rest = new ArrayList<>();
        for (SimEntry entry : entries) {
            Path cwd = ctx.cwdFor(entry);
            Optional<SuiteBase.Marker> marker = Files.isDirectory(cwd) ? SuiteBase.readMarker(cwd) : Optional.empty();
            if (marker.isPresent() && marker.get().branch().equals(branchName) && marker.get().build().equals(number)) {
                already.put(entry.name(), entry.name() + ": already " + branchName + " build " + number
                        + " (its install record), so it was not downloaded again");
            } else {
                rest.add(entry);
            }
        }
        if (!rest.isEmpty()) {
            results.putAll(replace(rest, ctx, branch, number, olderBase, running));
        }
        List<FetchResult> out = new ArrayList<>();
        for (SimEntry entry : entries) {
            if (already.containsKey(entry.name())) {
                out.add(new FetchResult(entry.name(), true, already.get(entry.name())));
                continue;
            }
            String problem = results.get(entry.name());
            out.add(new FetchResult(entry.name(), problem.isEmpty(),
                    problem.isEmpty() ? entry.name() + ": replaced with " + branch + " build " + number + "'s copy"
                            : problem));
        }
        return out;
    }

    private Map<String, String> replace(List<SimEntry> entries, SimContext ctx, String branch, String build,
            boolean olderBase, Running running) {
        Map<String, String> results = new LinkedHashMap<>();
        List<String> folders;
        try {
            folders = NightlyBuilds.buildFolders(client(), branch, build);
        } catch (NightlyBuilds.NotFound | IllegalArgumentException e) {
            for (SimEntry entry : entries) {
                results.put(entry.name(), entry.name() + ": " + e.getMessage());
            }
            return results;
        } catch (ArtifactoryException e) {
            for (SimEntry entry : entries) {
                results.put(entry.name(), entry.name() + ": " + ArtifactoryErrors.describe(e, ""));
            }
            return results;
        }
        Map<String, ArtifactorySource> sources = new LinkedHashMap<>();
        for (SimEntry entry : entries) {
            Optional<ArtifactorySource> registered = SimSources.forRow(entry.name(), olderBase);
            if (registered.isEmpty()) {
                results.put(entry.name(), entry.name() + ": no Artifactory source in the registry");
                continue;
            }
            ArtifactorySource source;
            try {
                source = registered.get().onBranch(branch);
            } catch (IllegalArgumentException e) {
                results.put(entry.name(), entry.name() + ": bad artifactory: block (" + e.getMessage() + ")");
                continue;
            }
            if (!folders.contains(source.folder())) {
                String why = NightlyBuilds.branchName(branch).equals(NightlyBuilds.SOLUTIONS)
                        ? "Solutions builds carry no simulators; pick a master or ticket branch build"
                        : branch + " " + build + " has no " + source.folder() + "/";
                results.put(entry.name(), entry.name() + ": " + why);
                continue;
            }
            sources.put(entry.name(), source);
        }
        List<SimEntry> candidates = entries.stream().filter(e -> sources.containsKey(e.name())).toList();
        Map<String, String> busy;
        try {
            busy = candidates.isEmpty() ? Map.of() : running.apply(candidates, ctx);
        } catch (RuntimeException e) { // a typo'd registry placeholder, say: no row is safe to swap
            busy = new LinkedHashMap<>();
            for (SimEntry entry : candidates) {
                busy.put(entry.name(), "could not tell: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        List<Row> rows = new ArrayList<>();
        for (SimEntry entry : candidates) {
            if (busy.containsKey(entry.name())) {
                results.put(entry.name(), entry.name() + ": running (" + busy.get(entry.name())
                        + "); stop it before replacing its files");
            } else {
                rows.add(new Row(entry, sources.get(entry.name()), build));
            }
        }
        results.putAll(install(rows, ctx));
        return results;
    }

    /**
     * Replaces {@code entries} with the newest matching build's copies, as {@link #fetchBuild} does, without the
     * install-record shortcut: the stale rows of a Commander base mismatch, which are by definition not that build.
     */
    public List<FetchResult> replaceStale(List<SimEntry> entries, SimContext ctx, String branch, String build,
            boolean olderBase, Running running) {
        Map<String, String> results = replace(entries, ctx, branch, build, olderBase, running);
        List<FetchResult> out = new ArrayList<>();
        for (SimEntry entry : entries) {
            String problem = results.get(entry.name());
            out.add(new FetchResult(entry.name(), problem.isEmpty(),
                    problem.isEmpty() ? entry.name() + ": replaced with " + branch + " build " + build + "'s copy"
                            : problem));
        }
        return out;
    }

    private record Row(SimEntry entry, ArtifactorySource source, String build) {
    }

    private record ZipKey(String repo, String build, String folder, String file) {
    }

    /** Downloads each distinct zip once, then puts every row's directory in place. Row name to "" or the problem. */
    private Map<String, String> install(List<Row> rows, SimContext ctx) {
        Map<String, String> problems = new LinkedHashMap<>();
        Map<ZipKey, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(new ZipKey(row.source().repo(), row.build(), row.source().folder(),
                    row.source().file()), k -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<ZipKey, List<Row>> group : groups.entrySet()) {
            ZipKey key = group.getKey();
            List<Row> members = group.getValue();
            String names = String.join(", ", members.stream().map(r -> r.entry().name()).toList());
            log.accept(names + ": downloading " + key.repo() + "/" + key.build() + "/" + key.folder() + "/"
                    + key.file());
            Path scratch;
            try {
                Files.createDirectories(ctx.toolsRoot());
                scratch = Files.createTempDirectory(ctx.toolsRoot(), ".cat-fetch-");
            } catch (IOException e) {
                for (Row row : members) {
                    problems.put(row.entry().name(), row.entry().name() + ": could not make a scratch folder under "
                            + ctx.toolsRoot() + " (" + e.getMessage() + ")");
                }
                continue;
            }
            try {
                Path unpacked;
                try {
                    unpacked = downloadAndUnpack(members.get(0).source(), key.build(), scratch);
                } catch (IOException | RuntimeException e) {
                    String why = downloadProblem(e, key.build(), key.folder(), key.file());
                    for (Row row : members) {
                        problems.put(row.entry().name(), row.entry().name() + ": " + why);
                    }
                    continue;
                }
                for (Row row : members) {
                    problems.put(row.entry().name(), place(row, unpacked, ctx));
                }
            } finally {
                Trees.deleteQuietly(scratch);
            }
        }
        return problems;
    }

    private Path downloadAndUnpack(ArtifactorySource source, String build, Path scratch) throws IOException {
        String path = source.repo() + "/" + build + "/" + source.folder() + "/" + source.file();
        ArtifactoryClient artifactory = client();
        ArtifactoryClient.FileInfo info = artifactory.fileInfo(path);
        Path archive = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                archive = artifactory.downloadFile(artifactory.fileUrl(path), scratch.resolve(source.file()),
                        info.size(), info.sha256(), ArtifactoryClient.DEFAULT_WORKERS);
                break;
            } catch (ArtifactoryException e) {
                if (e.status() != ArtifactoryException.NO_RESPONSE || attempt == DOWNLOAD_ATTEMPTS) {
                    throw e;
                }
                Park.park(downloadRetryPause);
            }
        }
        Path unpacked = scratch.resolve("unpacked");
        Unzip.extract(archive, unpacked);
        Files.deleteIfExists(archive);
        return unpacked;
    }

    private static String downloadProblem(Exception e, String build, String folder, String file) {
        if (e instanceof ArtifactoryException failure && failure.status() == 404) {
            return "build " + build + " has no " + folder + "/" + file;
        }
        return JavaSets.artifactoryFailure(e);
    }

    /** Moves one row's directory out of {@code unpacked} into the tools root, then sets it up. "" or the problem. */
    private String place(Row row, Path unpacked, SimContext ctx) {
        SimEntry entry = row.entry();
        ArtifactorySource source = row.source();
        Path target = ctx.cwdFor(entry);
        Path staged = source.stagedIn(unpacked, target);
        if (!Files.isDirectory(staged)) {
            return entry.name() + ": " + source.file() + " holds no "
                    + (source.holds().isEmpty() ? target.getFileName().toString() : source.holds()) + "/";
        }
        String problem = replaceDir(staged, target);
        if (!problem.isEmpty()) {
            return entry.name() + ": " + problem;
        }
        log.accept(entry.name() + ": replaced with build " + row.build() + "'s copy in " + target);
        String setup = setup(entry, source, ctx, target);
        try { // which base this copy is for, read back by SuiteBase.installedBase
            SuiteBase.writeMarker(target, source.repo(), row.build());
        } catch (IOException e) {
            log.accept(entry.name() + ": could not record what was installed (" + e.getMessage() + ")");
        }
        return setup;
    }

    /**
     * Puts {@code fresh} where {@code target} is, the old copy moved aside first: two renames on one volume, so the
     * row is never half-replaced. A sharing violation on the folder itself, with every file in it free, swaps its
     * contents instead. "" or why not.
     */
    String replaceDir(Path fresh, Path target) {
        Path old = null;
        if (Files.exists(target)) {
            old = target.resolveSibling(".cat-old-" + target.getFileName() + "-" + hex8());
            try {
                renameRetrying(target, old);
            } catch (IOException e) {
                if (!sharingViolation(e)) {
                    return "its files are in use, so the staged copy stays (" + describe(e) + ")";
                }
                String problem = replaceContents(fresh, target, old);
                if (!problem.isEmpty()) {
                    return "its files are in use, so the staged copy stays (" + problem + ")";
                }
                log.accept(target.getFileName() + ": a process has the folder itself open (a console sitting in "
                        + "it?), so its contents were replaced in place");
                Trees.deleteQuietly(old);
                return "";
            }
        }
        try {
            renameRetrying(fresh, target);
        } catch (IOException e) {
            if (old != null) {
                try {
                    mover.move(old, target);
                } catch (IOException ignored) {
                    // The old copy stays where it was moved; the message says the staged copy stays.
                }
            }
            return "could not move the new copy into place (" + describe(e) + "); the staged copy stays";
        }
        if (old != null) {
            Trees.deleteQuietly(old);
            if (Files.exists(old)) {
                log.accept("left the previous copy's remains at " + old);
            }
        }
        return "";
    }

    /** Moves {@code target}'s children into {@code old}, then {@code fresh}'s into {@code target}; undone on failure. */
    private String replaceContents(Path fresh, Path target, Path old) {
        List<Path[]> moved = new ArrayList<>(); // {where it is now, where it was}
        try {
            Files.createDirectory(old);
            for (Path child : children(target)) {
                renameRetrying(child, old.resolve(child.getFileName().toString()));
                moved.add(new Path[] {old.resolve(child.getFileName().toString()), child});
            }
            for (Path child : children(fresh)) {
                renameRetrying(child, target.resolve(child.getFileName().toString()));
                moved.add(new Path[] {target.resolve(child.getFileName().toString()), child});
            }
        } catch (IOException e) {
            for (int i = moved.size() - 1; i >= 0; i--) {
                try {
                    mover.move(moved.get(i)[0], moved.get(i)[1]);
                } catch (IOException ignored) {
                    // Best effort, as the Python's contextlib.suppress(OSError).
                }
            }
            try {
                Files.deleteIfExists(old);
            } catch (IOException ignored) {
                // Left.
            }
            return describe(e);
        }
        return "";
    }

    private static List<Path> children(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.toList();
        }
    }

    /** {@code mover.move(src, dst)}, retried through {@link #renameRetry} while Windows refuses it transiently. */
    void renameRetrying(Path src, Path dst) throws IOException {
        long deadline = System.nanoTime() + renameRetry.toNanos();
        while (true) {
            try {
                mover.move(src, dst);
                return;
            } catch (IOException e) {
                if (!transient_(e) || System.nanoTime() >= deadline) {
                    throw e;
                }
            }
            Park.park(renameInterval);
        }
    }

    /** Windows' two transient refusals: access denied (5) and a sharing violation (32). */
    static boolean transient_(IOException e) {
        return e instanceof AccessDeniedException || sharingViolation(e);
    }

    /** A sharing violation (Windows error 32): a process holds the path open. */
    static boolean sharingViolation(IOException e) {
        String reason = e instanceof FileSystemException fs && fs.getReason() != null ? fs.getReason()
                : String.valueOf(e.getMessage());
        return reason.contains(SHARING_VIOLATION);
    }

    private static String describe(IOException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String hex8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * Runs {@code source.after} in {@code cwd} with the JDK's {@code java.exe}: after unpacking, as the vendor's start
     * script does. "" or the problem; never throws.
     */
    String setup(SimEntry entry, ArtifactorySource source, SimContext ctx, Path cwd) {
        if (source.after().isEmpty()) {
            return "";
        }
        Optional<Path> javaw = ctx.javawExe();
        Path java = javaw.map(exe -> exe.resolveSibling("java.exe")).orElse(null);
        if (java == null || !Files.isRegularFile(java)) {
            return entry.name() + ": unpacked, but no java.exe to run its setup with";
        }
        List<String> argv = new ArrayList<>();
        argv.add(java.toString());
        StringBuilder err = new StringBuilder();
        int code;
        try {
            for (String arg : source.after()) {
                argv.add(ctx.substitute(arg, entry));
            }
            code = exec.run(argv, cwd, SETUP_TIMEOUT, err);
        } catch (IOException | RuntimeException e) {
            return entry.name() + ": setup failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (code != 0) {
            String text = err.toString().strip();
            return entry.name() + ": setup exited " + code + ": " + text.substring(0, Math.min(200, text.length()));
        }
        log.accept(entry.name() + ": setup done");
        return "";
    }

    /** The real {@link Exec}: the child's stdout discarded, its stderr kept, bounded by {@code timeout}. */
    private static int execute(List<String> argv, Path cwd, Duration timeout, StringBuilder err) throws IOException {
        Process process = new ProcessBuilder(argv).directory(cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = process.getErrorStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("TimeoutExpired: setup took over " + timeout.toSeconds() + " s");
            }
            try {
                err.append(stderr.get(5, TimeUnit.SECONDS));
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // The exit code still says how it went.
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while the setup ran", e);
        }
    }
}
