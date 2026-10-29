package com.verifone.cat.provision.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.devices.DeviceError;

/**
 * Reverse port forwards with no network: the box's side of each forwarded connection is an in-memory pipe pair that a
 * test reads and writes. Used by {@link InMemoryTransport} and by test fakes of {@link Transport}, so the cloud
 * tunnel's serve loop and relay run without an SSH session.
 *
 * <p>{@link #connect} plays a client on the box dialling a forward's port: the handler gets one end (on the calling
 * thread, as a transport's dispatcher would call it, so a handler that blocks shows up as a blocked test), and the
 * caller gets the other. Half-closes and closes behave as a TCP pair's do.
 */
public final class InMemoryForwards {

    /** One requested forward. */
    public record Forward(String bindAddress, int port, ForwardHandler handler) {}

    private final Map<Integer, Forward> forwards = new LinkedHashMap<>();
    private final List<Integer> requested = new ArrayList<>();
    private String refusal;

    /** Makes every later request fail as sshd's refusal would, with {@code reason}. Null accepts them again. */
    public synchronized void refuse(String reason) {
        refusal = reason;
    }

    /** Records the forward; a port already forwarded on this session is refused, as sshd refuses it. */
    public synchronized void request(String bindAddress, int port, ForwardHandler handler) {
        requested.add(port);
        if (refusal != null) {
            throw new DeviceError("remote port forwarding failed for listen port " + port + ": " + refusal);
        }
        if (forwards.containsKey(port)) {
            throw new DeviceError("remote port forwarding failed for listen port " + port + ": already forwarded");
        }
        forwards.put(port, new Forward(bindAddress, port, handler));
    }

    /** The forwards in force, in the order they were requested. */
    public synchronized List<Forward> forwards() {
        return List.copyOf(forwards.values());
    }

    /** Every port asked for, refused ones included, in order. */
    public synchronized List<Integer> requested() {
        return List.copyOf(requested);
    }

    /** Ends every forward, as a closed session does. */
    public synchronized void clear() {
        forwards.clear();
    }

    /**
     * A client on the box connects to {@code port}: the forward's handler receives one end, and this returns the
     * other (the box client's end).
     *
     * @throws DeviceError when nothing forwards that port
     */
    public ForwardedConnection connect(int port) {
        Forward forward;
        synchronized (this) {
            forward = forwards.get(port);
        }
        if (forward == null) {
            throw new DeviceError("connection refused: nothing forwards port " + port);
        }
        Pipe toController = new Pipe();
        Pipe toBox = new Pipe();
        ForwardedConnection controllerEnd = new End(port, toController, toBox);
        ForwardedConnection boxEnd = new End(port, toBox, toController);
        forward.handler().accept(controllerEnd);
        return boxEnd;
    }

    /** One direction: a growing buffer with a reader and a writer. */
    static final class Pipe {
        private byte[] data = new byte[1024];
        private int start;
        private int end;
        private boolean writerClosed;
        private boolean readerClosed;

        synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            if (readerClosed || writerClosed) {
                throw new IOException("pipe closed");
            }
            if (end + length > data.length) {
                int live = end - start;
                byte[] grown = new byte[Math.max(data.length * 2, live + length)];
                System.arraycopy(data, start, grown, 0, live);
                data = grown;
                start = 0;
                end = live;
            }
            System.arraycopy(bytes, offset, data, end, length);
            end += length;
            notifyAll();
        }

        synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (start == end && !writerClosed && !readerClosed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("read interrupted");
                }
            }
            if (readerClosed) {
                throw new IOException("pipe closed");
            }
            if (start == end) {
                return -1;
            }
            int n = Math.min(length, end - start);
            System.arraycopy(data, start, bytes, offset, n);
            start += n;
            return n;
        }

        synchronized void closeWriter() {
            writerClosed = true;
            notifyAll();
        }

        synchronized void closeReader() {
            readerClosed = true;
            notifyAll();
        }
    }

    /** One end of a connection: reads {@code in}, writes {@code out}. */
    private static final class End implements ForwardedConnection {
        private final int port;
        private final Pipe in;
        private final Pipe out;
        private final InputStream input;
        private final OutputStream output;

        End(int port, Pipe in, Pipe out) {
            this.port = port;
            this.in = in;
            this.out = out;
            this.input = new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = in.read(one, 0, 1);
                    return n < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return in.read(b, off, len);
                }

                @Override
                public void close() {
                    in.closeReader();
                }
            };
            this.output = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    out.write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() {
                    out.closeWriter();
                }
            };
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public void shutdownOutput() {
            out.closeWriter();
        }

        /** The peer reads end of stream; its writes fail, as they would to a closed socket. */
        @Override
        public void close() {
            out.closeWriter();
            in.closeReader();
        }
    }
}
