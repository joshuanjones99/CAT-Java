package com.verifone.cat.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fetches a build's files from Artifactory into a local folder, skipping any already there at
 * Artifactory's size.
 *
 * <p>A file already present at the right size is kept. A download goes through
 * {@link ArtifactoryClient#downloadFile}, which writes a {@code .part} file and moves it into
 * place only when whole, so a cut-short download is never skipped on a later run.
 *
 * <p>The Python CAT's {@code petrotest.build.nightly._fetch_file} and {@code _fetch_files}.
 */
public final class BuildDownloads {

    private BuildDownloads() {}

    /**
     * Downloads one file to {@code target} unless it is already there at Artifactory's size.
     *
     * @param repoPath the file's repo path, e.g. {@code RMS_Nightly_CLW/Petro/Solutions/1082/buypass/overlay.zip}
     * @param log      receives one line per file, as the Python's progress lines
     * @return {@code target}
     * @throws BuildException when the file is not on Artifactory, or the download does not verify
     */
    public static Path fetchFile(ArtifactoryClient client, String repoPath, Path target, Consumer<String> log) {
        String name = target.getFileName().toString();
        ArtifactoryClient.FileInfo info;
        try {
            info = client.fileInfo(repoPath);
        } catch (ArtifactoryException e) {
            if (e.status() == 404) {
                throw new BuildException(repoPath + " is not on Artifactory", e);
            }
            throw e;
        }
        if (Files.isRegularFile(target) && sizeOf(target) == info.size()) {
            log.accept("  " + name + ": already downloaded (" + info.size() + " bytes)");
            return target;
        }
        log.accept("  " + name + ": downloading " + info.size() + " bytes");
        return client.downloadFile(client.fileUrl(repoPath), target, info.size(), info.sha256(),
                ArtifactoryClient.DEFAULT_WORKERS);
    }

    /** Each named file of {@code folder} into {@code dest}, by {@link #fetchFile}. */
    public static void fetchFiles(ArtifactoryClient client, String folder, List<String> names,
            Path dest, Consumer<String> log) {
        for (String name : names) {
            fetchFile(client, folder + "/" + name, dest.resolve(name), log);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }
}
