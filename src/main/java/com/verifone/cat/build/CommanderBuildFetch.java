package com.verifone.cat.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Downloads one nightly Commander build's files for an install into the builds root: the part of the Python's
 * {@code petrotest build --branch B --build N --dist D --mode M} ({@code petrotest.build.nightly}) that a build with
 * its own dist folder needs.
 *
 * <p>The files are {@code overlay.zip} plus the mode's installer jars ({@link #PSI_FILES} for a PSI install), from
 * {@code RMS_Nightly_CLW/Petro/<branch>/<n>/<dist>/} into {@link BuildDiscovery#buildDir}. The dist folder is matched
 * case-insensitively, as the Python does.
 *
 * <p>Not ported: a viper-only build (one holding only {@code *-<dist>} flavour folders) is merged onto a foundation
 * build in the Python ({@code build_contents}, {@code overlay_merge}); here it is a {@link BuildException} that says
 * so. The same download is the web Downloads page's ({@code web.DownloadsService.fetchCommander}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested against the loopback Artifactory fake only.
 */
public final class CommanderBuildFetch {

    /** The PSI install's jars, beside {@code overlay.zip}: the Python's {@code INSTALLER_JARS["new_install"]}. */
    public static final List<String> PSI_FILES = List.of("Petro_Suite_Installer.jar", "Auto_Upgrade.jar");

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
     * Fetches {@code overlay.zip} and {@code jars} of {@code branch} build {@code build}'s {@code dist} folder.
     *
     * @return the local build directory, {@code <root>\<label>\<dist>}
     * @throws BuildException the build or its dist folder is not on Artifactory, or a download did not verify
     */
    public static Path fetch(ArtifactoryClient client, String branch, String build, String dist, List<String> jars,
            Path root, Consumer<String> log) {
        String number = BuildDiscovery.buildNumber(build);
        String buildPath = BuildCatalogue.branchPath(branch) + "/" + number;
        List<String> folders = folderNames(client, buildPath);
        String folder = folders.stream().filter(name -> name.equalsIgnoreCase(dist)).findFirst()
                .orElseThrow(() -> new BuildException(missingDist(branch, number, dist, folders)));
        List<String> names = new ArrayList<>();
        names.add(BuildDiscovery.OVERLAY);
        names.addAll(jars);
        Path dest = BuildDiscovery.buildDir(branch, number, dist, root);
        log.accept(branch + " " + number + ", " + folder + " -> " + dest);
        BuildDownloads.fetchFiles(client, buildPath + "/" + folder, names, dest, log);
        return dest;
    }

    /** Why a build has no folder for the dist, naming a flavour folder as not ported when that is what it holds. */
    static String missingDist(String branch, String number, String dist, List<String> folders) {
        String suffix = "-" + dist.toLowerCase(Locale.ROOT);
        List<String> flavours = folders.stream().filter(name -> name.toLowerCase(Locale.ROOT).endsWith(suffix)).toList();
        if (!flavours.isEmpty()) {
            return branch + " " + number + " holds only " + String.join(", ", flavours)
                    + ": merging a viper, foundation, sc or retail build is unavailable in CAT-Java"
                    + " (petrotest/build/nightly.py build_contents, petrotest/build/overlay_merge.py)";
        }
        return branch + " " + number + " holds no " + dist + " folder (" + String.join(", ", folders) + ")";
    }

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
}
