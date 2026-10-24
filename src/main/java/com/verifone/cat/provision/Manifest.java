package com.verifone.cat.provision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What should be on one target, and the single digest that summarises it: the content-hash guard (DESIGN.md 6.1).
 *
 * <p>The canonical form is the Python CAT's: entries sorted by remote path, keys sorted, compact separators,
 * {@code format} 1. The digest is over exactly those bytes, so a Java manifest and a Python one for the same
 * payload agree; {@code ManifestTest} holds both to the same digests.
 *
 * <p>Pure: bytes in, digests out. The only I/O is the {@link ContentReader} the caller supplies.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Manifest {

    /** Bumped whenever the canonical form changes: an old stamp then cannot describe the new manifest. */
    public static final int FORMAT = 1;

    /** Lower-case hex, as the digests are written. */
    private static final HexFormat HEX = HexFormat.of();

    /** One file's contribution to a target's manifest. {@code entries} keeps build order (the push order). */
    public record Entry(String artifact, String remotePath, String sha256, int mode, long size) {}

    private final TargetRole role;
    private final List<Entry> entries;
    private final String digest;

    private Manifest(TargetRole role, List<Entry> entries) {
        this.role = role;
        this.entries = List.copyOf(entries);
        this.digest = sha256Hex(canonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    /** Builds the manifest for {@code role}. Reads every file the role needs, failing before any push. */
    public static Manifest build(TargetRole role, ContentReader read, List<Artifact> artifacts) {
        List<Entry> entries = new ArrayList<>();
        Map<String, String> seen = new HashMap<>();
        for (Artifact artifact : artifacts) {
            for (ArtifactFile file : artifact.filesFor(role)) {
                byte[] content = read.read(file);
                checkLineEndings(file, content);
                String owner = seen.putIfAbsent(file.remotePath(), artifact.name());
                if (owner != null) {
                    throw new IllegalArgumentException("two artifacts push to " + file.remotePath()
                            + " (" + owner + " and " + artifact.name() + ")");
                }
                entries.add(new Entry(artifact.name(), file.remotePath(), sha256Hex(content), file.mode(),
                        content.length));
            }
        }
        return new Manifest(role, entries);
    }

    public TargetRole role() {
        return role;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** The digest the stamp records and the fast path compares. */
    public String digest() {
        return digest;
    }

    /** The stable serialisation the digest is taken over. Public so a test can hold it to the Python's. */
    public String canonicalJson() {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::remotePath, RemotePaths::compare));
        StringBuilder out = new StringBuilder();
        out.append("{\"entries\":[");
        for (int i = 0; i < sorted.size(); i++) {
            Entry e = sorted.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"artifact\":").append(Json.string(e.artifact()))
                    .append(",\"mode\":").append(Json.string(String.format("%04o", e.mode())))
                    .append(",\"remote_path\":").append(Json.string(e.remotePath()))
                    .append(",\"sha256\":").append(Json.string(e.sha256()))
                    .append(",\"size\":").append(e.size()).append('}');
        }
        out.append("],\"format\":").append(FORMAT)
                .append(",\"role\":").append(Json.string(role.wire())).append('}');
        return out.toString();
    }

    /** The remote paths this manifest covers, in build order. */
    public List<String> remotePaths() {
        return entries.stream().map(Entry::remotePath).toList();
    }

    static void checkLineEndings(ArtifactFile file, byte[] content) {
        if (file.source().endsWith(".sh") && indexOfCarriageReturn(content) >= 0) {
            throw new CrlfScriptException("payload file " + file.source() + " has CRLF line endings; on the target it"
                    + " fails with '/bin/sh^M: bad interpreter'. Rebuild it with tools/build_pos_shim.py (which writes"
                    + " LF) or convert it to LF before provisioning.");
        }
    }

    private static int indexOfCarriageReturn(byte[] content) {
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\r') {
                return i;
            }
        }
        return -1;
    }

    /** SHA-256 of {@code content}, lower-case hex. */
    public static String sha256Hex(byte[] content) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** Reads one payload file's bytes. Injected so a manifest can come from a directory, a blob or a map. */
    @FunctionalInterface
    public interface ContentReader {
        byte[] read(ArtifactFile file);
    }

    /** A {@link ContentReader} over a staged payload directory. A missing file is named, never skipped. */
    public static ContentReader payloadReader(Path payloadRoot) {
        return file -> {
            Path path = payloadRoot.resolve(file.source());
            try {
                return Files.readAllBytes(path);
            } catch (NoSuchFileException e) {
                throw new MissingPayloadException("payload file missing: " + path + " (needed for "
                        + file.remotePath() + "). The payload is not checked in: props/ are copied off the target"
                        + " over SSH and pos/*.jar are built by tools/build_pos_shim.py, so the 'could not stage' or"
                        + " 'pos-shim build failed' line above says why.", e);
            } catch (IOException e) {
                throw new MissingPayloadException("payload file unreadable: " + path + ": " + e.getMessage(), e);
            }
        };
    }

    /** A file the table requires is not in the payload. Raised before anything is pushed. */
    public static final class MissingPayloadException extends RuntimeException {
        public MissingPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A shell script in the payload has Windows line endings, so the register cannot run it. */
    public static final class CrlfScriptException extends RuntimeException {
        public CrlfScriptException(String message) {
            super(message);
        }
    }
}
