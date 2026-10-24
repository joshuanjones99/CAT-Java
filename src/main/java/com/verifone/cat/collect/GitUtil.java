package com.verifone.cat.collect;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Runs the {@code git} executable as a child process, with bounded waits, and reads git objects in one batch.
 *
 * <p>The one place the collect code starts git: every call names its own timeout and environment, a run start never
 * sits on a credential prompt ({@link #NO_PROMPT}), and nothing is decoded as text except git's own messages.
 * Process I/O is drained on daemon threads so a full pipe cannot deadlock a wait.
 *
 * <p>The Python CAT's {@code petrotest.gitutil}. Java port UNVERIFIED AGAINST HARDWARE; it runs only against local
 * git repositories in the tests.
 */
public final class GitUtil {

    /** A run start must never sit on a credential prompt nobody can see. */
    public static final Map<String, String> NO_PROMPT = Map.of("GIT_TERMINAL_PROMPT", "0", "GCM_INTERACTIVE", "never");

    /**
     * The bound on a git read. The Python CAT's {@code read_blobs} has none; a Java wait is always bounded, and a
     * batch read of a plan's blobs finishes well inside it.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** How long a finished git process may keep its output pipes open before the read is given up. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern FULL_OBJECT_ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /** git's captured output: exit code, stdout and stderr as bytes. */
    public record Output(int exitCode, byte[] stdout, byte[] stderr) {}

    private GitUtil() {}

    /**
     * {@code git -C <repo> <args>}, output captured.
     *
     * @param env   merged over the parent's environment; {@code null} leaves the child the parent's environment
     * @param stdin the bytes to write to git's stdin, or {@code null} for an empty stdin
     * @throws CollectException when git cannot start, or does not finish within {@code timeout}
     */
    public static Output run(Path repo, List<String> args, Map<String, String> env, byte[] stdin, Duration timeout) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", repo.toString()));
        argv.addAll(args);
        String describe = "git " + String.join(" ", args);
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (env != null) {
            builder.environment().putAll(env);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CollectException(describe + ": " + e.getMessage(), e);
        }
        CompletableFuture<byte[]> out = drain(process.getInputStream(), "git-stdout");
        CompletableFuture<byte[]> err = drain(process.getErrorStream(), "git-stderr");
        feed(process.getOutputStream(), stdin);
        boolean done;
        try {
            done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CollectException(describe + ": interrupted", e);
        }
        if (!done) {
            process.destroyForcibly();
            throw new CollectException(describe + ": timed out after " + timeout.toSeconds() + " seconds");
        }
        return new Output(process.exitValue(), await(out, describe), await(err, describe));
    }

    /**
     * {@code git -C <repo> <args>} for a read that must succeed: the stdout, or a {@link CollectException} naming the
     * command and git's own reason.
     */
    public static byte[] git(Path repo, Duration timeout, Map<String, String> env, String... args) {
        List<String> list = List.of(args);
        Output done = run(repo, list, env, null, timeout);
        if (done.exitCode() != 0) {
            String detail = new String(done.stderr(), StandardCharsets.UTF_8).strip();
            throw new CollectException("git " + String.join(" ", list) + " failed in " + repo + ": " + detail);
        }
        return done.stdout();
    }

    /**
     * Reads unique git object IDs in one {@code git cat-file --batch} process, preserving arbitrary blob bytes.
     *
     * @return sha to bytes, in the order the IDs were first given
     * @throws IllegalArgumentException when an ID is not a full lowercase object ID
     * @throws CollectException         when git's batch reply is incomplete, of the wrong type, or has trailing output
     */
    public static Map<String, byte[]> readBlobs(Path repo, Iterable<String> shas) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String sha : shas) {
            wanted.add(sha);
        }
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return blobs;
        }
        StringBuilder request = new StringBuilder();
        for (String sha : wanted) {
            if (!FULL_OBJECT_ID.matcher(sha).matches()) {
                throw new IllegalArgumentException("git cat-file: expected full lowercase object IDs");
            }
            request.append(sha).append('\n');
        }
        Output done = run(repo, List.of("cat-file", "--batch"), null,
                request.toString().getBytes(StandardCharsets.US_ASCII), DEFAULT_TIMEOUT);
        return parseBatch(done.stdout(), wanted);
    }

    private static Map<String, byte[]> parseBatch(byte[] raw, Set<String> wanted) {
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        int offset = 0;
        for (String sha : wanted) {
            int end = indexOf(raw, (byte) '\n', offset);
            String[] header = end >= 0
                    ? new String(raw, offset, end - offset, StandardCharsets.ISO_8859_1).strip().split("\\s+")
                    : new String[0];
            if (header.length != 3 || !header[0].equals(sha) || !header[1].equals("blob")) {
                throw new CollectException("git cat-file: cannot read blob " + sha + ": " + Arrays.toString(header));
            }
            long size;
            try {
                size = Long.parseLong(header[2]);
            } catch (NumberFormatException e) {
                throw new CollectException("git cat-file: cannot read blob " + sha + ": bad size", e);
            }
            offset = end + 1;
            long stop = offset + size;
            if (size < 0 || stop >= raw.length || raw[(int) stop] != '\n') {
                throw new CollectException("git cat-file: truncated blob " + sha);
            }
            blobs.put(sha, Arrays.copyOfRange(raw, offset, (int) stop));
            offset = (int) stop + 1;
        }
        if (offset != raw.length) {
            throw new CollectException("git cat-file: unexpected trailing output");
        }
        return blobs;
    }

    private static int indexOf(byte[] raw, byte value, int from) {
        for (int i = from; i < raw.length; i++) {
            if (raw[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static CompletableFuture<byte[]> drain(InputStream in, String name) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                future.complete(in.readAllBytes());
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    private static void feed(OutputStream out, byte[] data) {
        Thread thread = new Thread(() -> {
            try (out) {
                if (data != null) {
                    out.write(data);
                }
            } catch (IOException ignored) {
                // git exited before reading its stdin; its exit code says why.
            }
        }, "git-stdin");
        thread.setDaemon(true);
        thread.start();
    }

    private static byte[] await(CompletableFuture<byte[]> future, String describe) {
        try {
            return future.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new CollectException(describe + ": " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new CollectException(describe + ": output did not close", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CollectException(describe + ": interrupted", e);
        }
    }
}
