package com.verifone.cat.launch;

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
import java.util.concurrent.TimeUnit;

import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.devices.forecourt.JavaSerial;
import com.verifone.cat.devices.forecourt.PetroDcr;
import com.verifone.cat.devices.forecourt.PetroPumps;

/**
 * A loopback fake of Petro Simulator Suite's sockets for the launch tests: its request port (the pumps' 9700 or the
 * DCR's 1053), which keeps what it reads and, for the DCR, answers each request with the suite's {@code Status}; and
 * the pushes the suite makes to PATS's listen ports. Every socket is on this machine, on an ephemeral port: nothing
 * here can reach a real simulator.
 */
final class FakePetroSuite implements AutoCloseable {

    static final String HOST = "localhost";
    static final ForecourtTimeouts FAST = new ForecourtTimeouts(
            Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5));

    private static final JavaSerial.JavaClass STATUS_INFO = new JavaSerial.JavaClass(
            "com.verifone.isd.pcapps.simulators.forecourt.fuel.comm.ent.StatusInfo", 1L, List.of(
                    JavaSerial.Field.prim('S', "fuelingPosition"),
                    JavaSerial.Field.prim('S', "gradeNum"),
                    new JavaSerial.Field('L', "deviceStatus", "Ljava/lang/String;"),
                    new JavaSerial.Field('L', "notification", "Ljava/lang/String;")), null);
    private static final JavaSerial.JavaClass STATUS_INFO_ARRAY =
            new JavaSerial.JavaClass("[L" + STATUS_INFO.name() + ";", -6693312931190890026L);

    private final ServerSocket server;
    private final byte[] reply;
    private final List<byte[]> received = new ArrayList<>();
    private final Object changed = new Object();

    /** The pumps' request port: no reply. */
    static FakePetroSuite pumps() {
        return new FakePetroSuite(null);
    }

    /** The DCR's request port: each request answered with the suite's {@code Status}. */
    static FakePetroSuite dcr() {
        return new FakePetroSuite(resource("dcr_ack.bin"));
    }

    private FakePetroSuite(byte[] reply) {
        this.reply = reply;
        try {
            server = new ServerSocket(0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the fake suite", e);
        }
        Thread thread = new Thread(this::serve, "fake-petro-suite");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** A pump client on an ephemeral listen port, logged in to this fake, started. */
    PetroPumps startedPumps(int listenPort) {
        return PetroPumps.started(HOST, listenPort, port(), null, FAST);
    }

    /** DCR 1 on an ephemeral listen port, logged in to this fake, started. */
    PetroDcr startedDcr(int listenPort) {
        return PetroDcr.started(HOST, 1, listenPort, port(), FAST);
    }

    /** The requests read so far. */
    List<byte[]> received() {
        synchronized (changed) {
            return List.copyOf(received);
        }
    }

    /** Waits until {@code count} requests have arrived. */
    void awaitRequests(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (changed) {
            while (received.size() < count) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("expected " + count + " requests, got " + received.size());
                }
                try {
                    changed.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted");
                }
            }
        }
    }

    /** Pushes one {@code StatusInfo[]} to a pump listener, as the suite does on a state change. */
    static void pushPumpState(int listenPort, int pump, String state) {
        byte[] payload = JavaSerial.dumps(new JavaSerial.Arr(STATUS_INFO_ARRAY, List.of(new JavaSerial.Obj(STATUS_INFO,
                Map.of("fuelingPosition", pump, "gradeNum", 0, "deviceStatus", state,
                        "notification", "ACK")))));
        try (Socket conn = new Socket()) {
            conn.connect(new InetSocketAddress(HOST, listenPort), 5000);
            OutputStream out = conn.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("push to port " + listenPort + " failed", e);
        }
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port", e);
        }
    }

    static byte[] resource(String name) {
        try (InputStream in = FakePetroSuite.class.getResourceAsStream("/com/verifone/cat/devices/forecourt/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket conn = server.accept()) {
                byte[] data = read(conn.getInputStream());
                if (reply != null) {
                    conn.getOutputStream().write(reply);
                    conn.getOutputStream().flush();
                }
                synchronized (changed) {
                    received.add(data);
                    changed.notifyAll();
                }
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        while (true) {
            int n = in.read(chunk);
            if (n < 0) {
                return data.toByteArray();
            }
            data.write(chunk, 0, n);
            if (reply != null && complete(data.toByteArray())) {
                return data.toByteArray();
            }
        }
    }

    private static boolean complete(byte[] data) {
        try {
            JavaSerial.loads(data);
            return true;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return false;
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
