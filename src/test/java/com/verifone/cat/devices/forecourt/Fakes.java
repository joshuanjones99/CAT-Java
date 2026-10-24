package com.verifone.cat.devices.forecourt;

import static com.verifone.cat.devices.forecourt.JavaSerial.Field;
import static com.verifone.cat.devices.forecourt.JavaSerial.JavaClass;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Loopback fakes of Petro Simulator Suite's sockets, and the helpers the forecourt tests share. Nothing here reaches a
 * real simulator: every server is on this machine, on an ephemeral port, and every host is {@code localhost}.
 *
 * <p>The fixtures are the streams the real JDK wrote with the suite's own jar (the Python tests' {@code Gen.java}, not
 * copied here), so the encoders and the decoder are checked against Java, not against each other.
 */
final class Fakes {

    static final ForecourtTimeouts FAST = new ForecourtTimeouts(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5));
    static final String HOST = "localhost";

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "forecourt-test-timer");
        t.setDaemon(true);
        return t;
    });
    private static final CountDownLatch NEVER = new CountDownLatch(1);

    static final JavaClass STATUS_INFO = new JavaClass(ForecourtWire.FUEL_ENT + "StatusInfo", 1L, List.of(
            Field.prim('S', "fuelingPosition"),
            Field.prim('S', "gradeNum"),
            new Field('L', "deviceStatus", "Ljava/lang/String;"),
            new Field('L', "notification", "Ljava/lang/String;")), null);
    static final JavaClass STATUS_INFO_ARRAY = new JavaClass("[L" + STATUS_INFO.name() + ";", -6693312931190890026L);

    private Fakes() {
    }

    static byte[] fixture(String name) {
        try (InputStream in = Fakes.class.getResourceAsStream("/com/verifone/cat/devices/forecourt/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }

    /** A {@code StatusInfo[]} as {@code FuelClientAdapter} writes it: (pump, grade, state, notification) per pump. */
    static byte[] status(Object... quads) {
        List<JavaSerial.Obj> items = new ArrayList<>();
        for (int i = 0; i < quads.length; i += 4) {
            items.add(new JavaSerial.Obj(STATUS_INFO, Map.of(
                    "fuelingPosition", quads[i],
                    "gradeNum", quads[i + 1],
                    "deviceStatus", quads[i + 2],
                    "notification", quads[i + 3])));
        }
        return JavaSerial.dumps(new JavaSerial.Arr(STATUS_INFO_ARRAY, items));
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port", e);
        }
    }

    static boolean complete(byte[] data) {
        try {
            JavaSerial.loads(data);
            return true;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    /**
     * Pushes {@code payload} to {@code port} as the suite does. With {@code reply}, reads the answer until it is one
     * whole object and returns its raw bytes.
     */
    static byte[] push(int port, byte[] payload, boolean reply) {
        try (Socket conn = new Socket()) {
            conn.connect(new InetSocketAddress(HOST, port), 5000);
            conn.setSoTimeout(5000);
            OutputStream out = conn.getOutputStream();
            out.write(payload);
            out.flush();
            if (!reply) {
                return new byte[0];
            }
            return readRaw(conn.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("push to port " + port + " failed", e);
        }
    }

    /** Reads raw bytes until they form one whole object, or the peer closes. */
    static byte[] readRaw(InputStream in) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        while (!complete(data.toByteArray())) {
            int n = in.read(chunk);
            if (n < 0) {
                break;
            }
            data.write(chunk, 0, n);
        }
        return data.toByteArray();
    }

    /** Runs {@code action} after {@code delay}, on a daemon timer. Failures are swallowed, as a Python Timer's are. */
    static void after(Duration delay, Runnable action) {
        SCHEDULER.schedule(() -> {
            try {
                action.run();
            } catch (RuntimeException expected) {
                // a push a test expected to fail, or a closed client
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Polls {@code condition} until it holds, each step a bounded park; fails when {@code timeout} runs out. */
    static void until(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("condition not met within " + timeout);
            }
            try {
                NEVER.await(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while polling");
            }
        }
    }

    /**
     * A fake of the suite's request port: keeps each request it reads, and with {@code reply} answers each one as the
     * DCR side does.
     */
    static final class Simulator implements AutoCloseable {
        private final ServerSocket server;
        private final byte[] reply;
        private final List<byte[]> received = new ArrayList<>();
        private final Object changed = new Object();

        Simulator() {
            this(null);
        }

        Simulator(byte[] reply) {
            this.reply = reply;
            try {
                this.server = new ServerSocket(0);
            } catch (IOException e) {
                throw new IllegalStateException("cannot start the fake simulator", e);
            }
            Thread thread = new Thread(this::serve, "fake-simulator");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                Socket conn;
                try {
                    conn = server.accept();
                } catch (IOException e) {
                    return;
                }
                try (conn) {
                    byte[] data = readRequest(conn.getInputStream());
                    if (reply != null) {
                        conn.getOutputStream().write(reply);
                        conn.getOutputStream().flush();
                    }
                    synchronized (changed) {
                        received.add(data);
                        changed.notifyAll();
                    }
                } catch (IOException e) {
                    // a client that went away mid-request: nothing to record
                }
            }
        }

        private byte[] readRequest(InputStream in) throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            while (true) {
                int n = in.read(chunk);
                if (n < 0) {
                    break;
                }
                data.write(chunk, 0, n);
                if (reply != null && complete(data.toByteArray())) {
                    break;
                }
            }
            return data.toByteArray();
        }

        /** Waits until {@code count} requests have arrived, and returns them all. */
        List<byte[]> waitFor(int count) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (changed) {
                while (received.size() < count) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        fail("expected " + count + " requests, got " + received.size());
                    }
                    try {
                        changed.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fail("interrupted while waiting for requests");
                    }
                }
                return List.copyOf(received);
            }
        }

        /** The requests received so far, without waiting. */
        List<byte[]> received() {
            synchronized (changed) {
                return List.copyOf(received);
            }
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException e) {
                // already closed
            }
        }
    }
}
