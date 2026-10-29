package com.verifone.cat.provision.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.jcraft.jsch.ChannelForwardedTCPIP;
import com.jcraft.jsch.ForwardedTCPIPDaemon;

/**
 * JSch's hook for a reverse forward's connection ({@code Session.setPortForwardingR} with a daemon class): JSch
 * creates one per accepted connection by reflection, hands it the channel, then runs it on a thread of its own. It
 * passes the connection to the {@link ForwardHandler} {@link JschTransport#requestReverseForward} registered.
 *
 * <p><b>Half-close.</b> JSch gives a daemon a pipe ({@code out}) that its channel thread copies to the box; when that
 * pipe ends, the channel thread sends EOF and then closes the channel at once, so a half-close from the controller's
 * side would end the box's half too. Bytes to the box are written to {@link ChannelForwardedTCPIP#getOutputStream()}
 * instead, whose {@code close()} sends only EOF (read from JSch 0.2.20's bytecode, 2026-10-29): the Python's
 * {@code Channel.shutdown_write}. The pipe is closed only by {@link Connection#close}, which ends the channel.
 *
 * <p>Must stay a public class with a public no-argument constructor: JSch instantiates it by name.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no SSH server has forwarded a connection to it.
 */
public final class JschForwardDaemon implements ForwardedTCPIPDaemon {

    private ChannelForwardedTCPIP channel;
    private InputStream in;
    private OutputStream pipe;
    private ForwardHandler handler;
    private int port;

    public JschForwardDaemon() {
        // JSch's reflective construction
    }

    /** The arguments {@link JschTransport#requestReverseForward} registers: the handler and the forward's port. */
    static Object[] arguments(ForwardHandler handler, int port) {
        return new Object[] {handler, port};
    }

    @Override
    public void setChannel(ChannelForwardedTCPIP channel, InputStream in, OutputStream out) {
        this.channel = channel;
        this.in = in;
        this.pipe = out;
    }

    @Override
    public void setArg(Object[] arg) {
        if (arg == null || arg.length != 2 || !(arg[0] instanceof ForwardHandler) || !(arg[1] instanceof Integer)) {
            throw new IllegalArgumentException("JschForwardDaemon needs {ForwardHandler, Integer port}");
        }
        this.handler = (ForwardHandler) arg[0];
        this.port = (Integer) arg[1];
    }

    @Override
    public void run() {
        Connection connection;
        try {
            connection = new Connection(port, in, pipe, channel.getOutputStream(), channel::disconnect);
        } catch (IOException e) {
            channel.disconnect();
            return;
        }
        try {
            handler.accept(connection);
        } catch (RuntimeException e) {
            connection.close();
        }
    }

    /**
     * A JSch forwarded channel as a {@link ForwardedConnection}. Package-visible with plain streams, so its close and
     * half-close rules are tested without a channel.
     */
    static final class Connection implements ForwardedConnection {
        private final int port;
        private final InputStream in;
        private final OutputStream pipe;
        private final OutputStream out;
        private final Runnable disconnect;

        /**
         * @param in         bytes from the box
         * @param pipe       JSch's daemon pipe: closed only to end the channel
         * @param out        the channel's own output stream; its close is the half-close
         * @param disconnect ends the channel
         */
        Connection(int port, InputStream in, OutputStream pipe, OutputStream out, Runnable disconnect) {
            this.port = port;
            this.in = in;
            this.pipe = pipe;
            this.out = out;
            this.disconnect = disconnect;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public InputStream input() {
            return in;
        }

        @Override
        public OutputStream output() {
            return out;
        }

        @Override
        public void shutdownOutput() throws IOException {
            out.close();
        }

        @Override
        public void close() {
            try {
                pipe.close();
            } catch (IOException ignored) {
                // already gone
            }
            try {
                disconnect.run();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }
}
