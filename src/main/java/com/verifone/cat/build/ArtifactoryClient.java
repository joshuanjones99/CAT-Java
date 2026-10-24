package com.verifone.cat.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for Artifactory's storage API: folder listings, a file's size and checksum,
 * and the download itself. The Java counterpart of the Python CAT's
 * {@code petrotest.build.artifactory.ArtifactoryClient}.
 *
 * <p>Calls: {@code GET /api/storage/<repoPath>} for a plain folder listing ({@code children[]},
 * no times) and for a file's {@code size} and {@code checksums.sha256}; the same with
 * {@code ?list&deep=...&listFolders=1} for a listing with times ({@code files[]}); and a plain
 * authenticated {@code GET} on a file's URL. This is the shape {@code Monarch.ps1} uses.
 *
 * <p>Credentials come from an {@link ArtifactoryCredentials} that is asked once, in the
 * constructor, so a missing login fails before any request. Every request carries Basic auth.
 * Redirects are not followed, so the login is never sent to another host.
 *
 * <p>A download is written to {@code <name>.part} and moved into place only once it is whole
 * and matches the size and sha256 given. A download that fails part-way leaves no file that a
 * later size check could take for a finished one, and a later run fetches it again. A file of
 * {@value #PARALLEL_MIN_BYTES} bytes or more with a known size is fetched as byte ranges, several
 * at once: one stream from the controller ran at about 3.4 MB/s, six ranges at 10.8 MB/s
 * (2026-09-21).
 *
 * <p>UNVERIFIED AGAINST A REAL ARTIFACTORY SERVER: the tests run against
 * {@code FakeArtifactory}, a loopback stand-in, and the real server is not called.
 */
public final class ArtifactoryClient {

    /** The Artifactory root the Python CAT reads. Not a credential. */
    public static final String DEFAULT_BASE_URL = "https://artifactory.verifone.com/artifactory";

    /** Below this a file is streamed in one request: the jars are under 1 MB. */
    public static final long PARALLEL_MIN_BYTES = 64L * 1024 * 1024;

    /** How many byte ranges a large file is fetched as, at once. */
    public static final int DEFAULT_WORKERS = 6;

    private static final int BUFFER = 64 * 1024;
    private static final int HASH_BLOCK = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A file's size, and its sha256 when Artifactory reports one (else {@code null}). */
    public record FileInfo(long size, String sha256) {}

    private final URI base;
    private final String authorization;
    private final Duration timeout;
    private final long parallelMinBytes;
    private final HttpClient http;

    /**
     * @param base        the Artifactory root, e.g. {@link #DEFAULT_BASE_URL}; a loopback URL in tests
     * @param credentials where the login comes from; asked once, here
     * @param timeout     per-request timeout; no call here is unbounded
     * @throws ArtifactoryAuthError when no login is available
     */
    public ArtifactoryClient(URI base, ArtifactoryCredentials credentials, Duration timeout) {
        this(base, credentials, timeout, PARALLEL_MIN_BYTES);
    }

    /** Package-private so a test can lower the threshold and keep its payload small. */
    ArtifactoryClient(URI base, ArtifactoryCredentials credentials, Duration timeout, long parallelMinBytes) {
        ArtifactoryCredentials.Login login = credentials.login();
        this.base = stripTrailingSlash(base);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (login.user() + ":" + login.password()).getBytes(StandardCharsets.UTF_8));
        this.timeout = timeout;
        this.parallelMinBytes = parallelMinBytes;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * The children of a folder, in the order Artifactory returned them (no sort applied here).
     * One request. {@link ArtifactoryEntry#lastModified()} is empty: the plain listing has no times.
     *
     * @throws ArtifactoryException on a non-2xx status or no response
     */
    public List<ArtifactoryEntry> listFolder(String repoPath) {
        JsonNode body = getJson(storageUrl(repoPath, ""), repoPath);
        List<ArtifactoryEntry> entries = new ArrayList<>();
        for (JsonNode child : body.path("children")) {
            entries.add(entry(child));
        }
        return entries;
    }

    /**
     * The folder's descendants {@code depth} levels deep, each with its {@code lastModified}.
     * One request. {@code depth} of 1 or less is the immediate children. A folder's time is when it
     * was created, and does not move as children are added. Entry URIs are relative to
     * {@code repoPath} and may have more than one segment ({@code "/60/simulators"}).
     *
     * @throws ArtifactoryException on a non-2xx status (404 for a missing folder) or no response
     */
    public List<ArtifactoryEntry> listTree(String repoPath, int depth) {
        String deep = depth <= 1 ? "deep=0" : "deep=1&depth=" + depth;
        JsonNode body = getJson(storageUrl(repoPath, "?list&" + deep + "&listFolders=1"), repoPath);
        List<ArtifactoryEntry> entries = new ArrayList<>();
        for (JsonNode item : body.path("files")) {
            entries.add(entry(item));
        }
        return entries;
    }

    /**
     * A file's size and sha256, from {@code GET /api/storage/<repoPath>}. One request.
     *
     * @throws ArtifactoryException on a non-2xx status or no response
     * @throws BuildException when the reply carries no size
     */
    public FileInfo fileInfo(String repoPath) {
        JsonNode body = getJson(storageUrl(repoPath, ""), repoPath);
        if (!body.hasNonNull("size")) {
            throw new BuildException(repoPath + ": Artifactory's storage reply has no size");
        }
        String sha = body.path("checksums").path("sha256").asText("");
        return new FileInfo(body.get("size").asLong(), sha.isEmpty() ? null : sha);
    }

    /** The absolute download URL for a repo path, as {@link #downloadFile} takes it. */
    public String fileUrl(String repoPath) {
        return base + "/" + encodePath(trimSlashes(repoPath));
    }

    /** {@link #downloadFile(String, Path, Long, String, int)} with no size, checksum or ranges. */
    public Path downloadFile(String url, Path dest) {
        return downloadFile(url, dest, null, null, DEFAULT_WORKERS);
    }

    /**
     * Fetches {@code url} to {@code dest}, creating its parent folders.
     *
     * <p>When {@code size} is known and at least {@link #PARALLEL_MIN_BYTES}, the file is
     * fetched as {@code workers} byte ranges at once; otherwise it is streamed whole. The bytes go
     * to {@code <dest>.part} and move into place only after the size and, when given, the sha256
     * match. The part file is removed on any failure.
     *
     * @param size    the file's size from {@link #fileInfo}, or {@code null} when not asked
     * @param sha256  the file's sha256 from {@link #fileInfo}, or {@code null}
     * @param workers how many ranges to fetch at once
     * @return {@code dest}
     * @throws ArtifactoryException on a non-2xx status or no response
     * @throws BuildException when the bytes do not match {@code size} or {@code sha256}, or
     *     the file cannot be written
     */
    public Path downloadFile(String url, Path dest, Long size, String sha256, int workers) {
        Path absolute = dest.toAbsolutePath();
        Path part = absolute.resolveSibling(absolute.getFileName() + ".part");
        try {
            Files.createDirectories(absolute.getParent());
            if (size != null && size >= parallelMinBytes && workers > 1) {
                downloadRanges(url, part, size, workers);
            } else {
                streamWhole(url, part);
            }
            long got = Files.size(part);
            if (size != null && got != size) {
                throw new BuildException(dest.getFileName() + ": got " + got + " bytes, expected " + size);
            }
            if (sha256 != null) {
                String digest = sha256Of(part);
                if (!digest.equalsIgnoreCase(sha256)) {
                    throw new BuildException(dest.getFileName() + ": sha256 " + digest + ", expected " + sha256);
                }
            }
            Files.move(part, absolute, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException e) {
            throw new BuildException("cannot write " + dest + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(part);
        }
    }

    private void streamWhole(String url, Path part) throws IOException {
        HttpRequest.Builder request = get(url);
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream(), url);
        try (InputStream in = response.body()) {
            requireOk(response.statusCode(), url);
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void downloadRanges(String url, Path part, long size, int workers) throws IOException {
        long slice = (size + workers - 1) / workers;
        List<long[]> bounds = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            long start = i * slice;
            long end = Math.min(size, (i + 1) * slice) - 1;
            if (start <= end) {
                bounds.add(new long[] {start, end});
            }
        }
        // Pre-size with one byte at the end; each range then writes at its own offset.
        try (FileChannel channel = FileChannel.open(part,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(new byte[1]), size - 1);
        }
        ExecutorService pool = Executors.newFixedThreadPool(bounds.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (long[] bound : bounds) {
                futures.add(pool.submit(() -> {
                    fetchRange(url, part, bound[0], bound[1]);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new BuildException("range download failed", cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BuildException("download interrupted", e);
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void fetchRange(String url, Path part, long start, long end) throws IOException {
        HttpRequest.Builder request = get(url).header("Range", "bytes=" + start + "-" + end);
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream(), url);
        try (InputStream in = response.body()) {
            if (response.statusCode() != 206) {
                throw new ArtifactoryException("expected 206 for a Range request, got "
                        + response.statusCode(), response.statusCode(), null);
            }
            long pos = start;
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                    while (chunk.hasRemaining()) {
                        pos += channel.write(chunk, pos);
                    }
                }
            }
            // The file was pre-sized, so a short range would leave a hole its size hides.
            long written = pos - start;
            if (written != end - start + 1) {
                throw new IOException("range " + start + "-" + end + ": got " + written + " bytes");
            }
        }
    }

    private JsonNode getJson(String url, String what) {
        HttpResponse<byte[]> response = send(get(url).header("Accept", "application/json"),
                HttpResponse.BodyHandlers.ofByteArray(), what);
        requireOk(response.statusCode(), what);
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new ArtifactoryException("Artifactory answered " + what + " with something that is not JSON",
                    response.statusCode(), e);
        }
    }

    private HttpRequest.Builder get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", authorization)
                .GET();
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler, String what) {
        try {
            return http.send(request.build(), handler);
        } catch (IOException e) {
            throw new ArtifactoryException(what + " did not complete: " + e.getClass().getSimpleName(),
                    ArtifactoryException.NO_RESPONSE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArtifactoryException(what + " was interrupted", ArtifactoryException.NO_RESPONSE, e);
        }
    }

    private static void requireOk(int status, String what) {
        if (status < 200 || status >= 300) {
            throw new ArtifactoryException(what + " answered HTTP " + status, status, null);
        }
    }

    private String storageUrl(String repoPath, String query) {
        return base + "/api/storage/" + encodePath(trimSlashes(repoPath)) + query;
    }

    private static ArtifactoryEntry entry(JsonNode node) {
        return new ArtifactoryEntry(node.path("uri").asText(""), node.path("folder").asBoolean(false),
                node.path("lastModified").asText(""));
    }

    /** Each segment percent-encoded; a space is {@code %20}, not {@code +}, in a path. */
    static String encodePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    static String trimSlashes(String path) {
        return path == null ? "" : path.strip().replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static URI stripTrailingSlash(URI uri) {
        return URI.create(uri.toString().replaceAll("/+$", ""));
    }

    /** The sha256 of a file, lowercase hex. */
    static String sha256Of(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; this cannot happen.
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] block = new byte[HASH_BLOCK];
            int read;
            while ((read = in.read(block)) != -1) {
                digest.update(block, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteQuietly(Path part) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException ignored) {
            // Best effort: a part file left behind is never taken for a finished download.
        }
    }
}
