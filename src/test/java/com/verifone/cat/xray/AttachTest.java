package com.verifone.cat.xray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;

/**
 * The coverage bundle's upload to Jira (the Python's {@code TestAttach}), against a loopback Jira. It never reaches
 * the live site.
 */
class AttachTest {

    private static final AtlassianCredentials.Jira JIRA = new AtlassianCredentials.Jira("me@example.test", "token");

    @TempDir
    Path tmp;

    /** One request as the loopback Jira saw it. */
    record Seen(String method, String path, Map<String, List<String>> headers, byte[] body) {}

    /** Jira's attachment and comment endpoints; {@code failOn} answers 403, {@code unavailable} answers 503 first. */
    static final class FakeJira implements AutoCloseable {
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        final AtomicInteger unavailable = new AtomicInteger();
        String failOn = "";
        private final HttpServer server;

        FakeJira() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                byte[] body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = in.readAllBytes();
                }
                String path = exchange.getRequestURI().getPath();
                seen.add(new Seen(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
                int status;
                String reply;
                if (!failOn.isEmpty() && path.contains(failOn)) {
                    status = 403;
                    reply = "no permission";
                } else if (path.endsWith("/attachments") && unavailable.getAndDecrement() > 0) {
                    status = 503;
                    reply = "busy";
                    exchange.getResponseHeaders().add("Retry-After", "0");
                } else {
                    status = 200;
                    reply = path.endsWith("/attachments") ? "[{\"id\": \"1\"}]" : "{}";
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }

        URI base() {
            return URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                    + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static XrayClient client(FakeJira fake) {
        AtlassianClient atlassian = new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"), () -> JIRA,
                () -> new AtlassianCredentials.Xray("", ""), Duration.ofSeconds(5));
        return new XrayClient(atlassian, Duration.ZERO, null);
    }

    private static AttachUpload upload(FakeJira fake) {
        return new AttachUpload(fake.base(), () -> JIRA, Duration.ofSeconds(5));
    }

    private Path summary() throws IOException {
        Path path = tmp.resolve("summary.json");
        Files.writeString(path, "{\"run_id\": \"run1\", \"build\": \"Solutions1082\", \"dist\": \"buypass\", "
                + "\"plans\": [\"PETROCORE-1\"], \"components\": {\"fcc\": {\"name\": \"fcc\", "
                + "\"counters\": {\"LINE\": [25, 75]}, \"exec_files\": [\"fcc.exec\"]}}, \"notes\": []}");
        return path;
    }

    private Path bundle(String content) throws IOException {
        Path bundle = tmp.resolve("coverage-run1.zip");
        Files.writeString(bundle, content);
        return bundle;
    }

    @Test
    void theBundleIsUploadedWithJirasXsrfHeaderThenCommented() throws IOException {
        try (FakeJira fake = new FakeJira()) {
            Map<String, String> outcome = Attach.attach(List.of("PETROCORE-900"), bundle("PK-zip-bytes"), summary(),
                    client(fake), upload(fake), line -> { });

            assertEquals(Map.of("PETROCORE-900", "ok"), outcome);
            assertEquals(2, fake.seen.size());
            Seen upload = fake.seen.get(0);
            Seen comment = fake.seen.get(1);
            assertEquals("/rest/api/3/issue/PETROCORE-900/attachments", upload.path());
            assertEquals(List.of("no-check"), upload.headers().get("X-atlassian-token"));
            String body = new String(upload.body(), StandardCharsets.UTF_8);
            assertTrue(body.contains("filename=\"coverage-run1.zip\""), body);
            assertTrue(body.contains("PK-zip-bytes"), body);
            assertTrue(upload.headers().get("Content-type").get(0).startsWith("multipart/form-data; boundary="));
            assertEquals("/rest/api/3/issue/PETROCORE-900/comment", comment.path());
            String adf = new String(comment.body(), StandardCharsets.UTF_8);
            assertTrue(adf.contains("25.0%") && adf.contains("Solutions1082"), adf);
        }
    }

    @Test
    void aRefusedIssueIsRecordedAndTheOthersStillAttached() throws IOException {
        try (FakeJira fake = new FakeJira()) {
            fake.failOn = "PETROCORE-1/";
            Map<String, String> outcome = Attach.attach(List.of("PETROCORE-1", "PETROCORE-2"), bundle("PK"),
                    summary(), client(fake), upload(fake), line -> { });
            assertEquals("ok", outcome.get("PETROCORE-2"));
            assertTrue(outcome.get("PETROCORE-1").contains("HTTP 403"), outcome.toString());
            assertTrue(outcome.get("PETROCORE-1").startsWith("Jira attach coverage-run1.zip to PETROCORE-1"),
                    outcome.toString());
        }
    }

    @Test
    void aBusyJiraIsRetriedAsEveryOtherCallIs() throws IOException {
        try (FakeJira fake = new FakeJira()) {
            fake.unavailable.set(2);
            Map<String, String> outcome = Attach.attach(List.of("PETROCORE-3"), bundle("PK"), summary(),
                    client(fake), upload(fake), line -> { });
            assertEquals(Map.of("PETROCORE-3", "ok"), outcome);
            assertEquals(4, fake.seen.size(), "two 503s, the upload, the comment");
        }
    }

    @Test
    void mainWritesTheOutcomeBesideTheRunAndExitsZero() throws IOException {
        Attach attach = new Attach((issues, bundle, summary) -> Map.of("PETROCORE-1", "ok"), line -> { });
        int code = attach.main(List.of("--run-dir", tmp.toString(), "--file", bundle("PK").toString(), "--summary",
                summary().toString(), "--issue", "PETROCORE-1"), System.err);
        assertEquals(0, code);
        JsonNode status = Attach.readStatus(tmp);
        assertEquals("ok", status.path("issues").path("PETROCORE-1").asText());
        assertTrue(status.path("started_at").asText().endsWith("+00:00"), status.toString());
    }

    @Test
    void aMissingBundleIsEachIssuesOutcomeAndJiraIsNotAsked() throws IOException {
        Attach attach = new Attach((issues, bundle, summary) -> {
            throw new AssertionError("nothing to upload");
        }, line -> { });
        Path missing = tmp.resolve("absent.zip");
        assertEquals(0, attach.main(List.of("--run-dir", tmp.toString(), "--file", missing.toString(), "--summary",
                "s.json", "--issue", "PETROCORE-1", "--issue", "PETROCORE-2"), System.err));
        JsonNode issues = Attach.readStatus(tmp).path("issues");
        assertEquals("no file at " + missing, issues.path("PETROCORE-2").asText());
    }

    @Test
    void aMissingRequiredFlagIsAUsageError() {
        assertEquals(2, new Attach((i, b, s) -> Map.of(), line -> { }).main(List.of("--run-dir", tmp.toString()),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        assertEquals(null, Attach.readStatus(tmp));
    }

    @Test
    void anUnreadableSummaryStillGetsACommentSayingSo() {
        List<Adf.Block> blocks = Attach.commentBlocks(tmp.resolve("absent.json"));
        assertEquals(1, blocks.size());
        assertTrue(Adf.toMarkdown(blocks).contains("its summary could not be read"));
    }

    @Test
    void theCommentLeadsWithTheRunAndCarriesTheTableAndNotes() throws IOException {
        Path path = tmp.resolve("s.json");
        Files.writeString(path, "{\"run_id\": \"r9\", \"build\": \"\", \"dist\": \"buypass\", \"components\": {}, "
                + "\"notes\": [\"one\", \"two\"], \"baseline_run\": \"r8\", \"baseline_build\": \"Solutions1\"}");
        String markdown = Adf.toMarkdown(Attach.commentBlocks(path));
        assertTrue(markdown.contains("JaCoCo coverage for CAT run r9 on an unknown build (buypass) is attached. "
                + "Line Δ is against run r8 (Solutions1)."), markdown);
        assertTrue(markdown.contains("Notes: one; two"), markdown);
    }
}
