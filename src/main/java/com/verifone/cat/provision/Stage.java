package com.verifone.cat.provision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.provision.transport.Transport;

/**
 * Stages the local {@code payload/} tree before {@code provision} reads it. A port of the offline half of
 * {@code petrotest.provision.stage}.
 *
 * <p>A file already in the payload is never overwritten, so a hand-staged one wins, except a copy of a file in
 * {@code bridge/browser-override/}: that source is the repository's, and a stale copy is staged again.
 *
 * <p>Staged here: the two {@code *private_system.prop} files (the target's live copy), the JaCoCo agent (the
 * target's, else PATS's on this controller), the Filebeat configs (rendered from the templates), and the four
 * browser-override files (copied from the repository).
 *
 * <p>Not staged here, left to the Python CAT: the Chrome for Testing tarball (a zip repacked as tar.gz), the
 * libxkbcommon package (an xz-compressed {@code .deb}, which the JDK cannot read), and the libcups stub (built by
 * {@code tools/build_libcups_stub.py}). A payload missing one of them fails the push by name, as it does in Python.
 *
 * <p>Never raises for a source that is down: the file stays missing and the push names it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Stage {

    private static final Logger log = LoggerFactory.getLogger(Stage.class);

    /** The payload files whose source is {@code bridge/browser-override/}, by payload name. */
    public static final List<String> BRIDGE_FILES = List.of("chromium-wrapper.sh", "petrotest-no-infobars.json",
            "CdpRelay.java", "cdp-relay-boot.sh");

    /** Where the staging reads its inputs from. The defaults are {@link ProvisionPaths}'. */
    public record Sources(Path templatesRoot, Path browserOverrideDir, Path patsJacocoAgent) {

        public static Sources fromEnv(Map<String, String> env) {
            return new Sources(ProvisionPaths.templatesRoot(env), ProvisionPaths.browserOverrideDir(env),
                    ProvisionPaths.patsJacocoAgent(env));
        }
    }

    private Stage() {}

    /**
     * Produces every payload file {@code spec}'s role needs that is not staged yet.
     *
     * @return the source paths it wrote, refreshed copies included
     */
    public static List<String> stagePayload(String rigName, TargetSpec spec, Transport transport, Path root,
            Duration timeout, Sources sources) {
        List<ArtifactFile> files = new ArrayList<>();
        for (Artifact artifact : Artifacts.artifactsFor(spec.role(), Artifacts.ARTIFACTS)) {
            files.addAll(artifact.files());
        }
        Set<String> wanted = new HashSet<>();
        files.forEach(f -> wanted.add(f.source()));
        List<String> refreshed = refreshBridgeCopies(root, wanted, sources.browserOverrideDir());

        List<ArtifactFile> missing = files.stream().filter(f -> !Files.isRegularFile(root.resolve(f.source()))).toList();
        if (missing.isEmpty()) {
            return refreshed;
        }
        Set<String> renderable = new HashSet<>();
        Render.RENDERABLE.forEach(item -> renderable.add(item.source()));
        if (missing.stream().anyMatch(f -> renderable.contains(f.source()))) {
            try {
                Render.renderPayload(rigName, root, sources.templatesRoot(), Map.of());
            } catch (RuntimeException e) {
                log.warn("could not render the config templates: {}", e.getMessage());
            }
        }

        List<String> staged = new ArrayList<>(refreshed);
        for (ArtifactFile file : missing) {
            String source = file.source();
            Path destination = root.resolve(source);
            if (renderable.contains(source) && Files.isRegularFile(destination)) {
                staged.add(source);
                continue;
            }
            try {
                Optional<byte[]> content = provide(file, spec, transport, timeout, sources);
                if (content.isEmpty()) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.write(destination, content.get());
                log.info("staged {} ({} bytes)", source, content.get().length);
                staged.add(source);
            } catch (IOException | RuntimeException e) {
                log.warn("could not stage {}: {}", source, e.getMessage());
            }
        }
        return List.copyOf(staged);
    }

    /**
     * The bytes for one missing file, or empty for a file this port does not produce (a build output, or one of the
     * Python-only downloads). Throws when a source that should have it fails.
     */
    private static Optional<byte[]> provide(ArtifactFile file, TargetSpec spec, Transport transport, Duration timeout,
            Sources sources) throws IOException {
        String source = file.source();
        if (source.equals("props/private_system.prop") || source.equals("props/core_private_system.prop")) {
            return Optional.of(fromTarget(spec, transport, file, timeout));
        }
        if (source.equals("jacoco/jacocoagent.jar")) {
            return Optional.of(jacoco(spec, transport, file, timeout, sources));
        }
        for (String name : BRIDGE_FILES) {
            if (source.equals("pos/chromium/" + name)) {
                return Optional.of(bridgeBytes(sources.browserOverrideDir(), name));
            }
        }
        if (source.equals("filebeat/commander/filebeat.yml") || source.equals("filebeat/pos/filebeat.yml")) {
            return Optional.empty();
        }
        if (source.startsWith("pos/chromium/chrome-linux64-") || source.equals("pos/chromium/libxkbcommon.so.0")
                || source.equals("pos/chromium/libcups.so.2")) {
            log.info("not staged by CAT-Java: {} (stage it with the Python CAT, or by hand)", source);
        }
        return Optional.empty();
    }

    private static byte[] fromTarget(TargetSpec spec, Transport transport, ArtifactFile file, Duration timeout) {
        return transport.get(file.remotePath(), timeout).orElseThrow(
                () -> new IllegalStateException(spec.name() + " has no " + file.remotePath()));
    }

    /** The target's own JaCoCo agent, else PATS's copy on this controller. Raises when neither exists. */
    private static byte[] jacoco(TargetSpec spec, Transport transport, ArtifactFile file, Duration timeout,
            Sources sources) throws IOException {
        try {
            Optional<byte[]> copy = transport.get(file.remotePath(), timeout);
            if (copy.isPresent()) {
                return copy.get();
            }
        } catch (RuntimeException e) {
            log.debug("{} has no JaCoCo agent to copy: {}", spec.name(), e.getMessage());
        }
        if (Files.isRegularFile(sources.patsJacocoAgent())) {
            return Files.readAllBytes(sources.patsJacocoAgent());
        }
        throw new IllegalStateException(spec.name() + " has no " + file.remotePath() + " and PATS's copy is absent at "
                + sources.patsJacocoAgent());
    }

    /** One bridge file, read as the repository has it, with CRLF read as LF (a shell and a JVM on Linux need that). */
    static byte[] bridgeBytes(Path browserOverrideDir, String name) throws IOException {
        return withLineFeeds(Files.readAllBytes(browserOverrideDir.resolve(name)));
    }

    static byte[] withLineFeeds(byte[] text) {
        byte[] out = new byte[text.length];
        int n = 0;
        for (int i = 0; i < text.length; i++) {
            if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                continue;
            }
            out[n++] = text[i];
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Re-copies a staged bridge file that no longer matches its repository source. The staged copy is only ever a
     * copy, so an edited {@code CdpRelay.java} must reach a register that already has one staged.
     */
    static List<String> refreshBridgeCopies(Path root, Set<String> wanted, Path browserOverrideDir) {
        List<String> refreshed = new ArrayList<>();
        for (String name : BRIDGE_FILES) {
            String source = "pos/chromium/" + name;
            Path destination = root.resolve(source);
            if (!wanted.contains(source) || !Files.isRegularFile(destination)) {
                continue;
            }
            try {
                byte[] canonical = bridgeBytes(browserOverrideDir, name);
                if (!Arrays.equals(Files.readAllBytes(destination), canonical)) {
                    Files.write(destination, canonical);
                    log.info("restaged {}: bridge/browser-override/{} changed", source, name);
                    refreshed.add(source);
                }
            } catch (IOException e) {
                // The repository copy is missing or unreadable: the staged one stays as it is.
                log.debug("{} not refreshed: {}", source, e.getMessage());
            }
        }
        return refreshed;
    }
}
