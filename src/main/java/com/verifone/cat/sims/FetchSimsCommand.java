package com.verifone.cat.sims;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.StoredArtifactoryCredentials;

/**
 * {@code cat-java fetch-sims}: stage the Java 17 sets and every simulator that is missing, from Artifactory
 * (simulators: the newest build that has them), with the saved login. The Python CAT's {@code cat-term fetch-sims}
 * ({@code cat.__main__._run_fetch_sims_command}), which takes no flags; neither does this, beyond {@code --help}. It
 * never asks for a login. Exit 0 when everything is staged, 1 when something could not be, 2 for a usage error.
 *
 * <p>The login is the one {@link StoredArtifactoryCredentials#forEnvironment} finds (the environment, then the
 * Python CAT's encrypted {@code .secrets/artifactory.credentials}, then Monarch's). Deviation: the lines are plain ASCII
 * ({@code OK}, {@code FAIL}) where the Python prints check marks, which a Windows console's code page garbles.
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER (Java port).
 */
public final class FetchSimsCommand {

    static final String USAGE = "usage: cat-java fetch-sims\n"
            + "  download the Java 17 sets and every simulator not yet staged, from Artifactory (simulators: the\n"
            + "  latest build that has them), with the login saved for CAT. Nothing staged is replaced.";

    /** The bound on one Artifactory request, as step 1's. */
    static final Duration ARTIFACTORY_TIMEOUT = Duration.ofMinutes(10);

    private final Path toolsRoot;
    private final Supplier<ArtifactoryClient> clients;
    private final PrintStream out;
    private final PrintStream err;

    /** The real command: {@code CAT_TOOLS_ROOT} or the default tools root, the saved login, the console. */
    public FetchSimsCommand() {
        this(ToolsRoot.resolve(null), () -> new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                StoredArtifactoryCredentials.forEnvironment(System.getenv()), ARTIFACTORY_TIMEOUT),
                System.out, System.err);
    }

    /** For tests: a temp tools root, a fake server's client, captured output. */
    FetchSimsCommand(Path toolsRoot, Supplier<ArtifactoryClient> clients, PrintStream out, PrintStream err) {
        this.toolsRoot = toolsRoot;
        this.clients = clients;
        this.out = out;
        this.err = err;
    }

    /** Runs the command with {@code argv} (the words after {@code fetch-sims}). */
    public int run(String[] argv) {
        for (String arg : argv) {
            if (arg.equals("-h") || arg.equals("--help")) {
                out.println(USAGE);
                return 0;
            }
            err.println("fetch-sims: unrecognised argument " + arg);
            err.println(USAGE);
            return 2;
        }
        SimStaging.MissingTools missing = SimStaging.missingTools(toolsRoot);
        if (missing.isEmpty()) {
            out.println("OK  Java 17 and every simulator are staged under " + toolsRoot);
            return 0;
        }
        out.println("not staged under " + toolsRoot + ": " + String.join(", ", missing.names()));
        List<String> problems = SimStaging.stageMissingTools(toolsRoot, clients, out::println);
        for (String problem : problems) {
            out.println("  FAIL  " + problem);
        }
        if (!problems.isEmpty()) {
            out.println("Save an Artifactory login for CAT (the Python CAT's `petrotest artifactory-login`, or "
                    + "ARTIFACTORY_USER/ARTIFACTORY_PASSWORD), then run `cat-java fetch-sims` again.");
            return 1;
        }
        out.println("OK  Java 17 and every simulator are staged under " + toolsRoot);
        return 0;
    }

}
