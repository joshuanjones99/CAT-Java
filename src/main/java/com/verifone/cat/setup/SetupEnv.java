package com.verifone.cat.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.verifone.cat.gitauth.GitAuth;

/**
 * What a setup item may look at and, in its repair, change: environment variables, the user's home, the repository
 * root, the file system, a process runner, git's credential store and the saved logins. Every item reads and writes
 * through this, so a test injects a temporary directory, a {@link Map} of variables, a fake command, a fake git and a
 * fake DPAPI, and never touches the real machine.
 *
 * <p>Ported from the Python CAT's {@code cat.setup.env.Env}, with its write methods ({@link #mkdir},
 * {@link #writeFile}, {@link #touch}, {@link #setEnvVar}), which only a repair or a sign-in card's save calls.
 *
 * <p>Deviations from the Python:
 * <ul>
 *   <li>{@link #setEnvVar} cannot change this JVM's own environment ({@code System.getenv} is read-only), so it sets
 *       the variable in this environment's view (every later check through it sees it) and persists it for the next
 *       start with {@code setx}, as the Python's does. Parts of CAT-Java that read {@code System.getenv} directly see
 *       it after CAT-Java is started again.</li>
 *   <li>CAT-Java writes its own saved logins under {@code ~/.cat/java/} ({@link #catJavaDir}), never the Python
 *       CAT's {@code .secrets/} or the shared {@code ~/jira_api_token.properties}; see {@link SavedLogins}.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). {@link #system()} is the only constructor that reads the real
 * environment.
 */
public final class SetupEnv {

    /** Runs one command and returns what it printed. Injected, so tests answer it themselves. */
    @FunctionalInterface
    public interface Commands {
        CommandResult run(List<String> argv, Duration timeout);
    }

    /** A finished command: its exit code and both streams. A timeout or a launch failure is exit 1. */
    public record CommandResult(int exitCode, String stdout, String stderr) {}

    /** How long {@code setx} may take, as the Python's. */
    static final Duration SETX_TIMEOUT = Duration.ofSeconds(10);

    private final Map<String, String> vars;
    private final Path home;
    private final Path repoRoot;
    private final Commands commands;
    private final GitAuth git;
    private final SavedLogins logins;
    private final boolean persistVars;

    /**
     * A test's environment: git answers that there is no checkout, the Artifactory login is read from
     * {@code ARTIFACTORY_USER}/{@code ARTIFACTORY_PASSWORD} in {@code vars} only and cannot be saved, and
     * {@link #setEnvVar} changes only this view (no {@code setx}).
     */
    public SetupEnv(Map<String, String> vars, Path home, Path repoRoot, Commands commands) {
        this(vars, home, repoRoot, commands, GitAuth.offline(), SavedLogins.envOnly(vars, home), false);
    }

    /**
     * @param git         git's credential store for the petroapps checkout
     * @param logins      where the saved Artifactory and Atlassian logins are read and written
     * @param persistVars {@link #setEnvVar} also runs {@code setx} through {@code commands}
     */
    public SetupEnv(Map<String, String> vars, Path home, Path repoRoot, Commands commands, GitAuth git,
            SavedLogins logins, boolean persistVars) {
        this.vars = new ConcurrentHashMap<>(Map.copyOf(Objects.requireNonNull(vars, "vars")));
        this.home = Objects.requireNonNull(home, "home");
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.git = Objects.requireNonNull(git, "git");
        this.logins = Objects.requireNonNull(logins, "logins");
        this.persistVars = persistVars;
    }

    /** The real process: {@code System.getenv}, the user's home, the working directory, real commands, git and DPAPI. */
    public static SetupEnv system() {
        Map<String, String> env = System.getenv();
        Path home = Path.of(System.getProperty("user.home"));
        return new SetupEnv(env, home, Path.of(System.getProperty("user.dir")), SetupEnv::runProcess,
                GitAuth.system(), SavedLogins.system(env, home), true);
    }

    /** The variables this environment sees now: the ones it was built from, plus any {@link #setEnvVar} set. */
    public Map<String, String> vars() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(vars));
    }

    /** A variable's value, or null when it is unset or blank (a blank counts as unset). */
    public String var(String name) {
        String value = vars.get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Whether a variable is set at all, whatever its value (the Python's {@code env_var_exists}). */
    public boolean varExists(String name) {
        return vars.containsKey(name);
    }

    /** The user's home directory, where per-user files such as {@code .accessibility.properties} live. */
    public Path home() {
        return home;
    }

    /** {@code ~/.cat/java}: the only folder CAT-Java writes its own files to. */
    public Path catJavaDir() {
        return home.resolve(".cat").resolve("java");
    }

    /** The repository root, where {@code target\} is built. */
    public Path repoRoot() {
        return repoRoot;
    }

    /** git's credential store, for the petroapps checkout. */
    public GitAuth git() {
        return git;
    }

    /** The saved Artifactory and Atlassian logins. */
    public SavedLogins logins() {
        return logins;
    }

    public boolean exists(Path path) {
        return Files.exists(path);
    }

    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    /**
     * True when {@code path} is a writable directory, or does not exist yet and its nearest existing
     * ancestor is a writable directory (so it can be created).
     */
    public boolean isWritable(Path path) {
        Path at = path.toAbsolutePath();
        while (at != null && !Files.exists(at)) {
            at = at.getParent();
        }
        return at != null && Files.isDirectory(at) && Files.isWritable(at);
    }

    /** The file's text, read as UTF-8. Throws {@link IOException} if it cannot be read. */
    public String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public CommandResult run(List<String> argv, Duration timeout) {
        return commands.run(argv, timeout);
    }

    // ------------------------------------------------------------------ writes (repairs and saves only)

    /** Creates the directory and its parents. Idempotent. */
    public void mkdir(Path path) throws IOException {
        Files.createDirectories(path);
    }

    /** Creates an empty file, and its folders, if it does not exist. Idempotent. */
    public void touch(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(path)) {
            Files.createFile(path);
        }
    }

    /** Writes the text as UTF-8, replacing the file, creating its folders. */
    public void writeFile(Path path, String text) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    /** Deletes the file if it is there. */
    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    /**
     * Sets a variable in this environment's view, and persists it for future processes with {@code setx}, best
     * effort and never throwing, as the Python's {@code set_env_var}. See the class Javadoc for why this JVM's own
     * environment is not changed.
     */
    public void setEnvVar(String name, String value) {
        vars.put(name, value);
        if (persistVars) {
            try {
                commands.run(List.of("setx", name, value), SETX_TIMEOUT);
            } catch (RuntimeException e) {
                // Best effort: the variable is set for this session's checks either way.
            }
        }
    }

    /** The real command runner. Both streams are read to the end before the wait, so a chatty command cannot stall. */
    static CommandResult runProcess(List<String> argv, Duration timeout) {
        try {
            Process process = new ProcessBuilder(argv).start();
            String out = readAll(process.getInputStream());
            String err = readAll(process.getErrorStream());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new CommandResult(1, "", "timeout");
            }
            return new CommandResult(process.exitValue(), out, err);
        } catch (IOException e) {
            return new CommandResult(1, "", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(1, "", "interrupted");
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
