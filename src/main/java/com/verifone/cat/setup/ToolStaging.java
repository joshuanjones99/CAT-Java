package com.verifone.cat.setup;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryErrors;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimFetch;
import com.verifone.cat.sims.SimSources;
import com.verifone.cat.sims.ToolsRoot;
import com.verifone.cat.sims.base.JavaSets;
import com.verifone.cat.sims.base.NightlyBuilds;

/**
 * Java 17 and the simulators: what is not staged under the tools root, and staging it from Artifactory with the saved
 * Verifone login. The Python CAT's {@code cat.sims.staging} ({@code missing_tools}, {@code stage_missing_tools}),
 * which CAT runs in the background once the Verifone login signs in. Nothing already staged is replaced (the Config
 * tab's Simulator Build does that); Java comes first, because the simulators' own setup steps run on it.
 *
 * <p>An interface so the web UI's start can be tested without Artifactory: {@link #NONE} stages nothing, and
 * {@link #standard} is the real one, composed from the sims slice's public parts ({@link JavaSets#missing},
 * {@link JavaSets#ensureJava}, {@link NightlyBuilds#latestBuildWith}, {@link SimFetch#fetchBuild}). If the sims slice
 * ports {@code cat.sims.staging} itself, {@link #standard} should call it instead (wave-16 hook).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): run only against fakes.
 */
public interface ToolStaging {

    /** What is missing, in words ({@code Java17 (Java 17 v1.2)}, a simulator row's name); empty when all is staged. */
    List<String> missing();

    /** Downloads whatever {@link #missing} names. One problem per thing not staged; empty when everything is. */
    List<String> stageMissing(Consumer<String> log);

    /** Stages nothing and finds nothing missing (tests, and {@code --no-setup}). */
    ToolStaging NONE = new ToolStaging() {
        @Override
        public List<String> missing() {
            return List.of();
        }

        @Override
        public List<String> stageMissing(Consumer<String> log) {
            return List.of();
        }
    };

    /** How long an Artifactory request may take while staging. */
    Duration TIMEOUT = Duration.ofMinutes(10);

    /** The real staging over {@code CAT_TOOLS_ROOT} (else {@code C:\CICD\Tools}), with the saved Verifone login. */
    static ToolStaging standard(Map<String, String> env, SavedLogins logins) {
        Path root = ToolsRoot.resolve(env.get(ToolsRoot.ENV), null);
        return standard(root, () -> new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                logins.artifactoryCredentials(), TIMEOUT));
    }

    /** The real staging over {@code toolsRoot} with {@code clients}. */
    static ToolStaging standard(Path toolsRoot, Supplier<ArtifactoryClient> clients) {
        return new ToolStaging() {
            private SimContext ctx() {
                return SimContext.of(toolsRoot, "");
            }

            private List<SimEntry> missingRows() {
                SimContext ctx = ctx();
                List<SimEntry> out = new ArrayList<>();
                for (SimEntry entry : Registry.all()) {
                    if (!Files.isDirectory(ctx.cwdFor(entry))) {
                        out.add(entry);
                    }
                }
                return out;
            }

            @Override
            public List<String> missing() {
                List<String> names = new ArrayList<>();
                for (JavaSets.JavaSet java : JavaSets.missing(toolsRoot)) {
                    names.add(java.target() + " (Java 17 " + java.version() + ")");
                }
                missingRows().forEach(entry -> names.add(entry.name()));
                return names;
            }

            @Override
            public List<String> stageMissing(Consumer<String> log) {
                List<String> problems = new ArrayList<>();
                ArtifactoryClient[] client = new ArtifactoryClient[1];
                Supplier<ArtifactoryClient> once = () -> {
                    if (client[0] == null) {
                        client[0] = clients.get();
                    }
                    return client[0];
                };
                for (JavaSets.JavaSet java : JavaSets.missing(toolsRoot)) {
                    String why = JavaSets.ensureJava(java, toolsRoot, once, log);
                    if (!why.isEmpty()) {
                        problems.add(why);
                    }
                }
                List<SimEntry> rows = missingRows();
                if (rows.isEmpty()) {
                    return problems;
                }
                String build;
                try {
                    build = NightlyBuilds.latestBuildWith(once.get(), SimSources.MASTER_REPO, "simulators");
                } catch (RuntimeException e) {
                    problems.add("simulators: " + JavaSets.artifactoryFailure(e));
                    return problems;
                }
                try {
                    for (SimFetch.FetchResult result : new SimFetch(once, log)
                            .fetchBuild(rows, ctx(), "master", build, false, (entries, c) -> Map.of())) {
                        if (!result.ok()) {
                            problems.add(result.detail());
                        }
                    }
                } catch (RuntimeException e) {
                    problems.add("simulators: " + ArtifactoryErrors.describe(e, SimSources.MASTER_REPO));
                }
                return problems;
            }
        };
    }
}
