package com.verifone.cat.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs one of the proprietary jars the install shells out to, bounded by a timeout (DESIGN.md 6.3).
 *
 * <p>The jars are Verifone's, with no source here, so they are never reimplemented: CAT runs them with the
 * same argv {@code CICDScript.bat} gives. A port of the Python {@code _run_jar}, which took
 * {@code subprocess.run}; tests replace this seam with a scripted fake.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
@FunctionalInterface
public interface Jars {

    /** A finished jar: its exit code and both output streams, decoded as UTF-8. */
    record Result(int exitCode, String stdout, String stderr) {}

    /**
     * Runs {@code argv} to its end.
     *
     * @throws IOException when the process cannot start, or does not finish within {@code timeout} (it is
     *     killed first); the message says which
     */
    Result run(List<String> argv, Duration timeout) throws IOException;

    /** The real thing: a child process per call. */
    Jars SYSTEM = Jars::runProcess;

    private static Result runProcess(List<String> argv, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(argv).start();
        process.getOutputStream().close();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while " + argv.get(0) + " ran", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("timed out after " + timeout.toMillis() + " ms: " + argv.get(0));
        }
        try {
            return new Result(process.exitValue(), out.get(), err.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted reading the output of " + argv.get(0), e);
        } catch (ExecutionException e) {
            throw new IOException("could not read the output of " + argv.get(0), e.getCause());
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
