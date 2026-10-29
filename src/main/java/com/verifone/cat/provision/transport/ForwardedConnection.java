package com.verifone.cat.provision.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * One TCP connection carried by a reverse port forward, or the socket it is joined to: two byte streams, a half-close
 * and a close. The cloud tunnel's relay ({@code cloudtunnel.CloudTunnelRelay}) joins two of these.
 *
 * <p>A half-close ({@link #shutdownOutput}) tells the peer no more bytes are coming while bytes still arrive the other
 * way, as {@code socket.shutdown(SHUT_WR)} and paramiko's {@code Channel.shutdown_write} do in the Python.
 */
public interface ForwardedConnection {

    /** The forward's port on the box this connection arrived on; for a plain socket, its remote port. */
    int port();

    /** Bytes from the peer; end of stream once the peer half-closed or closed. */
    InputStream input() throws IOException;

    /** Bytes to the peer. Each write is flushed by the caller. */
    OutputStream output() throws IOException;

    /** Ends this side's sending (the peer reads end of stream); reading continues. */
    void shutdownOutput() throws IOException;

    /** Ends both directions and frees the connection. Quiet when it is already gone. */
    void close();

    /** A socket as a connection: the relay's upstream side, and the loopback tests' channel side. */
    static ForwardedConnection of(Socket socket) {
        return new ForwardedConnection() {
            @Override
            public int port() {
                return socket.getPort();
            }

            @Override
            public InputStream input() throws IOException {
                return socket.getInputStream();
            }

            @Override
            public OutputStream output() throws IOException {
                return socket.getOutputStream();
            }

            @Override
            public void shutdownOutput() throws IOException {
                socket.shutdownOutput();
            }

            @Override
            public void close() {
                try {
                    socket.shutdownInput();
                } catch (IOException ignored) {
                    // already gone
                }
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {
                    // already gone
                }
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // already gone
                }
            }

            @Override
            public String toString() {
                return "socket " + socket;
            }
        };
    }
}
