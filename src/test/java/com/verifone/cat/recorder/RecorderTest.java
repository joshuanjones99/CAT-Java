package com.verifone.cat.recorder;

import static com.verifone.cat.recorder.RecordingFixture.PASSWORD_PROMPT;
import static com.verifone.cat.recorder.RecordingFixture.entry;
import static com.verifone.cat.recorder.RecordingFixture.record;
import static com.verifone.cat.recorder.RecordingFixture.tap;
import static com.verifone.cat.recorder.RecordingFixture.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.authoring.Check;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.validate.Normalisers;

/**
 * The recorder without a browser: the reverse map, the file, the collapse, the generator and the commands. Port of the
 * Python CAT's {@code tests/unit/test_recorder.py}; the taps are built through the recorder's own session, from ids read
 * out of the catalogue. Evidence about this repository, not about a register.
 */
class RecorderTest {

    private static final Locator VFI_KEY = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N;
    private static final Locator VFI_OK = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK;
    private static final Locator CALC_KEY = Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N;
    private static final Locator CALC_OK = Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK;
    private static final Locator EXPRESS = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N;
    private static final Locator ITEM = Locators.ITEM_PANEL_N;

    @TempDir
    Path tmp;

    // -- the reverse map --------------------------------------------------------

    @Test
    void everyCatalogueIdMapsBackToItsOwnEntry() {
        CatalogueMap cmap = CatalogueMap.standard();
        List<String> wrong = new ArrayList<>();
        for (Locator loc : Locators.ELEMENT_IDS) {
            List<String> slots = Collections.nCopies(loc.placeholders().size(), "12");
            String observed = loc.isTemplate() ? loc.at(slots.toArray()).value() : loc.value();
            Mapping got = cmap.byId(observed);
            if (!got.isMapped() || !loc.name().equals(got.locator()) || !got.args().equals(slots)) {
                wrong.add(loc.name() + " -> " + got);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void anExactIdBeatsATemplateAndAnUnknownIdIsUnmapped() {
        CatalogueMap cmap = CatalogueMap.standard();
        Mapping ok = cmap.byId(VFI_OK.value());
        assertEquals(VFI_OK.name(), ok.locator());
        assertEquals(List.of(), ok.args());
        assertEquals("unmapped", cmap.byId("RecorderTest_Unknown").status());
    }

    @Test
    void anIdTheCatalogueDoesNotKnowIsNeverRetriedByItsText() {
        assertEquals("unmapped", CatalogueMap.standard().observe("RecorderTest_Unknown", "button", List.of("Total"))
                .status());
    }

    @Test
    void anIdLessButtonIsNamedByItsOwnText() {
        CatalogueMap cmap = CatalogueMap.standard();
        Mapping total = cmap.observe(null, "button", List.of("Total"));
        assertEquals("mapped", total.status());
        assertEquals("text", total.via());
        assertEquals("Total", CatalogueMap.textOf(total.locator()));
        Mapping fresh = cmap.observe(null, "button", List.of("A New Label"));
        assertEquals(Locators.XP_BUTTON_BY_TEXT.name(), fresh.locator());
        assertEquals(Map.of("label", "A New Label"), fresh.kwargs());
        assertEquals("ambiguous", cmap.observe(null, "button", List.of("One", "Two")).status());
        assertEquals("unmapped", cmap.observe(null, "span", List.of("Total")).status());
    }

    @Test
    void twoEntriesNamingOneIdIsAmbiguousNotAPick() {
        Locator twin = new Locator(ITEM.value(), ITEM.kind(), "TWIN", "twin", 0, false, false, null, null, List.of(), "",
                false);
        Mapping got = new CatalogueMap(List.of(ITEM, twin)).byId(ITEM.at(5).value());
        assertEquals("ambiguous", got.status());
        assertEquals(List.of("ITEM_PANEL_N", "TWIN"), got.candidates());
    }

    // -- the file ---------------------------------------------------------------

    @Test
    void aPasswordPromptBlanksEveryDigitAndKeepsOk() throws Exception {
        List<Map<String, Object>> taps = List.of(
                tap(VFI_KEY.at(7), PASSWORD_PROMPT, List.of("7")),
                tap(VFI_OK, PASSWORD_PROMPT, null),
                tap(VFI_KEY.at(7), "Enter ID", null));
        Path path = record(tmp.resolve("r.ndjson"), taps);
        Recording recording = Recording.read(path);
        Map<String, Object> digit = recording.taps().get(0);
        Map<String, Object> ok = recording.taps().get(1);
        Map<String, Object> plain = recording.taps().get(2);
        assertEquals(true, digit.get("redacted"));
        assertNull(Recording.map(digit.get("element")).get("id"));
        assertEquals(List.of("*"), Recording.map(digit.get("map")).get("args"));
        assertEquals(false, ok.get("redacted"));
        assertEquals(VFI_OK.name(), Recording.map(ok.get("map")).get("locator"));
        assertEquals(false, plain.get("redacted"));
        assertEquals(List.of("7"), Recording.map(plain.get("map")).get("args"));
        assertTrue(Files.readString(path).contains(VFI_KEY.at(7).value()), "the un-redacted tap keeps its id");
        assertEquals(List.of(1, 2, 3), recording.taps().stream().map(t -> t.get("seq")).toList());
    }

    @Test
    void theReaderRefusesWhatItDoesNotKnow() throws Exception {
        Path path = record(tmp.resolve("r.ndjson"), List.of());
        List<String> lines = Files.readAllLines(path);
        String header = lines.get(0);
        List<List<String>> bads = List.of(
                List.of(header.replace("\"version\": 1", "\"version\": 2")),
                List.of("{\"type\": \"tap\"}"),
                List.of(lines.get(0), lines.get(1), "{\"type\": \"tap\"}"),
                List.of(header, "{\"type\": \"surprise\"}"));
        for (List<String> bad : bads) {
            Files.writeString(path, String.join("\n", bad) + "\n");
            assertThrows(RecordingException.class, () -> Recording.read(path), bad.toString());
        }
        Path cut = record(tmp.resolve("cut.ndjson"), List.of(tap(ITEM.at(9999))), "rig", "CSR Func", null, false);
        assertNull(Recording.read(cut).footer());
    }

    @Test
    void aRecordingFromBeforeTheRenameReadsAsTheFakePos() throws Exception {
        Path path = record(tmp.resolve("r.ndjson"), List.of(), "pos_double", "CSR Func", null, true);
        assertEquals("fake_pos", Recording.read(path).sourceKind());
    }

    @Test
    void theWriterNumbersTapsSecondAndKeepsNonAscii() {
        StringWriter out = new StringWriter();
        RecordingWriter writer = new RecordingWriter(out);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "tap");
        line.put("text", "café — ok");
        writer.write(line);
        assertEquals("{\"type\": \"tap\", \"seq\": 1, \"text\": \"café — ok\"}\n", out.toString());
    }

    // -- the collapse -----------------------------------------------------------

    private List<String> codes(Path path) throws Exception {
        return Collapse.collapse(Recording.read(path)).stream().map(Collapse.Step::code).toList();
    }

    @Test
    void signOnSignOffVoidAndTheMopRules() throws Exception {
        Locator reviewRow = Locators.REVIEW_OVERLAY_VIEW_ITEM_N.at(13631488);
        List<Map<String, Object>> taps = new ArrayList<>();
        taps.add(tap(Locators.IDLE_SCREEN_VIEW_IDLE_MSG));
        taps.addAll(entry(VFI_KEY, VFI_OK, "1", "Enter ID"));
        taps.addAll(entry(VFI_KEY, VFI_OK, "123", PASSWORD_PROMPT));
        taps.add(withText(tap(reviewRow), "1. OPEN TILL"));
        taps.addAll(entry(CALC_KEY, CALC_OK, "10000", null));
        taps.add(tap(null, null, List.of("Quantity")));
        taps.addAll(entry(VFI_KEY, VFI_OK, "2", null));
        taps.add(tap(ITEM.at(9999)));
        taps.add(tap(ITEM.at(145397)));
        taps.add(tap(ITEM.at(145395)));
        taps.add(tap(ITEM.at(145394)));
        taps.add(withText(tap(EXPRESS.at(4)), "Void Ticket"));
        taps.add(tap(EXPRESS.at(7)));
        taps.add(withText(tap(EXPRESS.at(5)), "Log Out"));
        taps.add(tap(EXPRESS.at(2)));
        assertEquals(List.of(
                "pos.sign_on(rig.cashier.id, rig.cashier.resolve_password())",
                "pos.item_panel.select_with_quantity(9999, 2)",
                "pos.item_panel.pay_credit()",
                "pos.item_panel.select(MOP.CASH)",
                "pos.void_ticket()",
                "pos.sign_off()",
                "pos.express_panel.button(2).tap()"), codes(record(tmp.resolve("r.ndjson"), taps)));
    }

    @Test
    void theKeypadIconPluCashAndTotalRules() throws Exception {
        List<Map<String, Object>> taps = new ArrayList<>();
        taps.add(tap(Locators.EXPRESS_PANEL_VIEW_KEYPAD_ICON));
        taps.addAll(entry(Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK, "9999", null));
        taps.add(tap(null, null, List.of("Total")));
        taps.add(tap(ITEM.at(145397)));
        taps.add(tap(ITEM.at(145394)));
        taps.addAll(entry(VFI_KEY, VFI_OK, "1100", null));
        assertEquals(List.of("pos.key_plu(9999)", "pos.item_panel.total()", "pos.item_panel.pay_cash(\"1100\")"),
                codes(record(tmp.resolve("r.ndjson"), taps)));
    }

    @Test
    void aMappedTapWithNoRuleIsACatalogueTapByName() throws Exception {
        Locator other = Locators.RECEIPT_VIEW_ITEM_LINES_CONTAINER;
        List<Map<String, Object>> taps = new ArrayList<>();
        taps.add(tap(other));
        taps.addAll(entry(VFI_KEY, VFI_OK, "05", null));
        assertEquals(List.of("pos.driver.tap(locators." + other.name() + ")", "pos.keypad.enter(\"05\")"),
                codes(record(tmp.resolve("r.ndjson"), taps)));
    }

    @Test
    void unresolvedStepsAreLoudAndNeverWriteTheObservedId() throws Exception {
        List<Map<String, Object>> taps = new ArrayList<>();
        taps.add(tap(ITEM.at(9999)));
        taps.add(tap("RecorderTest_Unknown"));
        taps.addAll(entry(VFI_KEY, VFI_OK, "4321", PASSWORD_PROMPT)); // a PIN, but no sign-on
        Map<String, Object> inferred = new LinkedHashMap<>();
        inferred.put("type", "inferred");
        inferred.put("seq", 9);
        inferred.put("device", "pinpad");
        Recording recording = Recording.read(record(tmp.resolve("r.ndjson"), taps, "rig", "CSR Func",
                List.of(inferred), true));
        List<Collapse.Step> steps = Collapse.collapse(recording);
        assertEquals(List.of(true, false, false, false), steps.stream().map(Collapse.Step::ok).toList());
        String feature = com.verifone.cat.authoring.Recorded.recordingFeature(recording, "T", "F", "PETROCORE-1");
        assertFalse(feature.contains("RecorderTest_Unknown"), feature);
        assertTrue(feature.contains("# NOT RECORDED: tap 2 — unmapped (no catalogue entry has this id)"), feature);
        assertTrue(feature.contains("And the recorded taps 3-7 could not be mapped"), feature);
        assertTrue(feature.contains("And the recorded tap 9 could not be mapped"), feature);
        assertFalse(Check.analyse(feature, "PETROCORE-1-TestCase.feature").ok(), "the checker refuses them");
    }

    // -- generate: names, collisions, baselines ----------------------------------

    @Test
    void theXrayKeyNamesTheCaseAndACollisionWritesNothing() throws Exception {
        Recording recording = Recording.read(record(tmp.resolve("r.ndjson"), List.of(tap(ITEM.at(9999)))));
        Path out = tmp.resolve("suite");
        Generate.Generated made = Generate.generate(recording, out, "test_one", "PETROCORE-9", "PETROCORE-424242", null,
                null);
        assertEquals(out.resolve("PETROCORE-424242-TestCase.feature"), made.caseFile());
        String before = Files.readString(made.caseFile());
        assertTrue(before.startsWith("@PETROCORE-424242\nFeature: PETROCORE-9 authored cases\n"), before);
        assertTrue(before.contains("  Scenario: PETROCORE-424242 — recorded: one\n"), before);
        assertTrue(before.contains("    Given the register is idle\n    When the cashier taps item 9999\n"), before);
        assertTrue(Check.analyse(before, "PETROCORE-424242-TestCase.feature").ok(),
                Check.analyse(before, "PETROCORE-424242-TestCase.feature").diagnostics().toString());
        Generate.GenerateException collision = assertThrows(Generate.GenerateException.class,
                () -> Generate.generate(recording, out, "test_two", null, "PETROCORE-424242", null, null));
        assertTrue(collision.getMessage().contains("D23"));
        assertEquals(before, Files.readString(made.caseFile()));
        record Bad(String name, String story, String key, Integer placeholder) {}
        for (Bad bad : List.of(new Bad("test_one", null, "PCS-1", null), new Bad("test_one", "PCS-1", null, null),
                new Bad("Test-One", null, null, null), new Bad("test_one", null, null, 0),
                new Bad("test_one", null, "PETROCORE-5", 1))) {
            assertThrows(Generate.GenerateException.class,
                    () -> Generate.generate(recording, out, bad.name(), bad.story(), bad.key(), bad.placeholder(), null),
                    bad.toString());
        }
    }

    @Test
    void placeholdersNumberASessionsCasesInOrder() throws Exception {
        Recording recording = Recording.read(record(tmp.resolve("r.ndjson"), List.of(tap(ITEM.at(9999)))));
        Path out = tmp.resolve("suite");
        List<String> names = new ArrayList<>();
        int n = 0;
        for (String name : List.of("test_cash", "test_card", "test_void")) {
            n++;
            names.add(Generate.generate(recording, out, name, "PETROCORE-9", null, n, null).caseFile().getFileName()
                    .toString());
        }
        assertEquals(List.of("PETROCORE-1-TestCase.feature", "PETROCORE-2-TestCase.feature",
                "PETROCORE-3-TestCase.feature"), names);
        String second = Files.readString(out.resolve("PETROCORE-2-TestCase.feature"));
        assertTrue(second.startsWith("@PETROCORE-2\n"), second);
        assertTrue(second.contains("Scenario: Placeholder 2 — recorded: card"), second);
    }

    private static List<Map<String, Object>> baselines(boolean approved) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("type", "baseline");
        receipt.put("kind", "receipt");
        receipt.put("content", "ITEM F  1  9.99");
        receipt.put("approved", approved);
        Map<String, Object> tlog = new LinkedHashMap<>();
        tlog.put("type", "baseline");
        tlog.put("kind", "tlog");
        tlog.put("content", "<trans><a>1</a></trans>");
        tlog.put("approved", approved);
        return List.of(receipt, tlog);
    }

    @Test
    void approvedRigBaselinesLandWhereTheFeatureReadsThem() throws Exception {
        Path path = record(tmp.resolve("r.ndjson"), List.of(tap(ITEM.at(9999))), "rig", "CSR Func", baselines(true),
                true);
        Generate.Generated made = Generate.generate(Recording.read(path), tmp.resolve("suite"), "test_one", null,
                "PETROCORE-7", null, null);
        assertEquals(2, made.baselines().size());
        Path receipt = tmp.resolve("suite").resolve("PETROCORE-7-CATReceipt.txt");
        assertEquals(Normalisers.normaliseReceipt("ITEM F  1  9.99") + "\n", Files.readString(receipt));
        assertTrue(Files.isRegularFile(tmp.resolve("suite").resolve("PETROCORE-7-Tlog.xml")));
    }

    @Test
    void aFakePosOrUnapprovedBaselineIsRefusedAndNothingWritten() throws Exception {
        record Case(String kind, boolean approved, String why) {}
        for (Case c : List.of(new Case("fake_pos", true, "§3.5"), new Case("pos_double", true, "§3.5"),
                new Case("rig", false, "approved"))) {
            Path path = record(tmp.resolve(c.kind() + c.approved() + ".ndjson"), List.of(tap(ITEM.at(9999))), c.kind(),
                    "CSR Func", baselines(c.approved()), true);
            Path out = tmp.resolve("suite-" + c.kind() + c.approved());
            Generate.GenerateException e = assertThrows(Generate.GenerateException.class,
                    () -> Generate.generate(Recording.read(path), out, "test_one", null, null, null, null));
            assertTrue(e.getMessage().contains(c.why()), e.getMessage());
            assertFalse(Files.exists(out));
        }
    }

    // -- the commands ------------------------------------------------------------

    private record Ran(int code, String out, String err) {}

    private static Ran generate(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GenerateCommand().run(argv, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theGenerateCommandsExitCodes() throws Exception {
        Path clean = record(tmp.resolve("clean.ndjson"), List.of(tap(ITEM.at(9999))));
        Path dirty = record(tmp.resolve("dirty.ndjson"), List.of(tap("RecorderTest_Unknown")));
        String out = tmp.resolve("suite").toString();
        assertEquals(0, generate("generate", clean.toString(), "--out", out, "--name", "test_clean").code());
        assertEquals(2, generate(clean.toString(), "--out", out, "--name", "test_clean").code());
        Ran dirtyRun = generate(dirty.toString(), "--out", out, "--name", "test_dirty");
        assertEquals(1, dirtyRun.code());
        assertTrue(dirtyRun.err().contains("not generated"), dirtyRun.err());
        assertEquals(2, generate(clean.toString(), "--out", out, "--key", "PETROCORE-1", "--placeholder", "1").code());
    }

    private static Ran recordRun(RecordCommand command, String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = command.run(argv, Map.of("PETROTEST_RIGS_DIR", "does-not-exist.invalid"),
                new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false);
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theRecordCommandRefusesBeforeItConnects() throws Exception {
        RecordCommand never = new RecordCommand((url, stream, source, stop, interrupted, timeout, announce) -> {
            throw new AssertionError("must not connect");
        });
        Path existing = tmp.resolve("taken.ndjson");
        Files.writeString(existing, "");
        assertEquals(2, recordRun(never, "record", "--cdp", "http://localhost:9", "--out", existing.toString()).code());
        assertEquals(2, recordRun(never, "--out", tmp.resolve("x").toString()).code()); // neither --rig nor --cdp
        assertEquals(2, recordRun(never, "--rig", "nosuch", "--out", tmp.resolve("y").toString()).code());
    }

    @Test
    void eitherFakePosFlagNamesTheSourceAndTheRecordingIsWritten() throws Exception {
        for (String flag : List.of("--fake-pos", "--pos-double")) {
            List<Capture.Source> seen = new ArrayList<>();
            RecordCommand fake = new RecordCommand((url, stream, source, stop, interrupted, timeout, announce) -> {
                seen.add(source);
                assertTrue(stop.getAsBoolean() || true);
                return 0;
            });
            Path out = tmp.resolve("rec" + flag + ".ndjson");
            Ran ran = recordRun(fake, "--cdp", "http://127.0.0.1:9", "--out", out.toString(), flag);
            assertEquals(0, ran.code(), ran.err());
            assertEquals("fake_pos", seen.get(0).kind());
            assertTrue(ran.out().contains("recorded 0 tap(s) to " + out), ran.out());
        }
    }

    @Test
    void recordingWithNothingListeningFailsAndLeavesNoFile() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        Path out = tmp.resolve("none.ndjson");
        Ran ran = recordRun(new RecordCommand(), "--cdp", "http://127.0.0.1:" + port, "--out", out.toString());
        assertEquals(1, ran.code(), ran.err());
        assertTrue(ran.err().contains("could not attach over CDP"), ran.err());
        assertFalse(Files.exists(out));
    }

    @Test
    void urlPathKeepsThePathAloneNeverTheHost() {
        assertEquals("/pos/index.html", Capture.urlPath("http://register.invalid:8080/pos/index.html?x=1#y"));
        assertEquals("blank", Capture.urlPath("about:blank"));
        assertEquals("", Capture.urlPath("http://register.invalid"));
    }
}
