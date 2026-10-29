package com.verifone.cat.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one nightly build holds: its dist folders by flavour, and whether it carries the simulators. The Python's
 * {@code nightly.BuildContents} and {@code build_contents}.
 *
 * <p>The layout, as the real server was listed on 2026-10-07 (the Python module's docstring): {@code Solutions/<n>/}
 * holds unprefixed {@code <dist>/} folders ("plain": the merged overlay and both jars); other branches hold
 * {@code foundation-<dist>/}, {@code sc-<dist>/}, {@code retail-<dist>/} (installable) or {@code viper-<dist>/} (an
 * overlay and an {@code .exe}, no jars: it installs only merged onto a foundation's overlay). Every {@code *-base}
 * folder holds update packages, not a dist; {@code simulators/} and {@code automation/} are flags; anything else is
 * {@link #other}.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port): tested against {@code FakeArtifactory}.
 *
 * @param simulators {@code simulators/} (the LoySim, MPPA, BUYPASS and Petro zips) is in this build
 * @param automation {@code automation/} (the Terminal Connector's zip) is in this build
 * @param other      every other folder: {@code TopazERP}, {@code xrayIntegrator}, {@code foundation-base}...
 */
public record BuildContents(String branch, String build, List<DistFolder> folders, boolean simulators,
        boolean automation, List<String> other) {

    /** Solutions' unprefixed {@code <dist>/}. */
    public static final String PLAIN = "plain";
    public static final String FOUNDATION = "foundation";
    public static final String SC = "sc";
    public static final String RETAIL = "retail";
    public static final String VIPER = "viper";

    /** The flavours a dist folder comes in, by its name's prefix. */
    public static final List<String> FLAVOURS = List.of(PLAIN, FOUNDATION, SC, RETAIL, VIPER);

    private static final Pattern PREFIXED = Pattern.compile("^(foundation|sc|retail|viper)-(.+)$");

    public BuildContents {
        folders = List.copyOf(folders);
        other = List.copyOf(other);
    }

    /**
     * Classifies a build's child folder names (the Python's {@code _contents}).
     *
     * @param branch the branch, trimmed of slashes
     */
    public static BuildContents classify(String branch, String build, List<String> names) {
        List<DistFolder> folders = new ArrayList<>();
        List<String> other = new ArrayList<>();
        boolean simulators = false;
        boolean automation = false;
        for (String name : names) {
            Matcher prefixed = PREFIXED.matcher(name);
            boolean isPrefixed = prefixed.matches();
            if ("simulators".equals(name)) {
                simulators = true;
            } else if ("automation".equals(name)) {
                automation = true;
            } else if (isPrefixed && !"base".equals(prefixed.group(2))) {
                folders.add(new DistFolder(prefixed.group(2), prefixed.group(1), name));
            } else if (BuildDiscovery.SOLUTIONS.equals(branch) && !isPrefixed) {
                folders.add(new DistFolder(name, PLAIN, name));
            } else {
                other.add(name);
            }
        }
        return new BuildContents(branch, build, folders, simulators, automation, other);
    }

    /**
     * What {@code branch}'s build {@code build} holds. One request.
     *
     * @throws BuildException the build is not there, or a name is not a branch or a number
     * @throws ArtifactoryException any other Artifactory failure
     */
    public static BuildContents read(ArtifactoryClient client, String branch, String build) {
        String number = BuildDiscovery.buildNumber(build);
        String path = BuildCatalogue.branchPath(branch) + "/" + number;
        List<ArtifactoryEntry> children;
        try {
            children = client.listFolder(path);
        } catch (ArtifactoryException e) {
            if (e.status() == 404) {
                throw new BuildException(branch + " has no build " + build + " on Artifactory", e);
            }
            throw e;
        }
        List<String> names = new ArrayList<>();
        for (ArtifactoryEntry child : children) {
            if (child.folder()) {
                names.add(child.name());
            }
        }
        return classify(ArtifactoryClient.trimSlashes(branch.strip()), number, names);
    }

    /** Each dist once, in folder order, as its first folder spells it. */
    public List<String> dists() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (DistFolder folder : folders) {
            seen.putIfAbsent(folder.dist().toLowerCase(Locale.ROOT), folder.dist());
        }
        return new ArrayList<>(seen.values());
    }

    /** The build has a child folder of exactly this name ({@code simulators}, {@code TopazERP}...). */
    public boolean has(String folder) {
        if ("simulators".equals(folder)) {
            return simulators;
        }
        if ("automation".equals(folder)) {
            return automation;
        }
        return other.contains(folder) || folders.stream().anyMatch(d -> d.folder().equals(folder));
    }

    /** {@code dist}'s folders, matched case-insensitively ({@code testfep} finds {@code testFep}). */
    public List<DistFolder> foldersFor(String dist) {
        return folders.stream().filter(f -> f.dist().equalsIgnoreCase(dist)).toList();
    }

    /**
     * The folder to install {@code dist} from: {@code flavour}'s if named, else an installable one, else a viper one;
     * null when the build has none.
     */
    public DistFolder sourceFor(String dist, String flavour) {
        List<DistFolder> mine = foldersFor(dist);
        if (flavour != null) {
            return mine.stream().filter(f -> f.flavour().equals(flavour)).findFirst().orElse(null);
        }
        return mine.stream().filter(DistFolder::installable).findFirst()
                .orElse(mine.isEmpty() ? null : mine.get(0));
    }

    /** {@code dist} is here only as a viper overlay, so installing it means a merge. */
    public boolean viperOnly(String dist) {
        DistFolder source = sourceFor(dist, null);
        return source != null && !source.installable();
    }

    /** One line for a picker: what installing {@code dist} from this build means. The Python's wording. */
    public String describe(String dist) {
        String where = branch + " " + build;
        DistFolder source = sourceFor(dist, null);
        if (source == null) {
            if (dists().isEmpty()) {
                String held = other.isEmpty() ? "no folders in it" : "it holds " + String.join(", ", other);
                return where + " carries no Commander build (" + held + ")";
            }
            return where + " has no " + dist + "; it carries " + String.join(", ", dists());
        }
        if (!source.installable()) {
            return where + " " + source.folder() + ": viper-only (SOS); its overlay will be merged "
                    + "onto a foundation build's, and installed with that build's jars";
        }
        String line = where + " " + source.folder() + ": overlay and installer jars";
        if (foldersFor(dist).stream().anyMatch(f -> VIPER.equals(f.flavour()))) {
            line += "; its viper-" + source.dist() + " was published separately and is not used";
        }
        return line;
    }
}
