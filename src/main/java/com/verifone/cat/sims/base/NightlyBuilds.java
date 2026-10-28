package com.verifone.cat.sims.base;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryEntry;
import com.verifone.cat.build.ArtifactoryException;
import com.verifone.cat.build.BuildCatalogue;

/**
 * The nightly repo's numbered builds, as the simulator fetch reads them: the Python CAT's
 * {@code petrotest.build.nightly.branch_path} and {@code build_contents} (the folder names only), and
 * {@code cat.sims.fetch.latest_build_with}.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port): tested against {@code FakeArtifactory}.
 */
public final class NightlyBuilds {

    /** The Solutions branch, whose builds carry no simulators. */
    public static final String SOLUTIONS = BuildCatalogue.SOLUTIONS;

    private NightlyBuilds() {
    }

    /** No such branch, build or folder: the Python's {@code NightlyBuildError} or {@code LookupError}. */
    public static final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** {@code RMS_Nightly_CLW/Petro/<branch>}; refuses a name that would leave that folder. */
    public static String branchPath(String branch) {
        String name = stripSlashes(branch == null ? "" : branch.strip());
        if (name.isEmpty() || name.contains("\\") || List.of(name.split("/")).contains("..")) {
            throw new IllegalArgumentException("not a branch name: '" + branch + "'");
        }
        return BuildCatalogue.NIGHTLY_REPO + "/" + name;
    }

    /** The branch's name as {@link #branchPath} uses it: stripped of spaces and slashes. */
    public static String branchName(String branch) {
        return stripSlashes(branch == null ? "" : branch.strip());
    }

    /** {@code build} as a build number, or an {@link IllegalArgumentException}. */
    public static String buildNumber(String build) {
        String number = build == null ? "" : build.strip();
        if (number.isEmpty() || !number.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("not a build number: '" + build + "'");
        }
        return number;
    }

    /**
     * The child folders of {@code branch}'s build {@code build}. One request.
     *
     * @throws NotFound the build does not exist (HTTP 404)
     * @throws ArtifactoryException any other Artifactory failure
     */
    public static List<String> buildFolders(ArtifactoryClient client, String branch, String build) {
        String path = branchPath(branch) + "/" + buildNumber(build);
        List<ArtifactoryEntry> children;
        try {
            children = client.listFolder(path);
        } catch (ArtifactoryException e) {
            if (e.status() == 404) {
                throw new NotFound(branch + " has no build " + build + " on Artifactory");
            }
            throw e;
        }
        List<String> names = new ArrayList<>();
        for (ArtifactoryEntry child : children) {
            if (child.folder()) {
                names.add(child.name());
            }
        }
        return names;
    }

    /**
     * The newest build under {@code repo} that has {@code folder}, by the build's date, not its number: master's
     * builds are several series interleaved (24216 is from 2026-09-21, 60 from 2026-10-07).
     *
     * @throws NotFound when no build has it
     * @throws ArtifactoryException for a failure other than a missing folder
     */
    public static String latestBuildWith(ArtifactoryClient client, String repo, String folder) {
        List<ArtifactoryEntry> builds = new ArrayList<>();
        for (ArtifactoryEntry entry : client.listTree(repo, 1)) {
            if (entry.folder() && !entry.name().isEmpty() && entry.name().chars().allMatch(Character::isDigit)) {
                builds.add(entry);
            }
        }
        builds.sort(Comparator.comparing((ArtifactoryEntry e) -> e.lastModified() == null ? "" : e.lastModified())
                .thenComparing(e -> new java.math.BigInteger(e.name())).reversed());
        for (ArtifactoryEntry build : builds) {
            try {
                client.listFolder(repo + "/" + build.name() + "/" + folder);
            } catch (ArtifactoryException e) {
                if (e.status() == 404) {
                    continue;
                }
                throw e;
            }
            return build.name();
        }
        throw new NotFound("no build under " + repo + " has " + folder + "/");
    }

    private static String stripSlashes(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '/') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(start, end);
    }
}
