package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.provision.transport.InMemoryTransport;

/** The render and offline-stage halves of {@code provision} (HANDOFF 6 item 1). UNVERIFIED AGAINST HARDWARE. */
class RenderStageTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String FILEBEAT_TEMPLATE = "output.logstash:\n  hosts: [\"${logging_host}:5142\"]\n"
            + "filebeat.config.modules:\n  path: ${path.config}/modules.d/*.yml\n";

    @TempDir
    Path dir;

    private Path templates() throws IOException {
        Path root = dir.resolve("templates");
        for (String name : List.of("filebeat/commander/filebeat.yml", "filebeat/pos/filebeat.yml")) {
            Files.createDirectories(root.resolve(name).getParent());
            Files.writeString(root.resolve(name), FILEBEAT_TEMPLATE);
        }
        return root;
    }

    private static Set<String> artifactSources() {
        Set<String> sources = new HashSet<>();
        for (Artifact artifact : Artifacts.ARTIFACTS) {
            for (TargetRole role : TargetRole.values()) {
                artifact.filesFor(role).forEach(f -> sources.add(f.source()));
            }
        }
        return sources;
    }

    @Test
    void renderableAndNotRenderablePartitionEveryArtifactFile() {
        Set<String> renderable = new HashSet<>();
        Render.RENDERABLE.forEach(r -> renderable.add(r.source()));
        Set<String> notRenderable = new HashSet<>();
        Render.NOT_RENDERABLE.forEach(r -> notRenderable.add(r.source()));

        assertTrue(java.util.Collections.disjoint(renderable, notRenderable));
        Set<String> union = new HashSet<>(renderable);
        union.addAll(notRenderable);
        assertEquals(artifactSources(), union);
    }

    @Test
    void aRenderWithoutALoggingHostLeavesThePlaceholderAndSaysSo() throws IOException {
        Path payload = dir.resolve("payload");

        Render.Report report = Render.renderPayload("bench", payload, templates(), Map.of());

        assertFalse(report.ok());
        assertEquals(List.of("logging_host"), report.rendered().get(0).unresolved());
        assertTrue(Files.readString(payload.resolve("filebeat/commander/filebeat.yml")).contains("${logging_host}"));
        assertTrue(report.render().contains("UNRESOLVED"), report.render());
    }

    @Test
    void aRenderWithALoggingHostResolvesCleanlyAndKeepsFilebeatsOwnPlaceholder() throws IOException {
        Path payload = dir.resolve("payload");

        Render.Report report = Render.renderPayload("bench", payload, templates(),
                Map.of("logging_host", "elk.bench.invalid"));

        assertTrue(report.ok(), report.render());
        String text = Files.readString(payload.resolve("filebeat/pos/filebeat.yml"));
        assertTrue(text.contains("hosts: [\"elk.bench.invalid:5142\"]"), text);
        assertTrue(text.contains("${path.config}"), text);
    }

    @Test
    void aNotRenderableFileIsReportedPresentOrMissingByName() throws IOException {
        Path payload = dir.resolve("payload");
        Files.createDirectories(payload.resolve("jacoco"));
        Files.write(payload.resolve("jacoco/jacocoagent.jar"), new byte[] {1, 2, 3});

        Render.Report report = Render.renderPayload("bench", payload, templates(), Map.of());

        assertTrue(report.notRenderable().stream().anyMatch(s -> s.source().equals("jacoco/jacocoagent.jar")
                && s.present()));
        assertTrue(report.notRenderable().stream().anyMatch(s -> s.source().equals("props/private_system.prop")
                && !s.present()));
    }

    @Test
    void theRegisterStagesItsOwnPropsAndTheBridgeFilesFromTheRepository() throws IOException {
        Path bridge = bridgeDir();
        Path payload = dir.resolve("payload");
        RecordingTransport register = new RecordingTransport("topaz101.invalid");
        register.inner.withFile("/home/topaz/config/private_system.prop", "live=1\n".getBytes(StandardCharsets.UTF_8));
        TargetSpec spec = new TargetSpec(TargetRole.REGISTER, "topaz101", "topaz101.invalid", "root", 22, 8801, null);

        List<String> staged = Stage.stagePayload("bench", spec, register, payload, TIMEOUT,
                new Stage.Sources(templates(), bridge, dir.resolve("absent.jar")));

        assertTrue(staged.contains("props/private_system.prop"), staged.toString());
        assertEquals("live=1\n", Files.readString(payload.resolve("props/private_system.prop")));
        assertEquals("#!/bin/sh\nexec chrome\n",
                Files.readString(payload.resolve("pos/chromium/chromium-wrapper.sh")));
        assertTrue(Files.exists(payload.resolve("filebeat/pos/filebeat.yml")));
        assertFalse(Files.exists(payload.resolve("pos/petrotest-pos-shim.jar")), "build outputs are not staged");
        assertFalse(Files.exists(payload.resolve("pos/chromium/libcups.so.2")),
                "the libcups stub is the Python CAT's to build");
    }

    @Test
    void theCommanderGetsItsJacocoAgentFromPatsWhenTheTargetHasNoCopy() throws IOException {
        Path pats = dir.resolve("pats-jacocoagent.jar");
        Files.write(pats, "pats-jar".getBytes(StandardCharsets.UTF_8));
        Path payload = dir.resolve("payload");
        TargetSpec spec = new TargetSpec(TargetRole.COMMANDER, "commander", "commander.invalid", "root", 22, 8801, null);

        Stage.stagePayload("bench", spec, new InMemoryTransport("commander.invalid"), payload, TIMEOUT,
                new Stage.Sources(templates(), bridgeDir(), pats));

        assertEquals("pats-jar", Files.readString(payload.resolve("jacoco/jacocoagent.jar")));
    }

    @Test
    void aTargetWithNoCopyLeavesTheFileMissingAndDoesNotRaise() throws IOException {
        Path payload = dir.resolve("payload");
        TargetSpec spec = new TargetSpec(TargetRole.COMMANDER, "commander", "commander.invalid", "root", 22, 8801, null);

        Stage.stagePayload("bench", spec, new InMemoryTransport("commander.invalid"), payload, TIMEOUT,
                new Stage.Sources(templates(), bridgeDir(), dir.resolve("absent.jar")));

        assertFalse(Files.exists(payload.resolve("props/core_private_system.prop")));
        assertFalse(Files.exists(payload.resolve("jacoco/jacocoagent.jar")));
    }

    @Test
    void anAlreadyStagedFileIsNeverOverwritten() throws IOException {
        Path payload = dir.resolve("payload");
        Files.createDirectories(payload.resolve("props"));
        Files.writeString(payload.resolve("props/private_system.prop"), "hand-staged");
        RecordingTransport register = new RecordingTransport("topaz101.invalid");
        register.inner.withFile("/home/topaz/config/private_system.prop", "live".getBytes(StandardCharsets.UTF_8));
        TargetSpec spec = new TargetSpec(TargetRole.REGISTER, "topaz101", "topaz101.invalid", "root", 22, 8801, null);

        Stage.stagePayload("bench", spec, register, payload, TIMEOUT,
                new Stage.Sources(templates(), bridgeDir(), dir.resolve("absent.jar")));

        assertEquals("hand-staged", Files.readString(payload.resolve("props/private_system.prop")));
    }

    @Test
    void aStaleBridgeCopyIsRestagedFromTheRepositoryAndAnUpToDateOneIsLeftAlone() throws IOException {
        Path payload = dir.resolve("payload");
        Path stale = payload.resolve("pos/chromium/chromium-wrapper.sh");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "#!/bin/sh\nexec old-chrome\n");
        TargetSpec spec = new TargetSpec(TargetRole.REGISTER, "topaz101", "topaz101.invalid", "root", 22, 8801, null);
        Stage.Sources sources = new Stage.Sources(templates(), bridgeDir(), dir.resolve("absent.jar"));

        List<String> first = Stage.stagePayload("bench", spec, new RecordingTransport("topaz101.invalid"), payload,
                TIMEOUT, sources);
        assertEquals("#!/bin/sh\nexec chrome\n", Files.readString(stale));
        assertTrue(first.contains("pos/chromium/chromium-wrapper.sh"));

        List<String> second = Stage.stagePayload("bench", spec, new RecordingTransport("topaz101.invalid"), payload,
                TIMEOUT, sources);
        assertFalse(second.stream().anyMatch(s -> s.startsWith("pos/chromium/")), second.toString());
    }

    @Test
    void carriageReturnsAreReadAsLineFeeds() {
        assertArrayEquals("a\nb\r\n".replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8),
                Stage.withLineFeeds("a\r\nb\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    /** The four bridge sources, as the repository has them: the wrapper with CRLF, the rest as plain text. */
    private Path bridgeDir() throws IOException {
        Path bridge = dir.resolve("bridge");
        Files.createDirectories(bridge);
        Files.write(bridge.resolve("chromium-wrapper.sh"), "#!/bin/sh\r\nexec chrome\r\n".getBytes(StandardCharsets.UTF_8));
        Files.writeString(bridge.resolve("petrotest-no-infobars.json"), "{}\n");
        Files.writeString(bridge.resolve("CdpRelay.java"), "class CdpRelay {}\n");
        Files.writeString(bridge.resolve("cdp-relay-boot.sh"), "#!/bin/sh\n");
        return bridge;
    }
}
