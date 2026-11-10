// Canonical source — BROWSER_STRATEGY.md §7 / §5.6. Staged into
// `payload/pos/chromium/` by hand (gitignored build output — see
// `provision/artifacts.py`'s `DEFAULT_PAYLOAD_ROOT` docstring), the same
// "no build tool yet" precedent as `chromium-wrapper.sh` in this same
// directory.
//
// Formalizes the hand-run scratch relay described in BROWSER_STRATEGY.md
// §5.6/§7: this Chrome build's DevTools server binds 127.0.0.1 only (no
// `--remote-debugging-address` support — confirmed absent from the binary's
// own symbol/rodata data, not a flag this wrapper can fix) and this
// register's SSH port forwarding is administratively disabled. Until either
// changes, a plain TCP relay listening on all interfaces and forwarding to
// the loopback CDP port is the only supported way an off-box controller can
// reach it.
//
// Launched via single-file source execution (`java CdpRelay.java <relay_port>
// <cdp_port>`) because this register has no `javac` — confirmed absent,
// same as `socat`/`nc`/`python` (BROWSER_STRATEGY.md §5.6). Started/stopped by
// `provision.browser_override.enable()`/`disable()`, pushed by the
// `cdp_relay` artifact (`provision/artifacts.py`).
//
// NOT protocol-blind anymore — see §7's "chrome://inspect lists targets but
// Inspect shows nothing" finding. Chromium's DevTools HTTP server rejects a
// WebSocket upgrade whose `Host` header isn't `localhost`/a loopback address
// (an anti-DNS-rebinding hardening; Vewd's older CDP server never enforced
// this, which is why a bare byte-for-byte relay used to be enough). A plain
// TCP relay forwards the client's real `Host` header (e.g.
// `<register-ip>:19220`) unchanged, so Chromium 403s the upgrade — the JSON
// listing endpoints aren't guarded the same way, which is why targets show up
// but "Inspect" never attaches to anything. This relay now parses just enough
// HTTP to rewrite that one header outbound, and to rewrite the
// `127.0.0.1:<cdpPort>`/`localhost:<cdpPort>` references Chromium bakes into
// `/json/list`'s response body (`webSocketDebuggerUrl`, `devtoolsFrontendUrl`)
// back to whatever host:port the client actually used, so the DevTools
// front-end's next connection comes back through this relay instead of
// trying to dial its own loopback. Once a response is `101 Switching
// Protocols`, this drops straight back to raw byte pumping for the rest of
// the connection — WebSocket frames are binary and must never be touched.
//
// UNVERIFIED AGAINST HARDWARE as of this rewrite (per CLAUDE.md's evidence
// discipline) — sanity-checked locally against a fake HTTP CDP stand-in
// (Host-rewrite outbound, body-rewrite inbound, oversized-body and
// no-Content-Length edge cases), never yet against the real register or a
// real Chromium DevTools 403. Confirm live before trusting this over the old
// byte-for-byte version: `curl http://<register>:19220/json/list` should
// still list targets, and `chrome://inspect` -> "Inspect" on the POS page
// target should now actually attach and render, not just list.
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CdpRelay {
    //: Header block (request/status line + headers, ending at the blank
    //: line) larger than this is not a shape any CDP HTTP exchange this
    //: relay needs to handle takes — bail out to a hard failure rather than
    //: buffering an unbounded amount of memory for malformed/hostile input.
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    //: How long a request or response's headers may take to arrive, and the
    //: connect to Chromium. A client that opens a socket and sends nothing (or
    //: a Chromium mid-restart) must not hold a thread for ever: on 2026-10-14
    //: the rig owner found this relay "rarely visible" from edge://inspect,
    //: and a relay whose threads and sockets only ever accumulate is one that
    //: eventually cannot accept. A WebSocket, once upgraded, has no timeout —
    //: a DevTools session is silent for as long as nobody touches it — but gets
    //: TCP keep-alive, so a client that vanished is noticed.
    private static final int HEADER_TIMEOUT_MS = 30_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: java CdpRelay.java <relay_port> <cdp_port>");
            System.exit(2);
        }
        int relayPort = Integer.parseInt(args[0]);
        int cdpPort = Integer.parseInt(args[1]);

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(relayPort));
            System.out.println(
                "cdp-relay listening on 0.0.0.0:" + relayPort + " -> 127.0.0.1:" + cdpPort);
            // Never leaves: one failed accept (a client reset mid-handshake, a
            // momentary file-descriptor limit) used to end `main`, and with it
            // the relay, silently. `cdp-relay-boot.sh` restarts the JVM too, if
            // it ever exits anyway.
            while (true) {
                try {
                    Socket downstream = server.accept();
                    Thread worker = new Thread(() -> handleConnection(downstream, cdpPort));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException | RuntimeException e) {
                    System.err.println("cdp-relay accept error (carrying on): " + e);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static void handleConnection(Socket downstream, int cdpPort) {
        try (Socket upstream = new Socket()) {
            downstream.setSoTimeout(HEADER_TIMEOUT_MS);
            upstream.connect(new InetSocketAddress("127.0.0.1", cdpPort), CONNECT_TIMEOUT_MS);
            upstream.setSoTimeout(HEADER_TIMEOUT_MS);
            downstream.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            InputStream dIn = downstream.getInputStream();
            OutputStream dOut = downstream.getOutputStream();
            InputStream uIn = upstream.getInputStream();
            OutputStream uOut = upstream.getOutputStream();

            byte[] reqBlock = readHeaderBlock(dIn);
            if (reqBlock == null) {
                // Nothing sent at all (a bare connect-and-close health probe) —
                // nothing to relay, nothing to log as an error.
                return;
            }
            HttpMessage request = HttpMessage.parse(reqBlock);
            String originalHost = request.header("Host");
            if (originalHost != null) {
                // The one rewrite this relay exists for: Chromium's DevTools
                // server 403s a WebSocket upgrade (and, on some builds, the
                // JSON endpoints too) unless Host says localhost/loopback.
                request.setHeader("Host", "localhost:" + cdpPort);
            }
            if (request.header("Upgrade") == null) {
                // Answered once and closed (below), so Chromium need not keep it.
                request.setHeader("Connection", "close");
            }
            uOut.write(request.toBytes());
            uOut.flush();

            byte[] respBlock = readHeaderBlock(uIn);
            if (respBlock == null) {
                // Upstream closed without responding — nothing more to relay.
                return;
            }
            HttpMessage response = HttpMessage.parse(respBlock);

            if (response.statusCode() == 101) {
                // WebSocket upgrade accepted — forward the response headers
                // as-is, then get out of the way completely. Every byte from
                // here on is a binary WS frame; parsing it as HTTP would
                // corrupt it.
                dOut.write(response.toBytes());
                dOut.flush();
                downstream.setSoTimeout(0);
                upstream.setSoTimeout(0);
                downstream.setKeepAlive(true);
                upstream.setKeepAlive(true);
                rawRelay(downstream, upstream, dIn, dOut, uIn, uOut);
                return;
            }

            int contentLength = response.contentLength();
            byte[] body = contentLength > 0 ? readExactly(uIn, contentLength) : new byte[0];
            if (originalHost != null && body.length > 0) {
                String rewritten =
                    new String(body, StandardCharsets.UTF_8)
                        .replace("127.0.0.1:" + cdpPort, originalHost)
                        .replace("localhost:" + cdpPort, originalHost);
                body = rewritten.getBytes(StandardCharsets.UTF_8);
                response.setHeader("Content-Length", String.valueOf(body.length));
            }
            // One request per connection. A keep-alive client used to have its
            // later requests relayed raw — unrewritten — on a pair of threads
            // that lived as long as the client kept the socket idle, which a
            // browser's discovery poll can do indefinitely. Closing says "ask
            // again on a new connection", which every HTTP client does, and
            // every answer then gets the rewrite.
            response.setHeader("Connection", "close");
            dOut.write(response.toBytes());
            dOut.write(body);
            dOut.flush();
        } catch (IOException | RuntimeException | InterruptedException e) {
            System.err.println("cdp-relay connection error: " + e);
        } finally {
            closeQuietly(downstream);
        }
    }

    //: Reads raw bytes up to and including the first `\r\n\r\n`. Byte-at-a-time
    //: and unbuffered so it never reads past the header boundary — anything
    //: after belongs to the body/frames and must reach `rawRelay`/`readExactly`
    //: untouched. Returns `null` only if the stream hit EOF with zero bytes
    //: read (an empty probe connection), not on a malformed-but-nonempty block.
    private static byte[] readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tail = new byte[4];
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            tail[0] = tail[1];
            tail[1] = tail[2];
            tail[2] = tail[3];
            tail[3] = (byte) b;
            if (tail[0] == '\r' && tail[1] == '\n' && tail[2] == '\r' && tail[3] == '\n') {
                return buf.toByteArray();
            }
            if (buf.size() > MAX_HEADER_BYTES) {
                throw new IOException("header block exceeded " + MAX_HEADER_BYTES + " bytes");
            }
        }
        return buf.size() == 0 ? null : buf.toByteArray();
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(data, off, n - off);
            if (r == -1) {
                throw new IOException("EOF while reading body (" + off + "/" + n + " bytes)");
            }
            off += r;
        }
        return data;
    }

    private static void rawRelay(
        Socket downstream, Socket upstream, InputStream dIn, OutputStream dOut,
        InputStream uIn, OutputStream uOut
    ) throws InterruptedException {
        Thread toUpstream = pump(dIn, uOut, upstream);
        Thread toDownstream = pump(uIn, dOut, downstream);
        toUpstream.join();
        toDownstream.join();
    }

    private static Thread pump(InputStream in, OutputStream out, Socket toSocket) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                // Normal on either side closing first — nothing to report.
            } finally {
                try {
                    toSocket.shutdownOutput();
                } catch (IOException e) {
                    // Already closed; nothing to do.
                }
            }
        });
        t.start();
        return t;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing to do.
        }
    }

    //: Just enough HTTP to read/rewrite a request or response's first line and
    //: headers as text. Deliberately not a general HTTP parser — no chunked
    //: transfer-encoding, no multi-value headers, no folding. CDP's own HTTP
    //: server only ever sends `Content-Length`-delimited bodies (confirmed by
    //: `_probe_cdp`'s own `/json/version`/`/json/list` probes in
    //: `provision/browser_override.py`), so that's the only body shape handled.
    private static final class HttpMessage {
        String firstLine;
        List<String> headers = new ArrayList<>();

        static HttpMessage parse(byte[] block) {
            String text = new String(block, StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n");
            HttpMessage msg = new HttpMessage();
            msg.firstLine = lines.length > 0 ? lines[0] : "";
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isEmpty()) {
                    msg.headers.add(lines[i]);
                }
            }
            return msg;
        }

        String header(String name) {
            for (String h : headers) {
                int idx = h.indexOf(':');
                if (idx > 0 && h.substring(0, idx).trim().equalsIgnoreCase(name)) {
                    return h.substring(idx + 1).trim();
                }
            }
            return null;
        }

        void setHeader(String name, String value) {
            for (int i = 0; i < headers.size(); i++) {
                int idx = headers.get(i).indexOf(':');
                if (idx > 0 && headers.get(i).substring(0, idx).trim().equalsIgnoreCase(name)) {
                    headers.set(i, name + ": " + value);
                    return;
                }
            }
            headers.add(name + ": " + value);
        }

        // Status line looks like "HTTP/1.1 101 Switching Protocols"; request
        // lines (no numeric second token) fall through to -1, which is fine —
        // only responses are ever checked for a status code.
        int statusCode() {
            String[] parts = firstLine.split(" ", 3);
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            return -1;
        }

        int contentLength() {
            String v = header("Content-Length");
            if (v == null) {
                return 0;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        byte[] toBytes() {
            StringBuilder sb = new StringBuilder();
            sb.append(firstLine).append("\r\n");
            for (String h : headers) {
                sb.append(h).append("\r\n");
            }
            sb.append("\r\n");
            return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }
}
