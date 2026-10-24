package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 5, Tests Run, over every case of the two Charity suites ({@code charity_combined}, {@code charity_outdoor}), on the
 * fake POS in headless Chromium, through {@link StandardRegistry} and {@link BrowserConnector}. The cases are the
 * {@code .feature} files the Python suites run with {@code --fake-pos}; they are the same files under
 * {@code src/test/resources/pats/feature}.
 *
 * <p>Evidence about this repository only: no register, no Commander and no cloud is reached. The assertions are about
 * the wiring: every case gets a verdict, every action with a name has a handler, and no internal error (a null, a cast)
 * escapes into a verdict. Each case's verdict, the first step that failed and its message are written to
 * {@code target/charity-fake-pos.tsv}, the table the Java-vs-Python comparison reads. Skipped, not failed, when Chromium
 * is not installed for Playwright.
 */
class CharityOnFakePosStepFiveTest {

    static final String RESOURCES = "/pats/feature/";
    static final List<String> SUITES = List.of("charity_combined", "charity_outdoor");
    static final Path REPORT = Path.of("target", "charity-fake-pos.tsv");

    @TempDir
    Path dir;

    @Test
    void everyCharityCaseGetsAVerdictFromStepFiveOnTheFakePos() throws Exception {
        assumeTrue(chromiumInstalled(), "Chromium is not installed for Playwright; skipping");
        List<CaseEntry> entries = charityCases();
        assertFalse(entries.isEmpty(), "the Charity features are on the test classpath");

        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG).fakePos(true).build();
        Path folder = dir.resolve("run");
        RunEvents events = RunEvents.open(folder, Clock.systemUTC());
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), "charity", folder, events, new PauseGate(),
                Clock.systemUTC());
        TestsRunStep step = new TestsRunStep(new StandardRegistry(), config1 -> entries, new BrowserConnector());

        StepRunner.runLocal(ctx, step);

        List<JsonNode> recorded = LaunchFixtures.events(folder);
        Map<String, Verdict> verdicts = verdicts(recorded);
        writeReport(entries, verdicts);
        for (CaseEntry entry : entries) {
            Verdict verdict = verdicts.get(entry.nodeid());
            assertTrue(verdict != null, entry.key() + " has no test.finished event");
            assertFalse(verdict.message().contains("no handler for"),
                    entry.key() + " named an action with no handler: " + verdict.message());
            assertFalse(verdict.message().contains("NullPointerException")
                    || verdict.message().contains("ClassCastException"),
                    entry.key() + " hit an internal error: " + verdict.message());
        }
        assertEquals(entries.size(), verdicts.size(), "one verdict per case");
    }

    /** One case's verdict and, when it failed, the first failing step's text and message. */
    record Verdict(String outcome, String step, String message) {
    }

    private static Map<String, Verdict> verdicts(List<JsonNode> events) {
        Map<String, Verdict> out = new LinkedHashMap<>();
        Map<String, String> failedStep = new LinkedHashMap<>();
        Map<String, String> failedMessage = new LinkedHashMap<>();
        for (JsonNode event : events) {
            String type = event.path("type").asText();
            String nodeid = event.path("nodeid").asText();
            if ("test.step".equals(type) && "failed".equals(event.path("outcome").asText())
                    && !failedStep.containsKey(nodeid)) {
                failedStep.put(nodeid, event.path("text").asText());
                failedMessage.put(nodeid, event.path("detail").asText());
            }
            if ("test.finished".equals(type)) {
                String message = event.path("detail").asText();
                if (message.isEmpty()) {
                    message = failedMessage.getOrDefault(nodeid, "");
                }
                out.put(nodeid, new Verdict(event.path("outcome").asText(), failedStep.getOrDefault(nodeid, ""),
                        message));
            }
        }
        return out;
    }

    private static void writeReport(List<CaseEntry> entries, Map<String, Verdict> verdicts) throws IOException {
        StringBuilder tsv = new StringBuilder("case\tsuite\toutcome\tfirst_failing_step\tmessage\n");
        for (CaseEntry entry : entries) {
            Verdict v = verdicts.get(entry.nodeid());
            String suite = entry.nodeid().split("/")[2];
            tsv.append(entry.key()).append('\t').append(suite).append('\t')
                    .append(v == null ? "no verdict" : v.outcome()).append('\t')
                    .append(v == null ? "" : clean(v.step())).append('\t')
                    .append(v == null ? "" : clean(v.message())).append('\n');
        }
        Files.createDirectories(REPORT.toAbsolutePath().getParent());
        Files.writeString(REPORT, tsv.toString(), StandardCharsets.UTF_8);
        System.out.println("[e2e] wrote " + REPORT.toAbsolutePath() + " for " + entries.size() + " cases");
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Every Charity feature in both suites, in suite then file-name order, as runnable case entries. */
    static List<CaseEntry> charityCases() throws Exception {
        List<CaseEntry> entries = new ArrayList<>();
        for (String suite : SUITES) {
            Path folder = Path.of(CharityOnFakePosStepFiveTest.class.getResource(RESOURCES + suite).toURI());
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith("-TestCase.feature"))
                        .sorted().toList()) {
                    String name = file.getFileName().toString();
                    String key = name.substring(0, name.indexOf("-TestCase"));
                    entries.add(new CaseEntry(key, "tests/suites/" + suite + "/" + name, file));
                }
            }
        }
        return List.copyOf(entries);
    }

    private static boolean chromiumInstalled() {
        try (Playwright playwright = Playwright.create()) {
            playwright.chromium().launch().close();
            return true;
        } catch (PlaywrightException missing) {
            return false;
        }
    }
}
