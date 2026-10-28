package com.verifone.cat.sims.base;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryException;

/**
 * Which Commander base a staged simulator was built for, and which nightly build matches a Commander: the Python
 * CAT's {@code cat.sims.suite_base}.
 *
 * <p>How a staged simulator's base is read, best first:
 * <ul>
 *   <li>CAT's install record, {@value #MARKER} in the simulator's folder, written by every install from Artifactory
 *       (the Python's and this port's alike, same keys): the branch and build. A branch that names a base gives it
 *       exactly; plain {@code master} is the newest line, base 57 or later.</li>
 *   <li>The jar itself: its classes reference {@code jakarta/xml/bind} (base 57 and later) or {@code javax/xml/bind}
 *       (base 56 and earlier). That tells the two sides of base 57 apart, not 55.01 from 55.02.</li>
 * </ul>
 *
 * <p>The matching build is the newest one with {@code simulators/} of {@code master<BBB.MM>} for the Commander's base,
 * and for base 57 and later, failing that, {@code master}.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port).
 */
public final class SuiteBase {

    /** CAT's record of what it installed into a simulator's folder. */
    public static final String MARKER = ".cat-build.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The jar namespace per (path, mtime, size): about a second for the 10 MB suite, once per copy. */
    private static final Map<String, Optional<String>> NAMESPACES = new ConcurrentHashMap<>();

    private SuiteBase() {
    }

    /**
     * What a {@value #MARKER} says.
     *
     * @param repo   the repo folder the build came from ({@code RMS_Nightly_CLW/Petro/master})
     * @param branch its last segment
     * @param build  the build number, or {@code ?}
     */
    public record Marker(String repo, String branch, String build) {
    }

    /**
     * What is known of a staged simulator's base.
     *
     * @param base the exact base, when an install record names one, else null
     * @param v12  built for base 57 and later (true), for 56 and earlier (false), or unknown (null)
     * @param how  where that came from, for the log
     */
    public record Installed(JavaSets.Base base, Boolean v12, String how) {
    }

    /** Records that {@code folder} now holds build {@code build} of {@code repo} ({@code .../Petro/<branch>}). */
    public static void writeMarker(Path folder, String repo, String build) throws IOException {
        String trimmed = repo.endsWith("/") ? repo.substring(0, repo.length() - 1) : repo;
        Map<String, String> record = new LinkedHashMap<>();
        record.put("repo", repo);
        record.put("branch", trimmed.substring(trimmed.lastIndexOf('/') + 1));
        record.put("build", String.valueOf(build));
        record.put("installed", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        Files.writeString(folder.resolve(MARKER), JSON.writeValueAsString(record) + "\n", StandardCharsets.UTF_8);
    }

    /** The folder's install record, or empty when there is none, it is unreadable, or it names no branch. */
    public static Optional<Marker> readMarker(Path folder) {
        try {
            JsonNode node = JSON.readTree(Files.readString(folder.resolve(MARKER), StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            String branch = node.path("branch").asText("");
            if (branch.isEmpty()) {
                return Optional.empty();
            }
            JsonNode build = node.get("build");
            return Optional.of(new Marker(node.path("repo").asText(""), branch,
                    build == null || build.isNull() ? "?" : build.asText()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** {@code jakarta} or {@code javax}: the JAXB API {@code jar}'s classes reference, or empty. */
    public static Optional<String> jarNamespace(Path jar) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(jar, BasicFileAttributes.class);
        } catch (IOException e) {
            return Optional.empty();
        }
        String key = jar.toAbsolutePath() + "|" + attrs.lastModifiedTime().toMillis() + "|" + attrs.size();
        return NAMESPACES.computeIfAbsent(key, k -> scan(jar));
    }

    private static Optional<String> scan(Path jar) {
        byte[] jakarta = "jakarta/xml/bind".getBytes(StandardCharsets.US_ASCII);
        byte[] javax = "javax/xml/bind".getBytes(StandardCharsets.US_ASCII);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (contains(data, jakarta)) {
                    return Optional.of("jakarta");
                }
                if (contains(data, javax)) {
                    return Optional.of("javax");
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** The base the simulator staged in {@code folder} (its jar {@code jar}, or null) was built for. */
    public static Installed installedBase(Path folder, Path jar) {
        Optional<Marker> record = readMarker(folder);
        if (record.isPresent()) {
            String branch = record.get().branch();
            String label = "CAT installed " + branch + " build " + record.get().build();
            Optional<JavaSets.Base> base = JavaSets.baseOf(branch);
            if (base.isPresent()) {
                return new Installed(base.get(), base.get().major() >= JavaSets.V12_FROM_BASE, label);
            }
            if (branch.equals("master")) {
                return new Installed(null, true, label);
            }
        }
        Optional<String> namespace = jar == null ? Optional.empty() : jarNamespace(jar);
        if (namespace.isEmpty()) {
            return new Installed(null, null, "no install record, and its jar names no JAXB API");
        }
        boolean jakarta = namespace.get().equals("jakarta");
        String side = jakarta ? "57 and later" : "56 and earlier";
        return new Installed(null, jakarta, "its jar references " + namespace.get() + ".xml.bind (base " + side + ")");
    }

    /** Why the staged simulator is for another base than {@code commander}'s, or {@code ""}. */
    public static String mismatch(Installed installed, String commander) {
        Optional<JavaSets.Base> want = JavaSets.baseOf(commander);
        if (want.isEmpty() || installed.v12() == null) {
            return "";
        }
        if (installed.base() != null && !installed.base().equals(want.get())) {
            return "built for base " + installed.base().text() + ", the Commander is " + want.get().text();
        }
        if (installed.v12() != (want.get().major() >= JavaSets.V12_FROM_BASE)) {
            String side = installed.v12() ? "57 and later" : "56 and earlier";
            return "built for base " + side + ", the Commander is " + want.get().text();
        }
        return "";
    }

    /** The nightly branches whose simulators are built for {@code commander}'s base, best first. */
    public static List<String> branchesFor(String commander) {
        Optional<JavaSets.Base> want = JavaSets.baseOf(commander);
        List<String> branches = new ArrayList<>();
        if (want.isEmpty()) {
            return branches;
        }
        branches.add("master" + want.get().text());
        if (want.get().major() >= JavaSets.V12_FROM_BASE) {
            branches.add("master");
        }
        return branches;
    }

    /** A branch and one of its builds. */
    public record BranchBuild(String branch, String build) {
    }

    /**
     * The newest build with {@code simulators/} for {@code commander}'s base.
     *
     * @throws NightlyBuilds.NotFound when no branch of {@link #branchesFor} has one
     * @throws ArtifactoryException for any other Artifactory failure
     */
    public static BranchBuild matchingBuild(ArtifactoryClient client, String commander) {
        List<String> tried = new ArrayList<>();
        for (String branch : branchesFor(commander)) {
            try {
                return new BranchBuild(branch,
                        NightlyBuilds.latestBuildWith(client, NightlyBuilds.branchPath(branch), "simulators"));
            } catch (NightlyBuilds.NotFound e) {
                tried.add(branch + " (no build with simulators/)");
            } catch (ArtifactoryException e) {
                if (e.status() != 404) {
                    throw e;
                }
                tried.add(branch + " (no such branch)");
            }
        }
        throw new NightlyBuilds.NotFound("no simulators for base " + commander + " on Artifactory: tried "
                + (tried.isEmpty() ? "none" : String.join(", ", tried)));
    }
}
