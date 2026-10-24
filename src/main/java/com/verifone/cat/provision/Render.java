package com.verifone.cat.provision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the payload files this repository can derive from a checked-in template, and reports by name every file
 * it cannot. A port of {@code petrotest.provision.render}, the {@code render-payload} half.
 *
 * <p>{@link #RENDERABLE} and {@link #NOT_RENDERABLE} partition the file sources of {@link Artifacts#ARTIFACTS}.
 * A template placeholder with no value is left in place and reported, not guessed: the Java rig schema has no
 * {@code logging:} block, so the Filebeat hosts stay unresolved until the caller supplies one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Render {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /** A payload file produced from a checked-in template under the templates root. */
    public record RenderableTemplate(String source, String template, String citedFrom) {}

    /** A payload file this tool cannot produce, reported and never guessed. */
    public record NotRenderableFile(String source, String reason) {}

    public static final List<RenderableTemplate> RENDERABLE = List.of(
            new RenderableTemplate("filebeat/commander/filebeat.yml", "filebeat/commander/filebeat.yml",
                    "AutomationTests/configurations/Filebeat/commander/filebeat.yml"),
            new RenderableTemplate("filebeat/pos/filebeat.yml", "filebeat/pos/filebeat.yml",
                    "AutomationTests/configurations/Filebeat/pos/filebeat.yml"));

    public static final List<NotRenderableFile> NOT_RENDERABLE = List.of(
            new NotRenderableFile("props/private_system.prop",
                    "no static source in petroapps; the automation build's Bin/Config/ output, not checked-in content"),
            new NotRenderableFile("props/core_private_system.prop",
                    "no static source in petroapps; the automation build's Bin/Config/ output, not checked-in content"),
            new NotRenderableFile("pos/automation-target-pos-topaz.jar",
                    "compiled artifact: tools/build_pos_shim.py compiles the retained petroapps component"),
            new NotRenderableFile("pos/topazerp-connection-enabler.jar",
                    "compiled artifact: one class from petroapps' TopazERP, built by tools/build_pos_shim.py"),
            new NotRenderableFile("pos/petrotest-pos-shim.jar",
                    "compiled artifact: bridge/pos-shim/, built by tools/build_pos_shim.py"),
            new NotRenderableFile("pos/petrotest-pos-shim.sh",
                    "ships in bridge/pos-shim/, copied into the payload by tools/build_pos_shim.py"),
            new NotRenderableFile("pos/tokenRMI.prop",
                    "emitted by tools/build_pos_shim.py: fixed content naming 127.0.0.1, the shim being co-located"),
            new NotRenderableFile("jacoco/jacocoagent.jar",
                    "third-party binary, out of the JaCoCo distribution"),
            new NotRenderableFile("pos/chromium/chrome-linux64-152.0.7977.42.tar.gz",
                    "third-party binary: a stock Chrome for Testing build, staged by hand"),
            new NotRenderableFile("pos/chromium/libxkbcommon.so.0",
                    "third-party binary from the Ubuntu 20.04 focal package (BROWSER_STRATEGY.md 5.0)"),
            new NotRenderableFile("pos/chromium/libcups.so.2",
                    "generated artifact: tools/build_libcups_stub.py (symbol intersection, BROWSER_STRATEGY.md 5.0)"),
            new NotRenderableFile("pos/chromium/chromium-wrapper.sh",
                    "fixed content from bridge/browser-override/, copied into the payload by the staging step"),
            new NotRenderableFile("pos/chromium/petrotest-no-infobars.json",
                    "fixed content from bridge/browser-override/, copied into the payload by the staging step"),
            new NotRenderableFile("pos/chromium/CdpRelay.java",
                    "fixed content from bridge/browser-override/, copied into the payload by the staging step"),
            new NotRenderableFile("pos/chromium/cdp-relay-boot.sh",
                    "fixed content from bridge/browser-override/, copied into the payload by the staging step"));

    private Render() {}

    /** One rendered file: where it came from, and any placeholder left unresolved. */
    public record RenderedFile(String source, String citedFrom, List<String> unresolved) {
        public boolean ok() {
            return unresolved.isEmpty();
        }
    }

    /** One not-renderable file, and whether the payload holds it. */
    public record NotRenderableStatus(String source, String reason, boolean present) {}

    /** What {@link #renderPayload} did, and {@link #render} prints. */
    public static final class Report {
        private final String rig;
        private final List<RenderedFile> rendered = new ArrayList<>();
        private final List<NotRenderableStatus> notRenderable = new ArrayList<>();

        public Report(String rig) {
            this.rig = rig;
        }

        public List<RenderedFile> rendered() {
            return List.copyOf(rendered);
        }

        public List<NotRenderableStatus> notRenderable() {
            return List.copyOf(notRenderable);
        }

        /** Whether every template this tool owns rendered clean. Missing build outputs are the push's to report. */
        public boolean ok() {
            return rendered.stream().allMatch(RenderedFile::ok);
        }

        public String render() {
            StringBuilder out = new StringBuilder("render-payload ").append(rig).append('\n');
            for (RenderedFile file : rendered) {
                if (file.ok()) {
                    out.append("  rendered  ").append(file.source()).append("  (").append(file.citedFrom()).append(")\n");
                } else {
                    out.append("  UNRESOLVED ").append(file.source()).append("  needs: ")
                            .append(String.join(", ", file.unresolved())).append(" - no rig field supplies this yet\n");
                }
            }
            for (NotRenderableStatus status : notRenderable) {
                out.append("  ").append(status.present() ? "present " : "MISSING ").append(' ')
                        .append(status.source()).append("  (").append(status.reason()).append(")\n");
            }
            out.append("  total ").append(ok() ? "OK" : "UNRESOLVED PLACEHOLDERS");
            return out.toString();
        }
    }

    /**
     * Renders every {@link #RENDERABLE} file into {@code payloadRoot}, and reports every {@link #NOT_RENDERABLE} one.
     * A template that resolves clean overwrites what was there, as re-running any generator here does.
     *
     * @param variables the values for {@code ${name}} placeholders; an absent name stays in the file, reported
     */
    public static Report renderPayload(String rigName, Path payloadRoot, Path templatesRoot,
            Map<String, String> variables) {
        Report report = new Report(rigName);
        for (RenderableTemplate item : RENDERABLE) {
            String text;
            try {
                text = Files.readString(templatesRoot.resolve(item.template()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read template " + templatesRoot.resolve(item.template()), e);
            }
            String rendered = substitute(text.replace("\r\n", "\n"), variables);
            TreeSet<String> unresolved = new TreeSet<>();
            Matcher m = PLACEHOLDER.matcher(rendered);
            while (m.find()) {
                unresolved.add(m.group(1));
            }
            Path destination = payloadRoot.resolve(item.source());
            try {
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, rendered, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot write " + destination, e);
            }
            report.rendered.add(new RenderedFile(item.source(), item.citedFrom(), List.copyOf(unresolved)));
        }
        for (NotRenderableFile missing : NOT_RENDERABLE) {
            boolean present = Files.isRegularFile(payloadRoot.resolve(missing.source()));
            report.notRenderable.add(new NotRenderableStatus(missing.source(), missing.reason(), present));
        }
        return report;
    }

    /** {@code string.Template.safe_substitute} for {@code ${name}}: a known name is replaced, anything else kept. */
    static String substitute(String text, Map<String, String> variables) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
