package com.verifone.cat.pats.runner.handlers.http;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.LockSupport;

import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;

/**
 * What the HTTP families reach outside a case, and what they keep for the run. Ported from the Python
 * {@code petrotest.pats.runner.http}'s module state: {@code _COOKIES} (a session cookie per Commander user name,
 * kept across cases, as PATS keeps it for the JVM) and {@code CURRENT_PAYLOADS} (the last reply of each kind, for
 * {@code VALIDATE_PAYLOAD}). Both live here, not in statics, so each run holds its own.
 *
 * <p>The Commander is not held here: the {@code commander_config} fixture is the {@code PdkShim} the case declares.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no {@code SEND_HTTP_REQUEST} has reached a Commander or the cloud from CAT-Java.
 */
public final class HttpContext {

    /** {@code request.retries} (deployed {@code automn_app_config.prop}): the Commander's attempts per request. */
    public static final int COMMANDER_ATTEMPTS = 15;
    /** {@code request.retries.sleep}: the pause between a busy Commander's attempts. */
    public static final Duration COMMANDER_RETRY_PAUSE = Duration.ofSeconds(60);
    /** {@code HttpPollingExecutor}'s default for {@code pollMaxSeconds}. */
    public static final int POLL_MAX_SECONDS = 60;
    /** {@code HttpPollingExecutor}'s default for {@code pollIntervalSeconds}. */
    public static final int POLL_INTERVAL_SECONDS = 5;

    private final CaseFiles files;
    private final CloudSite cloud;
    private final CloudSettle settle;
    private final Consumer<Duration> pause;
    private final LongSupplier nanoTime;
    private final Duration commanderRetryPause;
    private final int commanderAttempts;
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private final Map<String, byte[]> currentPayloads = new ConcurrentHashMap<>();

    /**
     * @param files  the case's payload, reference and rule files
     * @param cloud  the C-Site client, or {@code null} when none is wired (a CSITE step then fails by name)
     * @param settle the cloud-settle waits for cloud writes; {@link CloudSettle#disabled()} for none
     */
    public HttpContext(CaseFiles files, CloudSite cloud, CloudSettle settle) {
        this(files, cloud, settle, HttpContext::park, System::nanoTime, COMMANDER_RETRY_PAUSE, COMMANDER_ATTEMPTS);
    }

    /** The full constructor: a test passes a recording pause and a clock, so no test waits real time. */
    HttpContext(CaseFiles files, CloudSite cloud, CloudSettle settle, Consumer<Duration> pause,
            LongSupplier nanoTime, Duration commanderRetryPause, int commanderAttempts) {
        this.files = Objects.requireNonNull(files, "files");
        this.cloud = cloud;
        this.settle = Objects.requireNonNull(settle, "settle");
        this.pause = Objects.requireNonNull(pause, "pause");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.commanderRetryPause = Objects.requireNonNull(commanderRetryPause, "commanderRetryPause");
        if (commanderAttempts < 1) {
            throw new IllegalArgumentException("commanderAttempts must be at least 1");
        }
        this.commanderAttempts = commanderAttempts;
    }

    CaseFiles files() {
        return files;
    }

    Optional<CloudSite> cloud() {
        return Optional.ofNullable(cloud);
    }

    CloudSettle settle() {
        return settle;
    }

    void pause(Duration duration) {
        pause.accept(duration);
    }

    long nanoTime() {
        return nanoTime.getAsLong();
    }

    Duration commanderRetryPause() {
        return commanderRetryPause;
    }

    int commanderAttempts() {
        return commanderAttempts;
    }

    /** The session cookies, by Commander user name. */
    Map<String, String> cookies() {
        return cookies;
    }

    /** The last reply kept for each extension (".json", ".xml", ...), PATS's {@code CurrentPayload.<ext>}. */
    Map<String, byte[]> currentPayloads() {
        return currentPayloads;
    }

    /** Parks for {@code duration}; an interrupt is a step failure, never a silent retry. */
    static void park(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        for (long left = duration.toNanos(); left > 0; left = deadline - System.nanoTime()) {
            LockSupport.parkNanos(left);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new PatsStepError("interrupted during a pause in the Commander's retry schedule");
            }
        }
    }
}
