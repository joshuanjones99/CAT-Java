package com.verifone.cat.sims;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryErrors;
import com.verifone.cat.sims.base.JavaSets;

/**
 * Java 17 and the simulators: what is not staged, and staging it from Artifactory. The Python CAT's
 * {@code cat.sims.staging}, behind {@code cat-term fetch-sims} ({@code cat-java fetch-sims} here,
 * {@link FetchSimsCommand}). Neither asks for a login: it uses the saved one. Java comes first, because the
 * simulators' own setup steps (Petro's SSLUtil) run on it. Nothing already staged is replaced; a chosen Simulator Build
 * does that ({@link SimFetch#fetchBuild}).
 *
 * <p>Deviation: the Python also runs this in the background once the operator's Verifone login signs in
 * ({@code cat.app.checks}); CAT-Java has no such trigger yet, so only the command runs it.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port).
 */
public final class SimStaging {

    private SimStaging() {
    }

    /**
     * What is not under the tools root.
     *
     * @param toolsRoot the tools root looked at
     * @param java the Java 17 sets it does not hold
     * @param simulators the registry rows (base 57's) whose folder is absent
     */
    public record MissingTools(Path toolsRoot, List<JavaSets.JavaSet> java, List<SimEntry> simulators) {

        public MissingTools {
            java = List.copyOf(java);
            simulators = List.copyOf(simulators);
        }

        /** Each missing thing, in words: {@code "Java17 (Java 17 v1.2)"}, then each simulator's name. */
        public List<String> names() {
            List<String> names = new ArrayList<>();
            java.forEach(set -> names.add(set.target() + " (Java 17 " + set.version() + ")"));
            simulators.forEach(entry -> names.add(entry.name()));
            return names;
        }

        /** True when everything is staged. */
        public boolean isEmpty() {
            return java.isEmpty() && simulators.isEmpty();
        }
    }

    /** The Java 17 sets and simulator rows whose folders are absent under {@code toolsRoot}. Local reads only. */
    public static MissingTools missingTools(Path toolsRoot) {
        SimContext ctx = SimContext.of(toolsRoot, "");
        List<SimEntry> rows = Registry.all().stream().filter(entry -> !Files.isDirectory(ctx.cwdFor(entry))).toList();
        return new MissingTools(toolsRoot, JavaSets.missing(toolsRoot), rows);
    }

    /**
     * Downloads whatever {@link #missingTools} names, with the saved login. One problem per thing not staged; empty
     * when everything is. Blocking network.
     *
     * @param clients makes the Artifactory client, asked once and only when something is missing; may throw
     */
    public static List<String> stageMissingTools(Path toolsRoot, Supplier<ArtifactoryClient> clients,
            Consumer<String> log) {
        MissingTools missing = missingTools(toolsRoot);
        if (missing.isEmpty()) {
            return List.of();
        }
        ArtifactoryClient client;
        try {
            client = clients.get();
        } catch (RuntimeException e) {
            return List.of(ArtifactoryErrors.describe(e, ""));
        }
        List<String> problems = new ArrayList<>(JavaSets.fetchMissingJava(toolsRoot, () -> client, log));
        // Again, now that the JDK the simulators' setup steps run on may be there.
        SimContext ctx = SimContext.of(toolsRoot, "");
        for (String problem : new SimFetch(() -> client, log).fetchMissing(missing.simulators(), ctx, false)) {
            if (!problem.isEmpty()) {
                problems.add(problem);
            }
        }
        return problems;
    }
}
