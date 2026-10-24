package com.verifone.cat.provision.transport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;

/**
 * A {@link Transport} backed by maps. A port of {@code petrotest.provision.transport.InMemoryTransport}.
 *
 * <p>It lives beside the interface, not in the test tree, as in Python: the preflight and recovery code
 * reaches {@code provision}, and their tests need a target that behaves like a rig. It records every write,
 * read and command, which is how "a no-op changed nothing" is asserted.
 *
 * <p>Failure injection mirrors the Python: {@link #failWritesTo} makes a put throw, {@link #failReadsOf}
 * makes a get time out, and {@link #silentlyDrop} makes a put succeed without storing anything (the §6.1
 * failure the verification step must catch).
 */
public final class InMemoryTransport implements Transport {

    private final String host;
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, Integer> modes = new HashMap<>();
    private final Map<String, CommandResult> commands = new HashMap<>();
    private final Set<String> directories = new HashSet<>();
    private final List<String> writes = new ArrayList<>();
    private final List<String> reads = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private final Set<String> failWrites = new HashSet<>();
    private final Set<String> failReads = new HashSet<>();
    private final Set<String> dropped = new HashSet<>();
    private RuntimeException runFailure;
    private boolean closed;

    public InMemoryTransport() {
        this("fake-target");
    }

    public InMemoryTransport(String host) {
        this.host = host;
    }

    @Override
    public String host() {
        return host;
    }

    /** Sets what {@link #run} returns for {@code command}. Unset commands succeed with no output. */
    public InMemoryTransport command(String command, CommandResult result) {
        commands.put(command, result);
        return this;
    }

    public InMemoryTransport failWritesTo(String path) {
        failWrites.add(path);
        return this;
    }

    public InMemoryTransport failReadsOf(String path) {
        failReads.add(path);
        return this;
    }

    public InMemoryTransport silentlyDrop(String path) {
        dropped.add(path);
        return this;
    }

    /** Seeds a file as if the box already had it. */
    public InMemoryTransport withFile(String path, byte[] content) {
        files.put(path, content);
        return this;
    }

    /** Makes every {@link #run} throw this, as a dropped or refused connection would. Test seam only. */
    public InMemoryTransport failRunsWith(RuntimeException failure) {
        runFailure = failure;
        return this;
    }

    @Override
    public CommandResult run(String command, Duration timeout) {
        ran.add(command);
        if (runFailure != null) {
            throw runFailure;
        }
        return commands.getOrDefault(command, new CommandResult(0, "", ""));
    }

    @Override
    public void put(String remotePath, byte[] content, int mode, Duration timeout) {
        writes.add(remotePath);
        if (failWrites.contains(remotePath)) {
            throw new DeviceError("put " + remotePath + " to " + host + " failed: injected failure");
        }
        if (dropped.contains(remotePath)) {
            return;
        }
        files.put(remotePath, content.clone());
        modes.put(remotePath, mode);
    }

    @Override
    public Optional<byte[]> get(String remotePath, Duration timeout) {
        reads.add(remotePath);
        if (failReads.contains(remotePath)) {
            throw new DeviceUnreachable("ssh " + host, "get " + remotePath + " timed out");
        }
        byte[] content = files.get(remotePath);
        return content == null ? Optional.empty() : Optional.of(content.clone());
    }

    @Override
    public void mkdir(String remotePath, Duration timeout) {
        directories.add(remotePath);
    }

    @Override
    public boolean exists(String remotePath, Duration timeout) {
        return files.containsKey(remotePath) || directories.contains(remotePath);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** The mode a file was last written with, or empty if it was never written. */
    public Optional<Integer> modeOf(String path) {
        return Optional.ofNullable(modes.get(path));
    }

    public List<String> writes() {
        return List.copyOf(writes);
    }

    public List<String> reads() {
        return List.copyOf(reads);
    }

    public List<String> ran() {
        return List.copyOf(ran);
    }

    public boolean closed() {
        return closed;
    }
}
