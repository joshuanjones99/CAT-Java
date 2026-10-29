package com.verifone.cat.build;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The foundation build a viper (SOS) build was built with, and why: the Python's {@code nightly.FoundationPair} and
 * {@code paired_foundation}.
 *
 * <p>The rule, read off every viper build on master and master057.01 on 2026-10-07: candidates are the branch's own
 * builds with an installable, non-plain folder for the dist (the viper's own number included); ordered by how far
 * that folder's date is from the viper folder's, never by number; only within {@link #PAIR_WINDOW}; the first whose
 * folder holds every {@code .exe} name the viper folder holds is the pair. Never another branch's, never "the newest":
 * a viper build with no pair is the operator's to pair.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port): tested against {@code FakeArtifactory}.
 *
 * @param folder  the foundation build's folder for the dist: {@code foundation-buypass}, {@code sc-buypass}
 * @param exe     the {@code .exe} file name(s) both folders hold
 * @param minutes the foundation folder's date minus the viper folder's, rounded to minutes; negative when the
 *                foundation was published first
 */
public record FoundationPair(String branch, String build, String folder, String exe, int minutes) {

    /**
     * How far apart a viper and a foundation may have been published and still pair by default. Every pair on
     * 2026-10-07 was 16 to 100 min apart; six hours paired three wrongly; two hours sits between.
     */
    public static final Duration PAIR_WINDOW = Duration.ofHours(2);

    /** What a failed pairing tells the operator to do instead (the Python's, with CAT-Java's command). */
    static final String CHOOSE = "choose the foundation explicitly "
            + "(cat-java build --foundation-branch <branch> --foundation-build <n>)";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** {@code [branch, build]}, the shape {@link CommanderBuildFetch} takes for a foundation. */
    public List<String> foundation() {
        return List.of(branch, build);
    }

    /** {@code paired by exe name and build time: foundation-buypass in master057.01 23110, built 18 min after the viper}. */
    public String describe() {
        return "paired by exe name and build time: " + folder + " in " + branch + " " + build + ", built "
                + offset(minutes);
    }

    private static String offset(int minutes) {
        if (minutes == 0) {
            return "within a minute of the viper";
        }
        return Math.abs(minutes) + " min " + (minutes < 0 ? "before" : "after") + " the viper";
    }

    /** One candidate in the window. */
    private record Candidate(Duration gap, BigInteger number, String build, String folder, Duration offset) {}

    /**
     * The foundation build {@code branch}'s viper build {@code build} was built with, for {@code dist}. Requests: one
     * depth-2 listing of the branch, then, if any candidate is in the window, the viper folder once and each
     * candidate's folder until one matches.
     *
     * @throws BuildException no such viper build, or no foundation pairs with it (the message names what was looked
     *     at and says to choose one)
     */
    public static FoundationPair paired(ArtifactoryClient client, String branch, String build, String dist) {
        String name = ArtifactoryClient.trimSlashes(branch.strip());
        String number = BuildDiscovery.buildNumber(build);
        Map<String, BuildCatalogue.TreeBuild> tree = new BuildCatalogue(client).tree(name);
        if (!tree.containsKey(number)) {
            throw new BuildException(name + " has no build " + number + " on Artifactory");
        }
        Map<String, String> children = tree.get(number).children();
        DistFolder viper = BuildContents.classify(name, number, BuildCatalogue.childNames(tree.get(number)))
                .sourceFor(dist, BuildContents.VIPER);
        if (viper == null) {
            throw new BuildException(name + " " + number + " has no viper-" + dist + " folder to pair");
        }
        String where = name + " " + number + " " + viper.folder();
        OffsetDateTime built = utc(children.get(viper.folder()));
        if (built == null) {
            throw new BuildException(where + " has no date on Artifactory to pair it by; " + CHOOSE);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, BuildCatalogue.TreeBuild> row : tree.entrySet()) {
            Map<String, String> kids = row.getValue().children();
            for (DistFolder folder : BuildContents.classify(name, row.getKey(),
                    BuildCatalogue.childNames(row.getValue())).foldersFor(dist)) {
                OffsetDateTime when = utc(kids.get(folder.folder()));
                if (!folder.installable() || BuildContents.PLAIN.equals(folder.flavour()) || when == null) {
                    continue;
                }
                Duration offset = Duration.between(built, when);
                if (offset.abs().compareTo(PAIR_WINDOW) <= 0) {
                    candidates.add(new Candidate(offset.abs(), new BigInteger(row.getKey()), row.getKey(),
                            folder.folder(), offset));
                }
            }
        }
        String hours = (PAIR_WINDOW.toMinutes() % 60 == 0 ? String.valueOf(PAIR_WINDOW.toHours())
                : String.valueOf(PAIR_WINDOW.toMinutes() / 60.0)) + " h";
        if (candidates.isEmpty()) {
            throw new BuildException(where + " has no foundation to pair with: no installable " + dist + " folder on "
                    + name + " was published within " + hours + " of it (" + built.format(WHEN) + " UTC); " + CHOOSE);
        }

        TreeSet<String> theirs = exes(client, BuildCatalogue.branchPath(name) + "/" + number + "/" + viper.folder());
        if (theirs.isEmpty()) {
            throw new BuildException(where + " holds no .exe to pair a foundation by; " + CHOOSE);
        }
        candidates.sort(Comparator.comparing(Candidate::gap).thenComparing(Candidate::number)
                .thenComparing(Candidate::build).thenComparing(Candidate::folder)
                .thenComparing(Candidate::offset));
        List<String> looked = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int minutes = (int) Math.rint(candidate.offset().toMillis() / 60000.0);
            TreeSet<String> held = exes(client, BuildCatalogue.branchPath(name) + "/" + candidate.build() + "/"
                    + candidate.folder());
            if (held.containsAll(theirs)) {
                return new FoundationPair(name, candidate.build(), candidate.folder(), String.join(", ", theirs),
                        minutes);
            }
            looked.add(candidate.build() + " " + candidate.folder() + " (" + String.format("%+d", minutes) + " min: "
                    + (held.isEmpty() ? "no .exe" : String.join(", ", held)) + ")");
        }
        throw new BuildException(where + " has no foundation to pair with: none of the " + looked.size() + " " + dist
                + " folders published within " + hours + " of it holds its " + String.join(", ", theirs) + " ("
                + String.join("; ", looked) + "); " + CHOOSE);
    }

    /** The {@code .exe} file names directly in {@code folder}, sorted. One request. */
    private static TreeSet<String> exes(ArtifactoryClient client, String folder) {
        TreeSet<String> names = new TreeSet<>();
        for (ArtifactoryEntry entry : client.listFolder(folder)) {
            if (!entry.folder() && entry.name().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                names.add(entry.name());
            }
        }
        return names;
    }

    /** An Artifactory date ({@code 2026-10-07T14:27:09.889Z}) as an aware time, a naive one as UTC; null if neither. */
    static OffsetDateTime utc(String stamp) {
        if (stamp == null || stamp.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(stamp);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(stamp).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException again) {
                return null;
            }
        }
    }
}
