package com.verifone.cat.xray;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

import com.verifone.cat.atlassian.AtlassianClient.RawReply;
import com.verifone.cat.atlassian.AtlassianCredentials.Jira;
import com.verifone.cat.atlassian.AtlassianUnavailable;

/**
 * One multipart upload to Jira: {@code POST rest/api/3/issue/<key>/attachments} with the file as the {@code file}
 * part and {@code X-Atlassian-Token: no-check} (Jira's XSRF guard refuses an upload without it, whatever the
 * credentials). The transport half of the Python's {@code XrayClient.attach_file}; {@link XrayClient#attachFile}
 * judges the reply and retries, as it does for every other Jira call.
 *
 * <p>{@code AtlassianClient} sends JSON bodies only, and this slice does not own it, so the upload has its own
 * {@link HttpClient} with the same rules: no redirects, Basic auth built here and nowhere else, an error that names
 * the call and the exception type but never a header. The file is streamed from disk, not read into memory.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the tests run against a loopback fake, never the live site.
 */
public final class AttachUpload {

    /** A coverage bundle is tens of MB; the request's bound covers the whole upload. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final URI site;
    private final Supplier<Jira> jira;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param site    the Atlassian site ({@code AtlassianClient.SITE}); a loopback URL in tests
     * @param jira    the email and token, read when an upload is made
     * @param timeout the bound on one upload, request and reply
     */
    public AttachUpload(URI site, Supplier<Jira> jira, Duration timeout) {
        String text = site.toString();
        this.site = URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
        this.jira = jira;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The upload's call name, as the Python's {@code what}. */
    static String what(String issueKey, Path file) {
        return "Jira attach " + file.getFileName() + " to " + issueKey;
    }

    /**
     * Sends the file; the reply is returned unjudged. Only a transport error (or a missing file) throws.
     *
     * @throws AtlassianUnavailable on missing credentials or a transport error
     */
    public RawReply post(String issueKey, Path file) {
        Jira credentials = jira.get();
        String boundary = "cat-java-" + UUID.randomUUID().toString().replace("-", "");
        String name = file.getFileName().toString().replace("\"", "%22");
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(head),
                    HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofByteArray(tail));
        } catch (FileNotFoundException e) {
            throw new AtlassianUnavailable(what(issueKey, file) + " failed: no file at " + file, e);
        }
        String pair = credentials.email() + ":" + credentials.token();
        HttpRequest request = HttpRequest.newBuilder(URI.create(site + "/rest/api/3/issue/" + issueKey + "/attachments"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)))
                .header("X-Atlassian-Token", "no-check")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new RawReply(response.statusCode(), response.body(),
                    response.headers().firstValue("Retry-After").orElse(null));
        } catch (IOException e) {
            throw new AtlassianUnavailable(what(issueKey, file) + " failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlassianUnavailable(what(issueKey, file) + " interrupted", e);
        }
    }
}
