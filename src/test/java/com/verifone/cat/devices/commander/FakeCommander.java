package com.verifone.cat.devices.commander;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for the Commander's CGILink endpoint on loopback: the Java counterpart of the
 * Python tests' {@code httpx.MockTransport} handlers. Each test gives it an answer for a
 * request body; it records every request as it arrived on the wire.
 *
 * <p>Evidence about this repository only: it shows the client puts the bodies PATS puts on the
 * wire and reads the replies the Python CAT's tests quote, not that any Commander answers so.
 */
final class FakeCommander implements AutoCloseable {

    /** What the client sent: the body, the request target, two headers and the client's port. */
    record Request(String body, String target, String accept, String contentType, int remotePort, String method) {

        /** The {@code cmd=} value, read as the Python's handlers read it. */
        String cmd() {
            return fields().getOrDefault("cmd", "?");
        }

        /** The head's {@code key=value} pairs, split on {@code &} as the Python's handlers split them. */
        Map<String, String> fields() {
            String head = body.split("\n\n", 2)[0];
            Map<String, String> out = new LinkedHashMap<>();
            for (String part : head.split("&")) {
                String[] kv = part.split("=", 2);
                out.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
            return out;
        }
    }

    /** A reply: a status, a body, and how long to hold it back (a read timeout's double). */
    record Reply(int status, String body, Duration delay) {

        static Reply ok(String body) {
            return new Reply(200, body, Duration.ZERO);
        }

        static Reply status(int status, String body) {
            return new Reply(status, body, Duration.ZERO);
        }

        static Reply late(Duration delay, String body) {
            return new Reply(200, body, delay);
        }
    }

    static final String OK = "<VFI:Response/>";
    static final String NS_OK = "<VFI:Response xmlns:VFI=\"urn:vfi-sapphire:np.cgilink.2001-07-01\"/>";

    /** A fault as the Python's tests write it: an undeclared {@code VFI:} prefix, as text matching sees it. */
    static String bareFault(String code) {
        return "<VFI:Fault><faultCode>" + code + "</faultCode></VFI:Fault>";
    }

    /** A fault with its namespace declared, as {@code test_commander_snapshot.py} writes it. */
    static String fault(String code) {
        return "<VFI:Response xmlns:VFI=\"urn:vfi-sapphire:np.domain.2001-07-01\"><VFI:Fault>"
                + "<faultCode>" + code + "</faultCode><faultString>" + code + "</faultString>"
                + "</VFI:Fault></VFI:Response>";
    }

    static final String FRESH_COOKIE = "<credential><cookie>fresh</cookie></credential>";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fake-commander");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile Function<Request, Reply> answer;
    final List<Request> requests = new CopyOnWriteArrayList<>();

    FakeCommander(Function<Request, Reply> answer) {
        this.answer = answer;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Request request = new Request(body, exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRemoteAddress().getPort(), exchange.getRequestMethod());
            requests.add(request);
            Reply reply;
            try {
                reply = this.answer.apply(request);
            } catch (RuntimeException e) {
                reply = Reply.status(500, "fake commander: " + e);
            }
            if (!reply.delay().isZero()) {
                try {
                    // A bounded hold, ended early when the fake closes.
                    closed.await(reply.delay().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException e) {
                // The client gave up on a held reply; nothing to answer.
            }
        });
        server.start();
    }

    /** An answer that replays {@code replies} in order, one per request. */
    static Function<Request, Reply> inOrder(List<Reply> replies) {
        List<Reply> queue = new ArrayList<>(replies);
        return request -> {
            synchronized (queue) {
                if (queue.isEmpty()) {
                    throw new IllegalStateException("no reply left for " + request.body());
                }
                return queue.remove(0);
            }
        };
    }

    void answer(Function<Request, Reply> next) {
        this.answer = next;
    }

    String host() {
        return InetAddress.getLoopbackAddress().getHostAddress();
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** The bodies, in arrival order. */
    List<String> bodies() {
        List<String> out = new ArrayList<>();
        for (Request request : requests) {
            out.add(request.body());
        }
        return out;
    }

    /** Each body's command as the Python's {@code _commands} reads it: between {@code cmd=} and {@code &cookie=}. */
    List<String> commands() {
        List<String> out = new ArrayList<>();
        for (String body : bodies()) {
            out.add(body.split("cmd=", 2)[1].split("&cookie=", 2)[0]);
        }
        return out;
    }

    /** A client on this fake with a recording pauser. */
    PdkShim client(List<Duration> pauses) {
        return client(pauses, Duration.ofSeconds(5), null, PdkShim.COMMANDER_PASSWORD, false);
    }

    PdkShim client(List<Duration> pauses, Duration timeout, Supplier<String> readOtp, String password,
            boolean passwordMayBeExpired) {
        return new PdkShim(host(), port(), timeout, readOtp, password, passwordMayBeExpired, pauses::add);
    }

    /** A client with a cookie already cached, as the Python's tests seed {@code _cookie}. */
    PdkShim clientWithCookie(List<Duration> pauses, String cookie) {
        PdkShim client = client(pauses);
        client.adoptCookie(cookie);
        return client;
    }

    static String fixture(String name) {
        try (InputStream in = FakeCommander.class.getResourceAsStream("/commander/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
