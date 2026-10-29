package com.verifone.cat.authoring;

import static com.verifone.cat.recorder.RecordingFixture.PASSWORD_PROMPT;
import static com.verifone.cat.recorder.RecordingFixture.entry;
import static com.verifone.cat.recorder.RecordingFixture.tap;
import static com.verifone.cat.recorder.RecordingFixture.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.recorder.CatalogueMap;
import com.verifone.cat.recorder.Collapse;
import com.verifone.cat.recorder.Recording;
import com.verifone.cat.recorder.RecordingFixture;

/**
 * The key evidence for this slice: the Java's catalogue renderings, the recorder's collapse and the recorded feature,
 * and the drafted-case assembly equal the Python CAT's own, run by {@code src/test/resources/authoring/dump_authoring.py}
 * under its interpreter over a recording the Java wrote (so the file format is checked too).
 *
 * <p>Skipped (an assumption, not a failure) when the Python CAT is not checked out at {@code CAT_PYTHON_SRC} or its
 * interpreter is not at {@code CAT_PYTHON}, as {@link StepLibraryParityTest} is. Agreement with the Python's text, not
 * with a rig.
 */
class AuthoringParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PYTHON_SRC = Path.of(
            System.getenv().getOrDefault("CAT_PYTHON_SRC", "C:\\gitrepos\\CAT\\src"));
    private static final Path PYTHON = Path.of(
            System.getenv().getOrDefault("CAT_PYTHON", "C:\\gitrepos\\CAT\\.venv\\Scripts\\python.exe"));
    private static final String DUMP = "src/test/resources/authoring/dump_authoring.py";

    @TempDir
    static Path tmp;

    private static JsonNode python;
    private static Recording recording;

    @BeforeAll
    static void dump() throws Exception {
        Locator vfiKey = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N;
        Locator vfiOk = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK;
        List<Map<String, Object>> taps = new ArrayList<>();
        taps.add(tap(Locators.IDLE_SCREEN_VIEW_IDLE_MSG));
        taps.addAll(entry(vfiKey, vfiOk, "1", "Enter ID"));
        taps.addAll(entry(vfiKey, vfiOk, "123", PASSWORD_PROMPT));
        taps.add(tap(Locators.EXPRESS_PANEL_VIEW_KEYPAD_ICON));
        taps.addAll(entry(Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK, "0009", null));
        taps.add(tap(null, null, List.of("Total")));
        taps.add(tap(Locators.ITEM_PANEL_N.at(145397)));
        taps.add(tap(Locators.ITEM_PANEL_N.at(145394)));
        taps.addAll(entry(vfiKey, vfiOk, "1100", null));
        taps.add(tap(Locators.RECEIPT_VIEW_ITEM_LINES_CONTAINER));
        taps.add(tap(null, null, List.of("A New Label")));
        taps.add(tap(Locators.REVIEW_OVERLAY_VIEW_ITEM_N.at(3)));
        taps.add(tap(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL));
        taps.add(tap("RecorderTest_Unknown"));
        taps.addAll(entry(vfiKey, vfiOk, "77", PASSWORD_PROMPT));
        taps.add(withText(tap(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(4)), "Void Ticket"));
        taps.add(tap(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7)));
        taps.add(tap(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(3)));
        Path file = RecordingFixture.record(tmp.resolve("parity.ndjson"), taps);
        recording = Recording.read(file);
        if (!Files.isDirectory(PYTHON_SRC) || !Files.isRegularFile(PYTHON)) {
            return;
        }
        Process process = new ProcessBuilder(PYTHON.toString(), Path.of(DUMP).toAbsolutePath().toString(),
                file.toString()).directory(PYTHON_SRC.getParent().toFile()).start();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "the Python dump did not finish");
        assertEquals(0, process.exitValue(), "the Python dump failed: " + new String(err, StandardCharsets.UTF_8));
        python = JSON.readTree(new String(out, StandardCharsets.UTF_8));
    }

    private static void needPython() {
        assumeTrue(python != null, "the Python CAT is not checked out here");
    }

    @Test
    void theCatalogueRendersAsThePythonsByteForByte() {
        needPython();
        assertEquals(python.get("text").asText(), Catalogue.renderText());
        assertEquals(python.get("markdown").asText(), Catalogue.renderMarkdown());
        assertEquals(python.get("json").asText(), Catalogue.renderJson());
    }

    @Test
    void theReverseMapKnowsAsManyEntriesAsThePythons() {
        needPython();
        assertEquals(python.get("entries").asInt(), CatalogueMap.standard().entries());
    }

    @Test
    void theCollapseAndTheRecordedFeatureAreThePythons() {
        needPython();
        List<Collapse.Step> steps = Collapse.collapse(recording);
        List<String> codes = new ArrayList<>();
        python.get("codes").forEach(n -> codes.add(n.asText()));
        List<String> observed = new ArrayList<>();
        python.get("observed").forEach(n -> observed.add(n.asText()));
        List<Boolean> ok = new ArrayList<>();
        python.get("ok").forEach(n -> ok.add(n.asBoolean()));
        assertEquals(codes, steps.stream().map(Collapse.Step::code).toList());
        assertEquals(observed, steps.stream().map(Collapse.Step::observed).toList());
        assertEquals(ok, steps.stream().map(Collapse.Step::ok).toList());
        assertEquals(python.get("feature").asText(),
                Recorded.recordingFeature(recording, "Cash sale", "Authored cases", "PETROCORE-3"));
    }

    @Test
    void aDraftedCaseAssemblesAndClaudeIsAskedAsThePythonDoes() {
        needPython();
        String assembled = python.get("assembled").asText();
        Matcher date = Pattern.compile("on (\\d{4}-\\d{2}-\\d{2})\\.").matcher(assembled);
        assertTrue(date.find(), assembled);
        Map<String, Object> drafted = new LinkedHashMap<>();
        drafted.put("title", "Card types: donate");
        drafted.put("covers", "AC  1");
        drafted.put("assumptions", List.of("one", "two  words"));
        drafted.put("steps", List.of("the register is idle", "When the customer inserts card \"<card>\"", ""));
        drafted.put("examples", Map.of("header", List.of("card"), "rows", List.of(List.of("AMEX"), List.of("VISA"))));
        assertEquals(assembled, Draft.assemble(drafted, "F", "PETROCORE-5", "PETROCORE-3",
                LocalDate.parse(date.group(1))));
        List<String> argv = new ArrayList<>();
        python.get("argv").forEach(n -> argv.add(n.asText()));
        assertEquals(argv, Draft.claudeArgv(List.of("claude"), "m", 2.5));
    }
}
