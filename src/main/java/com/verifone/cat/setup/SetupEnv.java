package com.verifone.cat.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * What a setup item may look at: environment variables, the user's home, the repository root, the
 * file system and a process runner. Every item reads through this, so a test injects a temporary
 * directory, a {@link Map} of variables and a fake command, and never touches the real machine.
 *
 * <p>Read-only by design. Nothing here writes a file, sets a variable or starts an installer; the
 * Setup page tells the operator what to do instead (HANDOFF.md, wave 9).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). {@link #system()} is the only constructor that
 * reads the real environment, and only the web Setup page's POST uses it.
 */
public final class SetupEnv {

    /** Runs one command and returns what it printed. Injected, so tests answer it themselves. */
    @FunctionalInterface
    public interface Commands {
        CommandResult run(List<String> argv, Duration timeout);
    }

    /** A finished command: its exit code and both streams. A timeout or a launch failure is exit 1. */
    public record CommandResult(int exitCode, String stdout, String stderr) {}

    private final Map<String, String> vars;
    private final Path home;
    private final Path repoRoot;
    private final Commands commands;

    public SetupEnv(Map<String, String> vars, Path home, Path repoRoot, Commands commands) {
        this.vars = Map.copyOf(Objects.requireNonNull(vars, "vars"));
        this.home = Objects.requireNonNull(home, "home");
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** The real process: {@code System.getenv}, the user's home, the working directory, real commands. */
    public static SetupEnv system() {
        return new SetupEnv(System.getenv(), Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")), SetupEnv::runProcess);
    }

    /** The variables this environment was built from. */
    public Map<String, String> vars() {
        return vars;
    }

    /** A variable's value, or null when it is unset or blank (a blank counts as unset). */
    public String var(String name) {
        String value = vars.get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The user's home directory, where per-user files such as {@code .accessibility.properties} live. */
    public Path home() {
        return home;
    }

    /** The repository root, where {@code target\} is built. */
    public Path repoRoot() {
        return repoRoot;
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
