package com.verifone.cat.build;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An in-memory Artifactory on loopback, for the build package's tests.
 *
 * <p>Serves the three call shapes the real server answers: the plain storage listing
 * ({@code children[]}, no times), the file-list API ({@code ?list&deep=...&listFolders=1},
 * {@code files[]} with {@code lastModified}), a file's storage info ({@code size},
 * {@code checksums.sha256}), and the file itself, with {@code Range} support. Basic auth must
 * be {@code u:p} or the reply is 401.
 *
 * <p>The tree is nested maps: a {@link Map} is a folder, a {@code byte[]} is a file, and a
 * folder's {@value #DATE} key is its date. Build one with {@link #dir}.
 *
 * <p>Evidence about this repository's code only, not about the real server.
 */
public final class FakeArtifactory implements AutoCloseable {

    /** The key a folder's date sits under. */
    public static final String DATE = "@";

    /** The user and password this fake accepts. */
    public static final String USER = "u";
    public static final String PASSWORD = "p";

    private static final Pattern DEPTH = Pattern.compile("depth=(\\d+)");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every request's path and query, in arrival order. */
    public final List<String> requests = new CopyOnWriteArrayList<>();

    /** Every {@code Range} header a file request carried. */
    public final List<String> ranges = new CopyOnWriteArrayList<>();

    /** Range starts to answer 500, so a test can fail one part of a parallel download. */
    public final Set<Long> failRangeStarts = ConcurrentHashMap.newKeySet();

    private final Map<String, Object> tree;
    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public FakeArtifactory(Map<String, Object> tree) {
        this.tree = tree;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
    }

    /** A folder node: its date, then alternating child names and values (maps or {@code byte[]}). */
    public static Map<String, Object> dir(String date, Object... keysAndValues) {
        Map<String, Object> folder = new java.util.LinkedHashMap<>();
        folder.put(DATE, date);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            folder.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return folder;
    }

    public String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort()
                + "/artifactory";
    }

    /** A client against this fake, with the test login and the real parallel threshold. */
    public ArtifactoryClient client() {
        return client(ArtifactoryClient.PARALLEL_MIN_BYTES);
    }

    /** A client against this fake with a lowered parallel threshold. */
    public ArtifactoryClient client(long parallelMinBytes) {
        return new ArtifactoryClient(URI.create(baseUrl()), validCredentials(), Duration.ofSeconds(10),
                parallelMinBytes);
    }

    /** The test login, as an environment mapping read by {@link EnvArtifactoryCredentials}. */
    public static ArtifactoryCredentials validCredentials() {
        return new EnvArtifactoryCredentials(Map.of(
                EnvArtifactoryCredentials.USER_ENV, USER,
                EnvArtifactoryCredentials.PASSWORD_ENV, PASSWORD));
    }

    /** Requests that fetched file bytes (not storage calls), by repo path. */
    public List<String> downloads() {
        List<String> out = new ArrayList<>();
        for (String request : requests) {
            if (!request.startsWith("/api/storage/")) {
                out.add(request);
            }
        }
        return out;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().replaceFirst("^/artifactory", "");
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(path + (query == null ? "" : "?" + query));
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            reply(exchange, 401, new byte[0]);
            return;
        }
        if (path.startsWith("/api/storage/")) {
            storage(exchange, path.substring("/api/storage/".length()), query);
        } else {
            file(exchange, path);
        }
    }

    private void storage(HttpExchange exchange, String repo, String query) throws IOException {
        Object node = node(repo);
        if (node == null) {
            reply(exchange, 404, JSON.writeValueAsBytes(JSON.createObjectNode()));
            return;
        }
        ObjectNode body = JSON.createObjectNode();
        if (node instanceof byte[] bytes) {
            body.put("size", bytes.length);
            body.putObject("checksums").put("sha256", sha256Hex(bytes));
        } else if (query != null && query.startsWith("list")) {
            int depth = 1;
            Matcher match = DEPTH.matcher(query);
            if (match.find()) {
                depth = Integer.parseInt(match.group(1));
            }
            ArrayNode files = body.putArray("files");
            for (Item item : walk(asFolder(node), "", depth)) {
                ObjectNode entry = files.addObject();
                entry.put("uri", item.uri());
                entry.put("size", item.folder() ? -1 : 0);
                entry.put("lastModified", item.date());
                entry.put("folder", item.folder());
            }
        } else {
            ArrayNode children = body.putArray("children");
            for (Map.Entry<?, ?> child : asFolder(node).entrySet()) {
                String name = (String) child.getKey();
                if (DATE.equals(name)) {
                    continue;
                }
                ObjectNode entry = children.addObject();
                entry.put("uri", "/" + name);
                entry.put("folder", child.getValue() instanceof Map);
            }
        }
        reply(exchange, 200, JSON.writeValueAsBytes(body));
    }

    private void file(HttpExchange exchange, String path) throws IOException {
        Object node = node(path.replaceFirst("^/", ""));
        if (!(node instanceof byte[] bytes)) {
            reply(exchange, 404, new byte[0]);
            return;
        }
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null) {
            reply(exchange, 200, bytes);
            return;
        }
        ranges.add(range);
        String[] bounds = range.replace("bytes=", "").split("-");
        long start = Long.parseLong(bounds[0]);
        long end = Long.parseLong(bounds[1]);
        if (failRangeStarts.contains(start)) {
            reply(exchange, 500, new byte[0]);
            return;
        }
        byte[] slice = java.util.Arrays.copyOfRange(bytes, (int) start, (int) end + 1);
        reply(exchange, 206, slice);
    }

    /** The node at a repo path, or {@code null} when it is not in the tree. */
    private Object node(String path) {
        Object node = tree;
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!(node instanceof Map<?, ?> folder) || !folder.containsKey(part)) {
                return null;
            }
            node = folder.get(part);
        }
        return node;
    }

    private static Map<?, ?> asFolder(Object node) {
        return (Map<?, ?>) node;
    }

    /** One entry of a file-list reply. */
    private record Item(String uri, boolean folder, String date) {}

    private static List<Item> walk(Map<?, ?> folder, String prefix, int depth) {
        List<Item> found = new ArrayList<>();
        for (Map.Entry<?, ?> child : folder.entrySet()) {
            String name = (String) child.getKey();
            if (DATE.equals(name)) {
                continue;
            }
            String uri = prefix + "/" + name;
            boolean isFolder = child.getValue() instanceof Map;
            Object stamp = isFolder ? ((Map<?, ?>) child.getValue()).get(DATE) : null;
            String date = stamp instanceof String text ? text : "";
            found.add(new Item(uri, isFolder, date));
            if (isFolder && depth > 1) {
                found.addAll(walk((Map<?, ?>) child.getValue(), uri, depth - 1));
            }
        }
        return found;
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
