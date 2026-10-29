package com.verifone.cat.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.launch.UpgradeMode;

/**
 * Downloads one nightly Commander build's files for an install into the builds root: the Python's
 * {@code petrotest.build.nightly.fetch_commander_build}.
 *
 * <p>The directory ({@link BuildDiscovery#buildDir}) ends up holding {@code overlay.zip} and the mode's installer
 * jars ({@link InstallerJars#forMode}), ready for {@code cat-java upgrade --build-dir}.
 * <ul>
 *   <li><b>Every branch, Solutions included</b>: the build's listing picks the dist's folder
 *       ({@link BuildContents#sourceFor}, case-insensitive: {@code Buypass} finds {@code buypass/}); an installable
 *       one is downloaded as it is.</li>
 *   <li><b>Viper-only</b>: its overlay is merged onto a foundation's ({@link OverlayMerge}) for the mode's installer
 *       ({@link OverlayMerge.PackageType#forMode}), and the jars come from that foundation. With no foundation named,
 *       {@link FoundationPair#paired} finds the one the viper was built with and the log says how; with none, this
 *       throws rather than guess.</li>
 * </ul>
 * A file whose local size matches Artifactory's is not downloaded again. A merged build keeps
 * {@value #MERGE_STAMP} beside its overlay, naming the two overlays and the package type it was merged from, so the
 * same pair in the same mode downloads nothing again and a mode change merges again.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested against the loopback Artifactory fake only; no merged build has
 * been installed by CAT.
 */
public final class CommanderBuildFetch {

    /** The PSI install's jars, beside {@code overlay.zip}: the Python's {@code INSTALLER_JARS["new_install"]}. */
    public static final List<String> PSI_FILES = InstallerJars.NEW_INSTALL;

    /** Written beside a merged {@code overlay.zip}: the two overlays it came from, and its package type. */
    public static final String MERGE_STAMP = ".cat-merge.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private CommanderBuildFetch() {}

    /**
     * The files of an install ({@code overlay.zip} and {@code jars}) that {@code dest} does not hold, each by name, in
     * that order; empty when the build is already downloaded. A file counts when it is a non-empty regular file: a
     * download is written to {@code .part} and moved into place only once whole ({@link ArtifactoryClient}).
     *
     * <p>Deviation (CAT-Java, 2026-10-28): the launch skips Artifactory altogether when this is empty, so a controller
     * with no Artifactory login installs a build already on disk. The Python lists the folder and asks each file's size
     * first ({@code petrotest.build.nightly}), so it needs a login even then.
     */
    public static List<String> missingFiles(Path dest, List<String> jars) {
        List<String> names = new ArrayList<>();
        names.add(BuildDiscovery.OVERLAY);
        names.addAll(jars);
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            Path file = dest.resolve(name);
            try {
                if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                    missing.add(name);
                }
            } catch (IOException e) {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * {@link #fetchCommanderBuild} for the mode whose jars {@code jars} are ({@link InstallerJars#CLASSIC} is classic,
     * anything else a PSI install), with the paired foundation and no flavour: the call step 4's PSI download makes.
     *
     * @return the local build directory, {@code <root>\<label>\<dist>}
     */
    public static Path fetch(ArtifactoryClient client, String branch, String build, String dist, List<String> jars,
            Path root, Consumer<String> log) {
        UpgradeMode mode = InstallerJars.CLASSIC.equals(jars) ? UpgradeMode.CLASSIC : UpgradeMode.NEW_INSTALL;
        return fetchCommanderBuild(client, branch, build, dist, mode, null, null, root, log,
                new OverlayMerge(OverlayMerge.transitionDir(System.getenv())).merger());
    }

    /**
     * Downloads {@code branch}'s build {@code build} for {@code dist} and {@code mode}; returns its directory.
     *
     * @param foundation {@code [branch, build]} of the foundation to merge a viper-only build onto; null for the
     *                   paired one
     * @param flavour    install this flavour of {@code dist} ({@link BuildContents#FLAVOURS}) where a build holds two;
     *                   null for an installable one, else a viper one
     * @param merge      the merger
     * @throws BuildException the build, dist or a file is not there, no foundation pairs, or a download or merge
     *     failed
     * @throws IllegalArgumentException {@code mode} installs no build ({@code skip}, {@code vhq})
     */
    public static Path fetchCommanderBuild(ArtifactoryClient client, String branch, String build, String dist,
            UpgradeMode mode, List<String> foundation, String flavour, Path root, Consumer<String> log,
            OverlayMerge.Merger merge) {
        if (!InstallerJars.downloads(mode)) {
            throw new IllegalArgumentException("upgrade mode '" + mode.wire()
                    + "' installs no build; nothing to download");
        }
        if (flavour != null && !BuildContents.FLAVOURS.contains(flavour)) {
            throw new BuildException("unknown flavour '" + flavour + "'; one of "
                    + String.join(", ", BuildContents.FLAVOURS));
        }
        String name = ArtifactoryClient.trimSlashes(branch.strip());
        String number = BuildDiscovery.buildNumber(build);
        Path dest = BuildDiscovery.buildDir(name, number, dist, root);
        // Solutions too: the listing spells the folder (Artifactory's paths are case-sensitive).
        BuildContents contents = BuildContents.read(client, name, number);
        DistFolder source = contents.sourceFor(dist, flavour);
        if (source == null) {
            throw new BuildException(contents.describe(dist));
        }
        if (!source.installable()) {
            return fetchMerged(client, contents, source, dist, mode, dest, foundation, log, merge);
        }
        log.accept(contents.describe(dist));
        List<String> names = new ArrayList<>();
        names.add(BuildDiscovery.OVERLAY);
        names.addAll(InstallerJars.forMode(mode));
        BuildDownloads.fetchFiles(client, BuildCatalogue.branchPath(name) + "/" + number + "/" + source.folder(),
                names, dest, log);
        return dest;
    }

    /** A viper-only build: its overlay merged onto a foundation's for the mode's installer, with that one's jars. */
    private static Path fetchMerged(ArtifactoryClient client, BuildContents contents, DistFolder viper, String dist,
            UpgradeMode mode, Path dest, List<String> foundation, Consumer<String> log, OverlayMerge.Merger merge) {
        OverlayMerge.PackageType packageType = OverlayMerge.PackageType.forMode(mode);
        List<String> jars = InstallerJars.forMode(mode);
        String baseName;
        if (foundation == null) {
            FoundationPair pair = FoundationPair.paired(client, contents.branch(), contents.build(), dist);
            log.accept(contents.branch() + " " + contents.build() + " " + viper.folder() + " needs a foundation and "
                    + "none was chosen; " + pair.describe());
            foundation = pair.foundation();
            baseName = pair.folder();
        } else {
            BuildContents baseContents = BuildContents.read(client, foundation.get(0), foundation.get(1));
            DistFolder base = baseContents.sourceFor(dist, null);
            if (base == null || !base.installable()) {
                throw new BuildException(foundation.get(0) + " " + foundation.get(1) + " cannot be the foundation: "
                        + baseContents.describe(dist));
            }
            baseName = base.folder();
        }
        log.accept("merging " + contents.branch() + " " + contents.build() + " " + viper.folder() + " onto "
                + foundation.get(0) + " " + foundation.get(1) + " " + baseName + ", for " + packageType.name());
        String viperPath = BuildCatalogue.branchPath(contents.branch()) + "/" + contents.build() + "/" + viper.folder()
                + "/" + BuildDiscovery.OVERLAY;
        String baseFolder = BuildCatalogue.branchPath(foundation.get(0)) + "/"
                + BuildDiscovery.buildNumber(foundation.get(1)) + "/" + baseName;
        String basePath = baseFolder + "/" + BuildDiscovery.OVERLAY;
        // The package type is part of the pick: Auto Upgrade's overlay has six transition packages a PSI one lacks.
        ArtifactoryClient.FileInfo viperInfo = BuildDownloads.fileInfo(client, viperPath);
        ArtifactoryClient.FileInfo baseInfo = BuildDownloads.fileInfo(client, basePath);
        ObjectNode record = stamp(packageType, viperPath, viperInfo, basePath, baseInfo);

        BuildDownloads.fetchFiles(client, baseFolder, jars, dest, log);
        Path overlay = dest.resolve(BuildDiscovery.OVERLAY);
        Path stampFile = dest.resolve(MERGE_STAMP);
        JsonNode previous = readStamp(stampFile);
        if (Files.isRegularFile(overlay) && same(previous, record)) {
            log.accept("  overlay.zip: already merged from these two overlays, for " + packageType.name());
            return dest;
        }
        if (Files.isRegularFile(overlay) && previous != null && previous.isObject()) {
            JsonNode wasNode = previous.get("package_type");
            String was = wasNode == null ? "an unrecorded package type" : wasNode.asText();
            if (!was.equals(packageType.name())) {
                log.accept("  overlay.zip: was merged for " + was + "; merging again");
            }
        }

        Path inputs = dest.getParent().resolve(".inputs").resolve(dest.getFileName().toString());
        Path viperZip = BuildDownloads.fetchFile(client, viperPath, inputs.resolve("viper-overlay.zip"), log,
                viperInfo);
        Path baseZip = BuildDownloads.fetchFile(client, basePath, inputs.resolve("foundation-overlay.zip"), log,
                baseInfo);
        // An overlay merged from another pair goes first, with its stamp, and the new one is moved into place whole:
        // a merge that dies part-way leaves no overlay.zip a later run would take for this pick.
        Path merging = dest.resolve("overlay.merging.zip");
        try {
            Files.deleteIfExists(stampFile);
            Files.deleteIfExists(overlay);
            try {
                Path merged = merge.merge(baseZip, viperZip, merging, packageType, log);
                Files.move(merged, overlay, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(merging);
            }
            Files.writeString(stampFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record) + "\n",
                    StandardCharsets.UTF_8);
            // The inputs are two overlays of up to 1.2 GB each; the stamp is what skips a repeat.
            deleteTree(inputs);
            try {
                Files.deleteIfExists(inputs.getParent()); // only when no other dist's inputs are still in it
            } catch (IOException e) {
                // not empty: another dist's inputs are still there
            }
            log.accept("  overlay.zip: merged (" + BuildDownloads.bytes(Files.size(overlay)) + " bytes)");
        } catch (IOException e) {
            throw new BuildException("merging " + viper.folder() + " onto " + baseName + " failed: " + e.getMessage(),
                    e);
        }
        return dest;
    }

    /** The stamp a merge writes: the Python's record, its keys in sorted order. */
    private static ObjectNode stamp(OverlayMerge.PackageType type, String viperPath, ArtifactoryClient.FileInfo viper,
            String basePath, ArtifactoryClient.FileInfo base) {
        ObjectNode record = JSON.createObjectNode();
        record.set("foundation", input(basePath, base));
        record.put("package_type", type.name());
        record.set("viper", input(viperPath, viper));
        return record;
    }

    private static ObjectNode input(String path, ArtifactoryClient.FileInfo info) {
        ObjectNode node = JSON.createObjectNode();
        node.put("path", path);
        node.put("sha256", info.sha256());
        node.put("size", info.size());
        return node;
    }

    private static JsonNode readStamp(Path file) {
        try {
            return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The stamp read back equals the one this merge would write (both parsed the same way, so numbers compare). */
    private static boolean same(JsonNode previous, ObjectNode record) {
        if (previous == null) {
            return false;
        }
        try {
            return JSON.readTree(JSON.writeValueAsString(record)).equals(previous);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            // ignore_errors=True, as the Python's rmtree
        }
    }
}
