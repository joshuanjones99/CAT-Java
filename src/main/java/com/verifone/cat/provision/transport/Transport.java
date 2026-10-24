package com.verifone.cat.provision.transport;

import java.time.Duration;
import java.util.Optional;

/**
 * Everything {@code provision} needs from a target box. A port of {@code petrotest.provision.transport.Transport}.
 *
 * <p>The interface is deliberately narrow: the push logic (hash guard, manifest comparison, post-push
 * verification) is exercised against {@link InMemoryTransport} with no rig, and only these methods need
 * hardware to verify.
 *
 * <p>Every method takes an explicit {@code timeout} with no default (DESIGN.md §6.3: no unbounded blocking
 * call). A non-positive timeout is refused by the implementations, because JSch reads zero as "forever".
 *
 * <p>Implementations are not thread-safe: one caller at a time, as the Python one is.
 */
public interface Transport extends AutoCloseable {

    /** The box's address, for logging and error messages only. It comes from the rig file and nowhere else. */
    String host();

    /** Runs one command. A non-zero exit is a result, not an exception; a failure to run is one. */
    CommandResult run(String command, Duration timeout);

    /** Writes a file with the given octal-style permission bits, creating its parent directories. */
    void put(String remotePath, byte[] content, int mode, Duration timeout);

    /** Reads a remote file, or empty if it does not exist. Absence is a value, not an exception. */
    Optional<byte[]> get(String remotePath, Duration timeout);

    /** Creates a directory and any missing parents ({@code mkdir -p}). */
    void mkdir(String remotePath, Duration timeout);

    /** Whether a remote path exists. A path that cannot be examined is an error, not {@code false}. */
    boolean exists(String remotePath, Duration timeout);

    @Override
    void close();
}
