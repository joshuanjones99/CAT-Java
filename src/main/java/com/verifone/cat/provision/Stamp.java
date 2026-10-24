package com.verifone.cat.provision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The stamp file as it lives on the target (DESIGN.md 6.1): what was provisioned, and its digest. Written last,
 * and only after verification, so a failed push never looks provisioned to the next run.
 *
 * <p>{@link #toBytes} is the Python CAT's {@code json.dumps(indent=2, sort_keys=True)} plus a newline, so the
 * file reads the same from either tool. {@link #parse} returns null for anything it cannot read: unknown rig
 * state must make {@code provision} push, never skip.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record Stamp(String manifestDigest, TargetRole role, String rig, String toolVersion, String provisionedAt,
        Map<String, String> files) {

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");
    private static final ObjectMapper JSON = new ObjectMapper();

    public Stamp {
        files = Collections.unmodifiableMap(new TreeMap<>(files));
    }

    /** The stamp a successful push of {@code manifest} writes. {@code now} is the stamp's clock (UTC, to the second). */
    public static Stamp forManifest(Manifest manifest, String rig, String toolVersion, Instant now) {
        Map<String, String> files = new TreeMap<>();
        for (Manifest.Entry entry : manifest.entries()) {
            files.put(entry.remotePath(), entry.sha256());
        }
        return new Stamp(manifest.digest(), manifest.role(), rig, toolVersion,
                ISO_SECONDS.format(now.atOffset(ZoneOffset.UTC).withNano(0)), files);
    }

    /** The bytes written to the target: the Python CAT's layout, sorted keys, two-space indent, trailing newline. */
    public byte[] toBytes() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"files\": ");
        if (files.isEmpty()) {
            out.append("{}");
        } else {
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, String> file : files.entrySet()) {
                out.append("    ").append(Json.string(file.getKey())).append(": ").append(Json.string(file.getValue()));
                out.append(++i < files.size() ? ",\n" : "\n");
            }
            out.append("  }");
        }
        out.append(",\n  \"format\": ").append(Manifest.FORMAT)
                .append(",\n  \"manifest_digest\": ").append(Json.string(manifestDigest))
                .append(",\n  \"provisioned_at\": ").append(Json.string(provisionedAt))
                .append(",\n  \"rig\": ").append(Json.string(rig))
                .append(",\n  \"role\": ").append(Json.string(role.wire()))
                .append(",\n  \"tool_version\": ").append(Json.string(toolVersion))
                .append("\n}\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The stamp in {@code raw}, or null when it is absent, truncated, not JSON, or from another format. */
    public static Stamp parse(Optional<byte[]> raw) {
        return raw.map(Stamp::parse).orElse(null);
    }

    /** The stamp in {@code raw}, or null when it is empty or unreadable. */
    public static Stamp parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            JsonNode document = JSON.readTree(raw);
            if (document == null || !document.isObject()) {
                return null;
            }
            JsonNode format = document.get("format");
            if (format == null || !format.isInt() || format.asInt() != Manifest.FORMAT) {
                return null;
            }
            TargetRole role = roleOf(text(document, "role"));
            String digest = text(document, "manifest_digest");
            JsonNode files = document.get("files");
            if (role == null || digest == null || files == null || !files.isObject()) {
                return null;
            }
            Map<String, String> map = new TreeMap<>();
            files.fields().forEachRemaining(f -> map.put(f.getKey(), f.getValue().asText()));
            return new Stamp(digest, role, orEmpty(text(document, "rig")), orEmpty(text(document, "tool_version")),
                    orEmpty(text(document, "provisioned_at")), map);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** True when this stamp describes {@code manifest} exactly. */
    public boolean matches(Manifest manifest) {
        return manifestDigest.equals(manifest.digest()) && role == manifest.role();
    }

    /** Which remote paths differ from this stamp. Reporting only: the run log says what changed. */
    public List<String> drift(Manifest manifest) {
        Map<String, String> wanted = new TreeMap<>();
        for (Manifest.Entry entry : manifest.entries()) {
            wanted.put(entry.remotePath(), entry.sha256());
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> want : wanted.entrySet()) {
            if (!want.getValue().equals(files.get(want.getKey()))) {
                changed.add(want.getKey());
            }
        }
        for (String path : files.keySet()) {
            if (!wanted.containsKey(path)) {
                changed.add(path);
            }
        }
        return changed.stream().sorted().toList();
    }

    private static String text(JsonNode document, String field) {
        JsonNode node = document.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static TargetRole roleOf(String wire) {
        for (TargetRole role : TargetRole.values()) {
            if (role.wire().equals(wire)) {
                return role;
            }
        }
        return null;
    }
}
