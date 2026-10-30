package com.verifone.cat.devices.vats;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BTT's card server on a free loopback port: records each CSSP request and answers it with the next status of
 * {@code statuses} (0 once they run out), or, {@code silent}, accepts and never answers (a hung BTT). Evidence about
 * this repository only.
 */
final class CardServerStub implements AutoCloseable {

    final List<byte[]> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> statuses;
    private final boolean silent;
    private final ServerSocket server;
    private final List<Socket> connections = Collections.synchronizedList(new ArrayList<>());

    CardServerStub(List<Integer> statuses, boolean silent) throws IOException {
        this(statuses, silent, 0);
    }

    /** On {@code port} (0 for a free one). */
    CardServerStub(List<Integer> statuses, boolean silent, int port) throws IOException {
        this.statuses = Collections.synchronizedList(new ArrayList<>(statuses));
        this.silent = silent;
        this.server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::accept, "cssp-stub");
        accept.setDaemon(true);
        accept.start();
    }

    /** One that answers every request "ok". */
    static CardServerStub answering() throws IOException {
        return new CardServerStub(List.of(), false);
    }

    /** One that accepts and never answers. */
    static CardServerStub silent() throws IOException {
        return new CardServerStub(List.of(), true);
    }

    int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (true) {
            Socket conn;
            try {
                conn = server.accept();
            } catch (IOException e) {
                return;
            }
            connections.add(conn);
            if (!silent) {
                Thread serve = new Thread(() -> serve(conn), "cssp-stub-conn");
                serve.setDaemon(true);
                serve.start();
            }
        }
    }

    private void serve(Socket conn) {
        try (conn) {
            DataInputStream in = new DataInputStream(conn.getInputStream());
            OutputStream out = conn.getOutputStream();
            while (true) {
                int hi = in.read();
                if (hi < 0) {
                    return;
                }
                int lo = in.read();
                int length = (hi << 8) | lo;
                byte[] body = new byte[length];
                in.readFully(body);
                byte[] request = new byte[length + 2];
                request[0] = (byte) hi;
                request[1] = (byte) lo;
                System.arraycopy(body, 0, request, 2, length);
                requests.add(request);
                byte[] reply = new byte[13];
                synchronized (statuses) {
                    reply[12] = (byte) (statuses.isEmpty() ? 0 : statuses.remove(0).intValue());
                }
                out.write(reply);
                out.flush();
            }
        } catch (IOException e) {
            // the client went
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        synchronized (connections) {
            for (Socket s : connections) {
                s.close();
            }
        }
    }
}
