package com.verifone.cat.setup;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.sims.SimStaging;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Java 17 and the simulators: what is not staged under the tools root, and staging it from Artifactory with the saved
 * Verifone login. The Python CAT's {@code cat.sims.staging} ({@code missing_tools}, {@code stage_missing_tools}),
 * which CAT runs in the background once the Verifone login signs in.
 *
 * <p>An interface so the web UI's start can be tested without Artifactory: {@link #NONE} stages nothing, and
 * {@link #standard} is the sims slice's {@link SimStaging} ({@code missingTools}, {@code stageMissingTools}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): run only against fakes from here.
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

    /** The real staging over {@code toolsRoot} with {@code clients}: {@link SimStaging}. */
    static ToolStaging standard(Path toolsRoot, Supplier<ArtifactoryClient> clients) {
        return new ToolStaging() {
            @Override
            public List<String> missing() {
                return SimStaging.missingTools(toolsRoot).names();
            }

            @Override
            public List<String> stageMissing(Consumer<String> log) {
                return SimStaging.stageMissingTools(toolsRoot, clients, log);
            }
        };
    }
}
