package com.verifone.cat.build;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The branch and build catalogue on Artifactory: which branches exist under
 * {@code RMS_Nightly_CLW/Petro}, and which numbered builds each one has, newest first.
 *
 * <p>Read through an {@link ArtifactoryClient}: {@link #listBranches} is one request,
 * {@link #listBuildEntries} one request. Ordering is by the server's own dates, never by number
 * alone (see {@link #listBuildEntries}).
 *
 * <p>The Python CAT's listing half of {@code petrotest.build.nightly}: {@code list_branches},
 * {@code list_build_entries}, {@code _tree}, {@code builds_with}, {@code newest_build_for} and
 * {@code default_foundation}. The dist-folder classification is {@link BuildContents}, the viper pairing
 * {@link FoundationPair}, and the download {@link CommanderBuildFetch}.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER: tested against {@code FakeArtifactory}, laid
 * out as the real server was listed on 2026-10-07.
 */
public final class BuildCatalogue {

    /** The Artifactory repo path the branches live under. */
    public static final String NIGHTLY_REPO = "RMS_Nightly_CLW/Petro";

    /** The Solutions branch's name. */
    public static final String SOLUTIONS = BuildDiscovery.SOLUTIONS;

    /** The master branch's name. */
    public static final String MASTER = "master";

    /**
     * Top-level folders that are not branches, so never offered (listed 2026-10-07). Each can still
     * be named explicitly. Commit-hash folders are left out too; anything else is offered, last.
     */
    public static final Set<String> NOT_BRANCHES = Set.of(
            "null", "refs", "PCS", "PeripheralApps", "SalesAssistanceApps", "pcs-image-scans");

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern RELEASE = Pattern.compile("master(\\d+(?:\\.\\d+)*)");
    private static final Pattern TICKET = Pattern.compile("([A-Z][A-Z0-9]*)-(\\d+)");
    private static final Pattern NUMBER_ONLY = Pattern.compile("\\d+");

    /** One numbered build of a branch, with its folder's creation time (ISO-8601, UTC, or {@code ""}). */
    public record NightlyBuild(String build, String created) {}

    private final ArtifactoryClient client;

    public BuildCatalogue(ArtifactoryClient client) {
        this.client = client;
    }

    /**
     * Every branch: Solutions first, then master, the release lines newest first, PETROCORE tickets
     * newest first, other tickets, then anything else by name. One request.
     */
    public List<String> listBranches() {
        List<String> names = new ArrayList<>();
        for (ArtifactoryEntry entry : client.listFolder(NIGHTLY_REPO)) {
            if (entry.folder() && isBranch(entry.name())) {
                names.add(entry.name());
            }
        }
        names.sort(BuildCatalogue::compareBranches);
        return names;
    }

    /**
     * The branch's numbered builds, newest first by their folder's date, equal dates by the higher
     * number. One request. Never by number alone: on Solutions build 94 is newer than 1097, and 8 is
     * newer than 90.
     *
     * @throws BuildException for a name that would leave the repo, or a folder that is not there
     */
    public List<NightlyBuild> listBuildEntries(String branch) {
        List<NightlyBuild> builds = new ArrayList<>();
        for (ArtifactoryEntry entry : client.listTree(branchPath(branch), 1)) {
            if (entry.folder() && NUMBER_ONLY.matcher(entry.name()).matches()) {
                builds.add(new NightlyBuild(entry.name(), entry.lastModified()));
            }
        }
        builds.sort((a, b) -> {
            int byDate = b.created().compareTo(a.created());
            if (byDate != 0) {
                return byDate;
            }
            return new BigInteger(b.build()).compareTo(new BigInteger(a.build()));
        });
        return builds;
    }

    /** The branch's build numbers, newest first (see {@link #listBuildEntries}). One request. */
    public List<String> listBuilds(String branch) {
        List<String> numbers = new ArrayList<>();
        for (NightlyBuild build : listBuildEntries(branch)) {
            numbers.add(build.build());
        }
        return numbers;
    }

    /** What {@code branch}'s build {@code build} holds ({@link BuildContents#read}). One request. */
    public BuildContents contents(String branch, String build) {
        return BuildContents.read(client, branch, build);
    }

    /** One build of a branch, with its child folders' dates: one row of {@link #tree}. */
    public record TreeBuild(String date, Map<String, String> children) {}

    /** A build that carries a dist, with its folders for it: one row of {@link #buildsWith}. */
    public record BuildWith(NightlyBuild build, List<DistFolder> folders) {}

    /**
     * Build to (its date, each child folder's date), from one depth-2 listing (the Python's {@code _tree}). Each
     * child's own date matters where two pipelines share a number: master 19's folder dates from its viper, its
     * foundation-buypass from a day later.
     */
    public Map<String, TreeBuild> tree(String branch) {
        Map<String, String> dates = new LinkedHashMap<>();
        Map<String, Map<String, String>> children = new LinkedHashMap<>();
        for (ArtifactoryEntry entry : client.listTree(branchPath(branch), 2)) {
            String[] parts = entry.name().split("/");
            if (!entry.folder() || !NUMBER_ONLY.matcher(parts[0]).matches()) {
                continue;
            }
            if (parts.length == 1) {
                dates.put(parts[0], entry.lastModified());
            } else if (parts.length == 2) {
                children.computeIfAbsent(parts[0], k -> new LinkedHashMap<>()).put(parts[1], entry.lastModified());
            }
        }
        Map<String, TreeBuild> tree = new LinkedHashMap<>();
        for (Map.Entry<String, String> date : dates.entrySet()) {
            tree.put(date.getKey(), new TreeBuild(date.getValue(), java.util.Collections.unmodifiableMap(
                    children.getOrDefault(date.getKey(), new LinkedHashMap<>()))));
        }
        return tree;
    }

    /** {@link TreeBuild#children}'s names, in the listing's order. */
    static List<String> childNames(TreeBuild build) {
        return new ArrayList<>(build.children().keySet());
    }

    /**
     * The branch's builds that carry {@code dist}, newest first, each with its folders for it. One request, two
     * levels deep: about 650 KB and 2 s for master on 2026-10-07, so not for every keystroke of a picker.
     */
    public List<BuildWith> buildsWith(String branch, String dist) {
        Map<String, TreeBuild> tree = tree(branch);
        String name = ArtifactoryClient.trimSlashes(branch.strip());
        List<String> numbers = new ArrayList<>(tree.keySet());
        numbers.sort((a, b) -> {
            int byDate = tree.get(b).date().compareTo(tree.get(a).date());
            return byDate != 0 ? byDate : new BigInteger(b).compareTo(new BigInteger(a));
        });
        List<BuildWith> found = new ArrayList<>();
        for (String build : numbers) {
            List<DistFolder> folders = BuildContents.classify(name, build, childNames(tree.get(build)))
                    .foldersFor(dist);
            if (!folders.isEmpty()) {
                found.add(new BuildWith(new NightlyBuild(build, tree.get(build).date()), folders));
            }
        }
        return found;
    }

    /**
     * The newest build of {@code branch} with a folder for {@code dist}, of any flavour. One request. What
     * {@code cat-java build --branch <b>} downloads when no {@code --build} is named.
     *
     * @throws BuildException when no build carries it
     */
    public String newestBuildFor(String branch, String dist) {
        List<BuildWith> found = buildsWith(branch, dist);
        if (found.isEmpty()) {
            throw new BuildException("no build on " + branch + " carries " + dist);
        }
        return found.get(0).build().build();
    }

    /**
     * The newest foundation build for {@code dist} on {@code branch}, else on master: a hint only, never what a
     * viper build is merged onto (that is {@link FoundationPair#paired}). The newest installable, non-plain folder,
     * by that folder's own date. One request per branch looked at.
     *
     * @return {@code [branch, build]}
     * @throws BuildException when neither branch has one
     */
    public List<String> defaultFoundation(String branch, String dist) {
        for (String candidate : new java.util.LinkedHashSet<>(List.of(ArtifactoryClient.trimSlashes(branch.strip()),
                MASTER))) {
            String bestDate = null;
            BigInteger bestNumber = null;
            String best = null;
            for (Map.Entry<String, TreeBuild> row : tree(candidate).entrySet()) {
                for (DistFolder folder : BuildContents.classify(candidate, row.getKey(), childNames(row.getValue()))
                        .foldersFor(dist)) {
                    if (!folder.installable() || BuildContents.PLAIN.equals(folder.flavour())) {
                        continue;
                    }
                    String date = row.getValue().children().get(folder.folder());
                    BigInteger number = new BigInteger(row.getKey());
                    int c = best == null ? 1 : date.compareTo(bestDate);
                    if (c == 0) {
                        c = number.compareTo(bestNumber);
                    }
                    if (c > 0) {
                        bestDate = date;
                        bestNumber = number;
                        best = row.getKey();
                    }
                }
            }
            if (best != null) {
                return List.of(candidate, best);
            }
        }
        throw new BuildException("no foundation-" + dist + " build on " + branch + " or " + MASTER
                + " to merge a viper build onto");
    }

    /**
     * {@code RMS_Nightly_CLW/Petro/<branch>}, refusing a name that would leave that folder.
     *
     * @throws BuildException for an empty name, a backslash, or a {@code ..} segment
     */
    public static String branchPath(String branch) {
        String name = branch == null ? "" : branch.strip().replaceAll("^/+", "").replaceAll("/+$", "");
        if (name.isEmpty() || name.contains("\\") || List.of(name.split("/")).contains("..")) {
            throw new BuildException("not a branch name: " + branch);
        }
        return NIGHTLY_REPO + "/" + name;
    }

    /** Whether a top-level folder is a branch (not one of {@link #NOT_BRANCHES}, not a commit hash). */
    public static boolean isBranch(String name) {
        return !NOT_BRANCHES.contains(name) && !COMMIT.matcher(name).matches();
    }

    /**
     * The branch order: Solutions, master, release lines newest first, PETROCORE tickets by
     * number newest first, other tickets, then everything else by lowercase name.
     */
    static int compareBranches(String a, String b) {
        int groupA = group(a);
        int groupB = group(b);
        if (groupA != groupB) {
            return Integer.compare(groupA, groupB);
        }
        if (groupA == 2) {
            return compareReleases(a, b);
        }
        if (groupA == 3 || groupA == 4) {
            Matcher ta = TICKET.matcher(a);
            Matcher tb = TICKET.matcher(b);
            ta.lookingAt();
            tb.lookingAt();
            int byProject = ta.group(1).compareTo(tb.group(1));
            if (byProject != 0) {
                return byProject;
            }
            // Newest ticket first: the larger number sorts earlier.
            int byNumber = new BigInteger(tb.group(2)).compareTo(new BigInteger(ta.group(2)));
            if (byNumber != 0) {
                return byNumber;
            }
        }
        return a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
    }

    private static int group(String name) {
        if (SOLUTIONS.equals(name)) {
            return 0;
        }
        if (MASTER.equals(name)) {
            return 1;
        }
        Matcher release = RELEASE.matcher(name);
        if (release.lookingAt()) {
            return 2;
        }
        Matcher ticket = TICKET.matcher(name);
        if (ticket.lookingAt()) {
            return "PETROCORE".equals(ticket.group(1)) ? 3 : 4;
        }
        return 5;
    }

    private static int compareReleases(String a, String b) {
        long[] partsA = releaseParts(a);
        long[] partsB = releaseParts(b);
        for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
            // Higher release first, as the Python's tuple of negated parts sorts.
            int c = Long.compare(-partsA[i], -partsB[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(partsA.length, partsB.length);
    }

    private static long[] releaseParts(String name) {
        Matcher release = RELEASE.matcher(name);
        release.lookingAt();
        String[] parts = release.group(1).split("\\.");
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Long.parseLong(parts[i]);
        }
        return values;
    }
}
