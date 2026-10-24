package com.verifone.cat.web;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryEntry;
import com.verifone.cat.build.ArtifactoryException;
import com.verifone.cat.build.BuildCatalogue;
import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.BuildDownloads;
import com.verifone.cat.build.BuildException;
import com.verifone.cat.build.EnvArtifactoryCredentials;
import com.verifone.cat.launch.UpgradeMode;

/**
 * The Downloads choice both Config and Install Upgrade show, its Artifactory listings, and the
 * one background action (a Commander download, or an install) the pages can have in flight.
 *
 * <p>The choice is held once: changing a build on one page changes it on the other, as the TUI's
 * two tabs share it. Listings run on a worker thread and the pages poll for them, so a slow
 * Artifactory never holds a request open. Each listing is asked once and kept until Refresh.
 *
 * <p>Ported from the Python CAT's {@code cat/web/downloads.py} ({@code DownloadsModel}), for the
 * parts this port has: branches and builds (read-only), this PC's builds, and the Commander download
 * of a {@code <dist>} folder. Not built yet: the flavour folders ({@code viper-}, {@code foundation-},
 * {@code sc-}, {@code retail-}), which need {@code petrotest/build/nightly.py build_contents} and
 * {@code petrotest/build/overlay_merge.py}; a Simulator Build download ({@code cat/sims/fetch.py}).
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER: tests run against {@code FakeArtifactory}.
 */
final class DownloadsService {

    /** The distribution a choice starts from: the Python CAT's {@code DEFAULT_DIST}. */
    static final String DEFAULT_DIST = "buypass";

    /** The value of "Newest on this PC" in the Commander build picker (under Don't download). */
    static final String NEWEST_LOCAL = "newest";

    /** The key the branch listing and its error are recorded under. */
    static final String BRANCHES = "branches";

    static final String COMMANDER_JAR = "Petro_Suite_Installer.jar";
    static final String AUTO_UPGRADE_JAR = "Auto_Upgrade.jar";
    static final String OVERLAY = BuildDiscovery.OVERLAY;

    private static final Pattern DIST = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
    private static final Pattern LOCAL_NAME = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern DIGITS = Pattern.compile("\\d*");
    private static final long LISTING_TIMEOUT_MINUTES = 10L;

    /** One Downloads choice, as a snapshot. */
    record Choice(String commanderBranch, String commanderBuild, String localBuild, String simBranch,
            String simBuild, UpgradeMode mode, String dist) {

        boolean commanderChosen() {
            return !commanderBranch.isEmpty() && !commanderBuild.isEmpty();
        }

        boolean simChosen() {
            return !simBranch.isEmpty();
        }

        /** The Commander build as the page names it: {@code master 268}, or this PC's folder name. */
        String commanderLabel() {
            if (!commanderBranch.isEmpty()) {
                return commanderBranch + " " + commanderBuild;
            }
            return localBuild.isEmpty() ? "the newest build on this PC" : localBuild;
        }
    }

    private final Supplier<ArtifactoryClient> clients;
    private final Path root;
    private final ExecutorService listings = pool("cat-web-listing");
    private final ExecutorService work = pool("cat-web-work");
    private final JobLog job = new JobLog();
    private final Object lock = new Object();

    // Every field below is guarded by lock.
    private String commanderBranch = "";
    private String commanderBuild = "";
    private String localBuild = "";
    private String simBranch = "";
    private String simBuild = "";
    private UpgradeMode mode = UpgradeMode.SKIP;
    private String dist = DEFAULT_DIST;
    private List<String> branches;
    private final Map<String, List<BuildCatalogue.NightlyBuild>> builds = new HashMap<>();
    private final Map<String, List<String>> dists = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();
    private final List<Future<?>> tasks = new ArrayList<>();
    private String busy = "";
    private String notice = "";

    DownloadsService(Supplier<ArtifactoryClient> clients, Path root) {
        this.clients = clients;
        this.root = root;
    }

    /** The real service: the Artifactory login from the environment, and {@code CAT_BUILDS_ROOT}. */
    static DownloadsService standard(Map<String, String> env) {
        return new DownloadsService(() -> new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                new EnvArtifactoryCredentials(env), Duration.ofMinutes(LISTING_TIMEOUT_MINUTES)),
                BuildDiscovery.buildsRoot(env));
    }

    Path root() {
        return root;
    }

    // ------------------------------------------------------------ the choice

    Choice choice() {
        synchronized (lock) {
            return new Choice(commanderBranch, commanderBuild, localBuild, simBranch, simBuild, mode, dist);
        }
    }

    /**
     * Sets one choice field from the page. Returns "" or the reason it was refused; a refused value
     * is never stored and never echoed.
     *
     * @param field one of {@code commanderBranch}, {@code commanderBuild}, {@code simBranch},
     *              {@code simBuild}, {@code mode}, {@code dist}
     */
    String set(String field, String value) {
        String v = value == null ? "" : value.strip();
        synchronized (lock) {
            try {
                switch (field) {
                    case "commanderBranch" -> {
                        checkBranch(v);
                        if (!v.equals(commanderBranch)) {
                            commanderBranch = v;
                            commanderBuild = "";
                            localBuild = "";
                        }
                    }
                    case "commanderBuild" -> {
                        if (commanderBranch.isEmpty()) {
                            localBuild = v.isEmpty() || NEWEST_LOCAL.equals(v) ? "" : checkedLocalName(v);
                        } else {
                            commanderBuild = v.isEmpty() ? "" : BuildDiscovery.buildNumber(v);
                        }
                    }
                    case "simBranch" -> {
                        checkBranch(v);
                        if (!v.equals(simBranch)) {
                            simBranch = v;
                            simBuild = "";
                        }
                    }
                    case "simBuild" -> {
                        if (!DIGITS.matcher(v).matches()) {
                            throw new BuildException("a Simulator Build is a build number");
                        }
                        simBuild = v;
                    }
                    case "mode" -> {
                        if (v.isEmpty()) {
                            throw new BuildException("pick an Upgrade mode");
                        }
                        mode = UpgradeMode.of(v);
                    }
                    case "dist" -> {
                        if (!DIST.matcher(v).matches()) {
                            throw new BuildException("a Distribution is one word, such as " + DEFAULT_DIST);
                        }
                        dist = v;
                    }
                    default -> throw new BuildException("unknown choice");
                }
                return "";
            } catch (BuildException | IllegalArgumentException e) {
                return message(e);
            }
        }
    }

    private static void checkBranch(String branch) {
        if (!branch.isEmpty()) {
            BuildCatalogue.branchPath(branch);
        }
    }

    private static String checkedLocalName(String name) {
        if (!LOCAL_NAME.matcher(name).matches() || name.contains("..")) {
            throw new BuildException("not a build folder on this PC");
        }
        return name;
    }

    // ------------------------------------------------------------ state for the pages

    String notice() {
        synchronized (lock) {
            return notice;
        }
    }

    void notice(String text) {
        synchronized (lock) {
            notice = text == null ? "" : text;
        }
    }

    /** The action in flight: {@code download}, {@code install}, or "" when none. */
    String busy() {
        synchronized (lock) {
            return busy;
        }
    }

    JobLog job() {
        return job;
    }

    /** Whether any listing is still being asked for. */
    boolean listingPending() {
        synchronized (lock) {
            return !pending.isEmpty();
        }
    }

    /** The branches, or null until they are listed. */
    List<String> branches() {
        synchronized (lock) {
            return branches == null ? null : List.copyOf(branches);
        }
    }

    /** A branch's numbered builds, newest first, or null until listed. */
    List<BuildCatalogue.NightlyBuild> builds(String branch) {
        synchronized (lock) {
            return builds.get(branch);
        }
    }

    /** A build's dist folders, or null until read. */
    List<String> dists(String branch, String build) {
        synchronized (lock) {
            return dists.get(distsKey(branch, build));
        }
    }

    /** The listing error for {@code key}, or "". */
    String error(String key) {
        synchronized (lock) {
            return errors.getOrDefault(key, "");
        }
    }

    static String buildsKey(String branch) {
        return "builds:" + branch;
    }

    static String distsKey(String branch, String build) {
        return "dists:" + branch + "/" + build;
    }

    /** This PC's builds for the mode and dist, newest first (never a network call). */
    List<Path> localBuilds(UpgradeMode m, String distName) {
        return BuildDiscovery.localBuildDirs(root, distName, jarsFor(m));
    }

    /** This PC's dist folders that hold an overlay. */
    List<String> localDists() {
        return BuildDiscovery.localDists(root);
    }

    /** The build folder an install would use: the chosen local one, else the newest that qualifies. */
    Optional<Path> localBuildDir(Choice c) {
        if (c.localBuild().isEmpty()) {
            return BuildDiscovery.latestBuildDir(root, c.dist(), jarsFor(c.mode()));
        }
        return Optional.of(root.resolve(c.localBuild()).resolve(c.dist()));
    }

    /** The files a mode's install needs beside {@code overlay.zip}. */
    static List<String> jarsFor(UpgradeMode m) {
        return switch (m) {
            case NEW_INSTALL -> List.of(COMMANDER_JAR, AUTO_UPGRADE_JAR);
            case CLASSIC -> List.of(AUTO_UPGRADE_JAR);
            default -> List.of();
        };
    }

    // ------------------------------------------------------------ listings

    /** Asks for whatever the choice needs and has not been asked for yet. Never blocks. */
    void ensureListings() {
        synchronized (lock) {
            if (branches == null) {
                want(BRANCHES, () -> listBranches(clients.get()));
            }
            wantBuilds(commanderBranch);
            wantBuilds(simBranch);
            if (!commanderBranch.isEmpty() && !commanderBuild.isEmpty()) {
                String branch = commanderBranch;
                String number = commanderBuild;
                if (!dists.containsKey(distsKey(branch, number))) {
                    want(distsKey(branch, number), () -> listDists(clients.get(), branch, number));
                }
            }
        }
    }

    /** Caller holds lock. */
    private void wantBuilds(String branch) {
        if (!branch.isEmpty() && !builds.containsKey(branch)) {
            want(buildsKey(branch), () -> listBuilds(clients.get(), branch));
        }
    }

    /** Refresh listings: forget every answer (this PC's builds are read on each view) and ask again. */
    void refresh() {
        synchronized (lock) {
            branches = null;
            builds.clear();
            dists.clear();
            errors.clear();
        }
        ensureListings();
    }

    /** Caller holds lock. Runs {@code listing} on the listing thread, once per key until Refresh. */
    private void want(String key, Runnable listing) {
        if (pending.contains(key) || errors.containsKey(key)) {
            return;
        }
        pending.add(key);
        tasks.removeIf(Future::isDone);
        tasks.add(listings.submit(() -> {
            try {
                listing.run();
            } catch (RuntimeException e) {
                synchronized (lock) {
                    errors.put(key, message(e));
                }
            } finally {
                synchronized (lock) {
                    pending.remove(key);
                }
            }
        }));
    }

    private void listBranches(ArtifactoryClient client) {
        List<String> names = new BuildCatalogue(client).listBranches();
        synchronized (lock) {
            branches = names;
        }
    }

    private void listBuilds(ArtifactoryClient client, String branch) {
        List<BuildCatalogue.NightlyBuild> found = new BuildCatalogue(client).listBuildEntries(branch);
        synchronized (lock) {
            builds.put(branch, found);
        }
    }

    private void listDists(ArtifactoryClient client, String branch, String number) {
        List<String> names = new ArrayList<>();
        for (String folder : folderNames(client, buildPath(branch, number))) {
            if (DIST.matcher(folder).matches()) {
                names.add(folder);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        synchronized (lock) {
            dists.put(distsKey(branch, number), names);
        }
    }

    /** Folder children of {@code path}, by name; a missing folder is a BuildException naming it. */
    private static List<String> folderNames(ArtifactoryClient client, String path) {
        List<String> names = new ArrayList<>();
        try {
            for (ArtifactoryEntry entry : client.listFolder(path)) {
                if (entry.folder()) {
                    names.add(entry.name());
                }
            }
        } catch (ArtifactoryException e) {
            if (e.status() == 404) {
                throw new BuildException(path + " is not on Artifactory", e);
            }
            throw e;
        }
        return names;
    }

    private static String buildPath(String branch, String number) {
        return BuildCatalogue.branchPath(branch) + "/" + BuildDiscovery.buildNumber(number);
    }

    // ------------------------------------------------------------ the one action

    /**
     * Downloads the chosen Commander build's files for the mode, to the builds root, in the background.
     *
     * @return "" when started, else the reason it was refused
     */
    String fetch() {
        Choice c = choice();
        if (c.commanderBranch().isEmpty()) {
            return "Pick a Commander Build on Artifactory to download. Don't download fetches nothing.";
        }
        if (c.commanderBuild().isEmpty()) {
            return "Pick a Commander Build number first.";
        }
        return begin("download", "Download", "Download done: the build is on this PC.", log -> {
            fetchCommander(c, log);
            return "";
        });
    }

    /**
     * Starts {@code task} on the worker thread under the one-action gate. {@code task} returns "" on
     * success or the reason it stopped; an exception it throws is its reason too.
     *
     * @return "" when started, else why not
     */
    String begin(String kind, String title, String okMessage, Function<JobLog, String> task) {
        synchronized (lock) {
            if (!busy.isEmpty()) {
                return (busy.equals("download") ? "A download" : "An install") + " is already in progress.";
            }
            busy = kind;
            job.start(title);
            tasks.removeIf(Future::isDone);
            tasks.add(work.submit(() -> {
                String error;
                try {
                    error = task.apply(job);
                } catch (RuntimeException e) {
                    error = message(e);
                }
                job.finish(error, okMessage);
                synchronized (lock) {
                    busy = "";
                }
            }));
        }
        return "";
    }

    /**
     * The Commander build's dist folder, downloaded for the choice's mode into {@link BuildDiscovery#buildDir}.
     * A file already there at Artifactory's size is kept, as the Python does.
     *
     * @throws BuildException when the build, the dist folder or a file is not there, or not built yet
     */
    Path fetchCommander(Choice c, JobLog log) {
        if (c.mode() == UpgradeMode.VHQ) {
            throw new BuildException("VHQ is not built yet (petrotest/upgrade/vhq.py)");
        }
        String branch = c.commanderBranch();
        String number = BuildDiscovery.buildNumber(c.commanderBuild());
        String distName = c.dist();
        ArtifactoryClient client = clients.get();
        String buildPath = buildPath(branch, number);
        List<String> folders = folderNames(client, buildPath);
        String folder = folders.stream().filter(name -> name.equalsIgnoreCase(distName)).findFirst()
                .orElseThrow(() -> new BuildException(missingDist(branch, number, distName, folders)));
        List<String> names = new ArrayList<>();
        names.add(OVERLAY);
        names.addAll(jarsFor(c.mode()));
        Path dest = BuildDiscovery.buildDir(branch, number, distName, root);
        String folderPath = buildPath + "/" + folder;
        List<JobLog.Transfer> files = new ArrayList<>();
        for (String name : names) {
            files.add(new JobLog.Transfer(name, dest.resolve(name),
                    client.fileInfo(folderPath + "/" + name).size()));
        }
        job.transfers(files);
        log.line(branch + " " + number + ", " + folder + " -> " + dest);
        BuildDownloads.fetchFiles(client, folderPath, names, dest, log::line);
        return dest;
    }

    /** Why a build has no folder for the dist, naming a flavour folder as not built yet when that is what it holds. */
    static String missingDist(String branch, String number, String distName, List<String> folders) {
        String suffix = "-" + distName.toLowerCase(Locale.ROOT);
        List<String> flavours = folders.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(suffix)).toList();
        if (!flavours.isEmpty()) {
            return branch + " " + number + " holds only " + String.join(", ", flavours)
                    + ": a viper, foundation, sc or retail folder is not built yet"
                    + " (petrotest/build/nightly.py build_contents, petrotest/build/overlay_merge.py)";
        }
        return branch + " " + number + " holds no " + distName + " folder (" + String.join(", ", folders)
                + "): pick another build or Distribution.";
    }

    /** Waits for every listing and action started so far, up to {@code timeout} each. For tests. */
    void awaitIdle(Duration timeout) {
        List<Future<?>> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(tasks);
        }
        for (Future<?> task : snapshot) {
            try {
                task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                // A failed listing is kept in errors; the caller's assertions show it.
            }
        }
    }

    static String message(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static ExecutorService pool(String name) {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
