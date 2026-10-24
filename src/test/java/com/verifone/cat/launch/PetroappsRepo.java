package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway petroapps checkout for the launch tests: a bare {@code origin.git} under the test's folder, a work tree
 * that pushes one commit to it, and a clone of that origin as {@code petroapps} on {@code master}. Nothing touches
 * {@code C:\gitrepos\petroapps} or any real remote.
 */
final class PetroappsRepo {

    /** The one Charity case the fixture holds: the seed's first case of PETROCORE-70960. */
    static final String CASE_KEY = "PETROCORE-61396";

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private PetroappsRepo() {}

    static boolean gitAvailable() {
        try {
            git(null, "--version");
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** A petroapps clone under {@code dir} whose {@code master} holds one Charity case, at the origin's head. */
    static Path withOneCase(Path dir) throws IOException {
        Path origin = dir.resolve("origin.git");
        Path work = dir.resolve("work");
        Path petroapps = dir.resolve("petroapps");
        git(dir, "init", "--bare", "-q", origin.toString());
        git(dir, "init", "-q", "-b", "master", work.toString());
        Path caseFolder = Files.createDirectories(work.resolve("AutomationTests").resolve("testcases")
                .resolve(CASE_KEY));
        Files.writeString(caseFolder.resolve(CASE_KEY + "-TestCase.xml"), "<case/>", StandardCharsets.UTF_8);
        git(work, "add", "-A");
        commit(work, "one case");
        git(work, "remote", "add", "origin", origin.toString());
        git(work, "push", "-q", "origin", "master");
        git(dir, "clone", "-q", origin.toString(), petroapps.toString());
        return petroapps;
    }

    /** Commits whatever is staged in {@code repo} with a fixed identity. */
    static void commit(Path repo, String message) {
        git(repo, "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "-m", message);
    }

    /** {@code git <args>} in {@code cwd} (or the process folder when null), stdout stripped. Throws on a non-zero exit. */
    static String git(Path cwd, String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            Process process = builder.start();
            process.getOutputStream().close();
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: "
                        + new String(err, StandardCharsets.UTF_8).strip());
            }
            return new String(out, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("cannot run git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted running git");
            return "";
        }
    }
}
