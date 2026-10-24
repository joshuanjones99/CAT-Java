package com.verifone.cat.runservice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.LaunchResult;

/**
 * A run engine that writes the events it is given into its own folder and then waits for the test
 * to release it. Nothing here reaches a rig, a device or {@code C:\CICD}: the folder is a
 * {@code @TempDir}. Evidence about this repository only.
 */
public final class FakeRunEngine implements RunEngine {

    /** The most a test waits for the fake to reach a point, so a broken test fails rather than hangs. */
    static final Duration WAIT = Duration.ofSeconds(10);

    private final Path folder;
    private final List<String> events;
    private final String throwMessage;
    private final CountDownLatch written = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean launched;
    private volatile boolean paused;
    private volatile boolean stopped;
    private volatile LaunchConfig config;
    private List<String> closing = List.of();

    /** Lines the fake writes after the test releases it, so the run can end with its run.finished. */
    public FakeRunEngine closingWith(String... lines) {
        closing = List.of(lines);
        return this;
    }

    /**
     * @param folder       the run's folder, made by the test
     * @param events       NDJSON lines written before the fake waits
     * @param throwMessage when not null, {@link #launch} throws this, instead of writing and waiting
     */
    public FakeRunEngine(Path folder, List<String> events, String throwMessage) {
        this.folder = folder;
        this.events = List.copyOf(events);
        this.throwMessage = throwMessage;
    }

    @Override
    public LaunchResult launch(LaunchConfig launchConfig) {
        config = launchConfig;
        launched = true;
        if (throwMessage != null) {
            throw new IllegalStateException(throwMessage);
        }
        try {
            Files.createDirectories(folder);
            Files.write(folder.resolve("events.ndjson"), events, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written.countDown();
            if (!release.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the fake run");
            }
            Files.write(folder.resolve("events.ndjson"), closing, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return new LaunchResult(folder.getFileName().toString(), folder, List.of(), stopped);
    }

    /** Waits until the fake has written its events and is parked, so the test can read a mid-run view. */
    public void awaitWritten() throws InterruptedException {
        if (!written.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("the fake run never wrote its events");
        }
    }

    /** Lets the fake run finish. */
    public void release() {
        release.countDown();
    }

    @Override
    public Optional<Path> runFolder() {
        return launched ? Optional.of(folder) : Optional.empty();
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    @Override
    public boolean paused() {
        return paused;
    }

    @Override
    public void stop() {
        stopped = true;
        release.countDown();
    }

    /** Whether {@link #stop} was called. */
    public boolean stopRequested() {
        return stopped;
    }

    /** The config the run was launched with, or {@code null} before it was. */
    public LaunchConfig launchedWith() {
        return config;
    }

    /** Waits until the run thread has returned from the service's point of view. */
    public static void awaitIdle(RunService service) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (service.view().busy()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the run never ended");
            }
            Thread.onSpinWait();
        }
    }

}
