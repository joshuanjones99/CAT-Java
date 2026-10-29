package com.verifone.cat.collect;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Throwaway git repositories for the collect tests, built from git objects, never a working tree.
 *
 * <p>{@code testcases/} and {@code TestCases/} cannot both exist in a Windows checkout, so the fixtures are written
 * with {@code fast-import} and {@code core.ignorecase=false}. Every repository lives under a {@code @TempDir}; nothing
 * here touches a real checkout or a real remote. The Python CAT's {@code tests/unit/petroapps_repo.py}.
 */
public final class PetroappsFixture {

    public static final String CASE = "AutomationTests/testcases";
    public static final String BDD = "AutomationTests/TestCases";

    /** A committer identity for the one command that needs one ({@code commit-tree}, {@code commit}). */
    public static final String[] IDENTITY = {"-c", "user.name=f", "-c", "user.email=f@example.invalid"};

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private PetroappsFixture() {}

    /** Whether a {@code git} executable is on PATH. Tests that need one are skipped when it is not. */
    public static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code git -C <repo> <args>}, stdout as text, stripped. Fails the test on a non-zero exit. */
    public static String git(Path repo, String... args) {
        return new String(gitBytes(repo, args), StandardCharsets.UTF_8).strip();
    }

    /** {@code git -C <repo> <args>}, stdout as bytes. Fails the test on a non-zero exit. */
    public static byte[] gitBytes(Path repo, String... args) {
        return gitWithInput(repo, null, args);
    }

    /** {@code git -C <repo> <args>} with {@code stdin} written to git's standard input. */
    public static byte[] gitWithInput(Path repo, byte[] stdin, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            if (stdin != null) {
                process.getOutputStream().write(stdin);
            }
            process.getOutputStream().close();
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            if (!process.waitFor(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("git " + String.join(" ", args) + " timed out");
            }
            if (process.exitValue() != 0) {
                fail("git " + String.join(" ", args) + " failed: " + new String(err, StandardCharsets.UTF_8));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run git", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running git", e);
        }
    }

    /**
     * One root commit on a new {@code ref} holding exactly {@code files}, via one {@code fast-import}. Returns the
     * commit ID.
     */
    public static String commit(Path repo, String ref, Map<String, byte[]> files) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        write(stream, "commit " + ref + "\ncommitter fixture <fixture@example.invalid> 0 +0000\ndata 7\nfixture\n");
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            write(stream, "M 100644 inline " + file.getKey() + "\ndata " + file.getValue().length + "\n");
            stream.write(file.getValue(), 0, file.getValue().length);
            write(stream, "\n");
        }
        gitWithInput(repo, stream.toByteArray(), "-c", "core.ignorecase=false", "fast-import", "--quiet");
        return git(repo, "rev-parse", ref);
    }

    /** {@code pairs} as path, content, path, content...: the content UTF-8 encoded, in order. */
    public static Map<String, byte[]> files(String... pairs) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            files.put(pairs[i], pairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    /** {@code base} with {@code pairs} added (or replaced), in order. */
    public static Map<String, byte[]> with(Map<String, byte[]> base, String... pairs) {
        Map<String, byte[]> merged = new LinkedHashMap<>(base);
        merged.putAll(files(pairs));
        return merged;
    }

    /**
     * A bare "remote" and a dirty local checkout whose {@code origin} is that remote, in {@code tmp}: one committed
     * file, a modified tracked file and an untracked one, and it has never fetched. Returns {remote, local}.
     */
    public static Path[] remoteAndCheckout(Path tmp) throws IOException {
        Path remote = tmp.resolve("remote.git");
        git(tmp, "init", "-q", "--bare", remote.toString());
        Path local = tmp.resolve("petroapps");
        git(tmp, "init", "-q", local.toString());
        Files.writeString(local.resolve("tracked.txt"), "committed\n", StandardCharsets.UTF_8);
        git(local, "add", "tracked.txt");
        git(local, "-c", "user.name=f", "-c", "user.email=f@example.invalid", "commit", "-qm", "local");
        Files.writeString(local.resolve("tracked.txt"), "modified, not committed\n", StandardCharsets.UTF_8);
        Files.writeString(local.resolve("untracked.txt"), "the operator's\n", StandardCharsets.UTF_8);
        git(local, "remote", "add", "origin", remote.toString());
        return new Path[] {remote, local};
    }

    /**
     * Makes {@code local} current with its remote's {@code branch} and leaves it dirty. Fetches, then points
     * {@code HEAD} at the branch by reference only: the working tree and the index stay as they were.
     */
    public static void track(Path local, String branch) {
        git(local, "fetch", "-q", "origin", "+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        git(local, "update-ref", "refs/heads/" + branch, "refs/remotes/origin/" + branch);
        git(local, "symbolic-ref", "HEAD", "refs/heads/" + branch);
    }

    private static void write(ByteArrayOutputStream stream, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(bytes, 0, bytes.length);
    }
}
