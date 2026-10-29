package com.verifone.cat.devices.vats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.verifone.cat.devices.DeviceUnreachable;

/**
 * The CAT side of {@code agent.py}: start it under the SDK's Python, call its ops. A port of the Python CAT's
 * {@code petrotest.devices.vats.client.AgentClient}.
 *
 * <p>Each call writes one JSON line and waits, on a queue fed by a reader thread, not on the clock, for the reply
 * carrying its id, bounded by the call's own budget plus {@link #MARGIN_SECONDS} for the round trip. The agent's stderr
 * (and the SDK's prints, which the agent points there) is appended to {@code agent.log} in the work folder.
 *
 * <p>Deviations: requests are written with every non-ASCII character escaped, so the agent's stdin decodes the same
 * whatever its console code page (the Python client relied on the agent's own locale); a stdout line that is not JSON
 * is logged and skipped where the Python's reader thread would have died on it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's {@code hello} and {@code screen} ran against the lab M400
 * on 2026-10-06; this client has run only against a stand-in SDK.
 */
public final class AgentClient implements VatsAgent {

    private static final Logger LOG = LoggerFactory.getLogger(AgentClient.class);

    /** Added to every op's own budget: process round trip and the SDK's bookkeeping. */
    public static final double MARGIN_SECONDS = 10.0;

    /** The agent's log in the work folder. */
    public static final String LOG_FILE = "agent.log";

    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** What the reader thread queues when the agent's stdout ends. */
    private static final Map<String, Object> EOF = Map.of();

    private final VatsSdk sdk;
    private final int km4Port;
    private final Path workdir;
    private final Path script;
    private final AtomicLong ids = new AtomicLong();
    private final BlockingQueue<Map<String, Object>> replies = new LinkedBlockingQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Process process;

    /**
     * @param script the agent to run: {@link AgentScript#extract} of the shipped one
     */
    public AgentClient(VatsSdk sdk, int km4Port, Path workdir, Path script) {
        this.sdk = sdk;
        this.km4Port = km4Port;
        this.workdir = workdir;
        this.script = script;
    }

    /** The command line: the SDK's Python, the agent, the SDK's {@code vats_runner} and the KM4 port. */
    public List<String> argv() {
        return List.of(sdk.python().toString(), script.toString(), "--sdk", sdk.vatsRunner().toString(),
                "--km4-port", String.valueOf(km4Port));
    }

    /** The agent's log: where its stderr, and so the SDK's prints, go. */
    public Path logFile() {
        return workdir.resolve(LOG_FILE);
    }

    /** Starts the agent; its working directory is the SDK's {@code app/}, as the Python's. */
    public AgentClient start() {
        try {
            Files.createDirectories(workdir);
            ProcessBuilder builder = new ProcessBuilder(argv())
                    .directory(sdk.app().toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
            process = builder.start();
        } catch (IOException e) {
            throw new DeviceUnreachable("the VATS agent", "could not start " + argv().get(0) + ": " + e.getMessage(),
                    e);
        }
        Process started = process;
        Thread reader = new Thread(() -> read(started), "vats-agent-reader");
        reader.setDaemon(true);
        reader.start();
        return this;
    }

    private void read(Process started) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    replies.put(JSON.readValue(line, MAP));
                } catch (JsonProcessingException e) {
                    LOG.warn("VATS agent: not a reply, skipped: {}", line);
                }
            }
        } catch (IOException e) {
            LOG.debug("VATS agent: stdout closed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            replies.offer(EOF); // EOF: the agent is gone
        }
    }

    @Override
    public Map<String, Object> call(String op, double budgetSeconds, Map<String, Object> args) {
        Process running = process;
        if (running == null || !running.isAlive()) {
            throw new DeviceUnreachable("the VATS agent", "not running; see " + logFile());
        }
        double wait = budgetSeconds + MARGIN_SECONDS;
        Map<String, Object> reply;
        lock.lock();
        try {
            long id = ids.incrementAndGet();
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", id);
            request.put("op", op);
            request.putAll(args);
            try {
                OutputStream stdin = running.getOutputStream();
                stdin.write((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.US_ASCII));
                stdin.flush();
            } catch (IOException e) {
                throw new DeviceUnreachable("the VATS agent", "exited during '" + op + "'; see " + logFile(), e);
            }
            long deadline = System.nanoTime() + (long) (wait * 1_000_000_000L);
            while (true) {
                long left = deadline - System.nanoTime();
                Map<String, Object> next;
                try {
                    next = left <= 0 ? null : replies.poll(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DeviceUnreachable("the VATS agent", "interrupted during '" + op + "'");
                }
                if (next == null) {
                    throw new DeviceUnreachable("the VATS agent", "no answer to '" + op + "' within "
                            + String.format(Locale.ROOT, "%.0f", wait) + "s");
                }
                if (next == EOF) {
                    replies.offer(EOF); // every later call sees the agent gone too
                    throw new DeviceUnreachable("the VATS agent", "exited during '" + op + "'; see " + logFile());
                }
                Object replyId = next.get("id");
                if (replyId instanceof Number n && n.longValue() == id) {
                    reply = next;
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
        if (!VatsAgent.truthy(reply, "ok")) {
            throw new AgentError("VATS " + op + ": " + reply.get("error"));
        }
        Object result = reply.get("result");
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    /** Says {@code bye}; an agent that does not leave within 5 s is killed. */
    @Override
    public void close() {
        Process running = process;
        process = null;
        if (running == null || !running.isAlive()) {
            return;
        }
        try {
            OutputStream stdin = running.getOutputStream();
            stdin.write("{\"id\": 0, \"op\": \"bye\"}\n".getBytes(StandardCharsets.US_ASCII));
            stdin.flush();
            if (!running.waitFor(5, TimeUnit.SECONDS)) {
                running.destroyForcibly();
            }
        } catch (IOException e) {
            running.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.destroyForcibly();
        }
    }
}
