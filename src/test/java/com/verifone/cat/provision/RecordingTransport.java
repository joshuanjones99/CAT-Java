package com.verifone.cat.provision;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.provision.transport.Transport;

/**
 * An {@link InMemoryTransport} that also keeps one ordered list of every command and push, so a test can say which
 * came before which. The shim fake appends its {@code /health} reads to the same list.
 *
 * <p>Evidence about this repository only: nothing here reaches a host.
 */
final class RecordingTransport implements Transport {

    /** One thing that happened, as {@code kind} ("ran", "put", or a test's own) and its detail. */
    record Event(String kind, String detail) {}

    final InMemoryTransport inner;
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    RecordingTransport(String host) {
        this.inner = new InMemoryTransport(host);
    }

    List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    void record(String kind, String detail) {
        events.add(new Event(kind, detail));
    }

    /** Forgets what happened so far, so a test can assert on one run alone. */
    void clear() {
        events.clear();
    }

    /** The commands run, in order. */
    List<String> ran() {
        return events().stream().filter(e -> e.kind().equals("ran")).map(Event::detail).toList();
    }

    /** The paths pushed, in order. */
    List<String> puts() {
        return events().stream().filter(e -> e.kind().equals("put")).map(Event::detail).toList();
    }

    @Override
    public String host() {
        return inner.host();
    }

    @Override
    public CommandResult run(String command, Duration timeout) {
        record("ran", command);
        return inner.run(command, timeout);
    }

    @Override
    public void put(String remotePath, byte[] content, int mode, Duration timeout) {
        record("put", remotePath);
        inner.put(remotePath, content, mode, timeout);
    }

    @Override
    public Optional<byte[]> get(String remotePath, Duration timeout) {
        return inner.get(remotePath, timeout);
    }

    @Override
    public void mkdir(String remotePath, Duration timeout) {
        inner.mkdir(remotePath, timeout);
    }

    @Override
    public boolean exists(String remotePath, Duration timeout) {
        return inner.exists(remotePath, timeout);
    }

    @Override
    public void close() {
        inner.close();
    }
}
