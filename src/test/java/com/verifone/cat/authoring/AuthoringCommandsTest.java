package com.verifone.cat.authoring;

import static com.verifone.cat.recorder.RecordingFixture.tap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.recorder.RecordingFixture;

/**
 * {@code cat-java authoring} and {@code cat-java steps}, and a recording adopted as a case's draft. Ports of the
 * Python's {@code test_the_cli_adds_and_shows_cases} and the {@code steps} CLI tests. Every session is under a temp
 * folder; nothing reaches Jira or Claude.
 */
class AuthoringCommandsTest {

    @TempDir
    Path tmp;

    private record Ran(int code, String out, String err) {}

    private Ran authoring(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new AuthoringCommand(tmp.resolve("sessions"), () -> {
            throw new AssertionError("Jira must not be asked");
        }, prompt -> {
            throw new AssertionError("Claude must not be asked");
        }).run(argv, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Ran steps(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new StepsCommand().run(argv, Map.of("CAT_PYTHON_ROOT", tmp.resolve("python").toString()),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theAuthoringCommandAddsAndShowsCases() throws Exception {
        Session session = Session.create(tmp.resolve("sessions"));
        Path draft = tmp.resolve("d.feature");
        Files.writeString(draft, "Feature: x\n\n  Scenario: Cash sale\n" + SessionTest.GOOD_STEPS);
        String dir = session.directory().toString();
        assertEquals(0, authoring("authoring", "--session", dir, "add", "--title", "Cash sale", "--file",
                draft.toString()).code());
        Files.writeString(draft, "Feature: x\n\n  Scenario: Bad\n    Given the cashier dances\n");
        assertEquals(1, authoring("--session", dir, "add", "--title", "Bad", "--file", draft.toString()).code());
        Ran shown = authoring("--session", dir, "show");
        assertEquals(1, shown.code());
        assertTrue(shown.out().contains("1. Cash sale [drafted] — ok"), shown.out());
        assertTrue(shown.out().contains("2. Bad [drafted] — 1 error(s)"), shown.out());
    }

    @Test
    void theAuthoringCommandsUsageAndDraftRefusals() {
        assertEquals(2, authoring("show", "--source", "nonsense").code());
        assertEquals(2, authoring().code());
        Ran draft = authoring("--session", "new", "draft");
        assertEquals(2, draft.code());
        assertTrue(draft.err().contains("ingest a ticket first"), draft.err());
        Ran shown = authoring("show");
        assertEquals(0, shown.code());
        assertTrue(shown.out().contains("0 case(s)"), shown.out());
    }

    @Test
    void stepsListWritesEveryFormatAndToAFile() throws Exception {
        Ran text = steps("steps", "list");
        assertEquals(0, text.code());
        assertTrue(text.out().contains("== POS screen ==") && text.out().contains("Arguments:"), text.out());
        Ran json = steps("list", "--format", "json");
        assertTrue(new ObjectMapper().readTree(json.out()).get("steps").size() > 40);
        Path file = tmp.resolve("catalogue.md");
        assertEquals(0, steps("list", "--format", "markdown", "--out", file.toString()).code());
        assertEquals(Catalogue.renderMarkdown(), Files.readString(file));
        assertEquals(2, steps("list", "--format", "html").code());
    }

    @Test
    void stepsCheckExitsOneOnAnErrorTwoOnAMissingPathAndSpeaksJson() throws Exception {
        Path suite = tmp.resolve("python").resolve("tests").resolve("suites");
        Files.createDirectories(suite);
        Files.writeString(suite.resolve("PETROCORE-1-TestCase.feature"),
                "@PETROCORE-1\nFeature: x\n\n  Scenario: Cash sale\n" + SessionTest.GOOD_STEPS);
        Ran clean = steps("check");
        assertEquals(0, clean.code(), clean.out());
        assertTrue(clean.out().contains("1 file(s): 0 error(s)"), clean.out());
        Files.writeString(suite.resolve("PETROCORE-2-TestCase.feature"),
                "@PETROCORE-2\nFeature: x\n\n  Scenario: Bad\n    Given the cashier dances\n");
        Ran bad = steps("check", suite.toString());
        assertEquals(1, bad.code());
        assertTrue(bad.out().contains("PETROCORE-2-TestCase.feature:5: error"), bad.out());
        Ran json = steps("check", "--json", suite.toString());
        assertEquals(1, json.code());
        assertTrue(new ObjectMapper().readTree(json.out()).size() == 2, json.out());
        assertEquals(2, steps("check", tmp.resolve("nowhere").toString()).code());
    }

    @Test
    void aRecordingIsAdoptedAsTheCasesDraft() throws Exception {
        Session session = Session.create(tmp.resolve("sessions"));
        session.setTicket("PETROCORE-900");
        Case c = session.insert(0, "Recorded sale", "recorded", null);
        RecordingFixture.record(session.directory().resolve(c.id() + ".ndjson"),
                List.of(tap(Locators.ITEM_PANEL_N.at(9999))));

        session.adoptRecording(c, c.id() + ".ndjson");

        CaseReview review = session.review(c);
        assertEquals("recorded", c.source());
        assertEquals(session.directory().resolve(c.id() + ".ndjson"), session.recordingPath(c));
        assertTrue(review.text().contains("Feature: PETROCORE-900 authored cases"), review.text());
        assertTrue(review.text().contains("When the cashier taps item 9999"), review.text());
        assertTrue(review.analysis().ok(), review.analysis().diagnostics().toString());
        assertTrue(review.recording() != null && !review.fakePos());
        Session again = Session.load(session.directory());
        assertEquals(c.id() + ".ndjson", again.cases().get(0).recording());

        Files.writeString(session.recordingPath(c), "not a recording\n");
        List<String> blockers = session.review(c).replayBlockers();
        assertTrue(blockers.get(blockers.size() - 1).startsWith("Recorded sale: its recording is unreadable — "),
                blockers.toString());
    }

    @Test
    void aFakePosOrCutOffRecordingBlocksPublishing() throws Exception {
        Session session = Session.create(tmp.resolve("sessions"));
        Case c = session.insert(0, "Recorded sale", "recorded", null);
        RecordingFixture.record(session.directory().resolve("r.ndjson"), List.of(tap(Locators.ITEM_PANEL_N.at(9999))),
                "fake_pos", "CSR Func", null, false);
        session.adoptRecording(c, "r.ndjson");
        List<String> blockers = session.review(c).publishBlockers();
        assertTrue(blockers.contains("Recorded sale: recorded on the fake POS, not a rig (§3.5)"), blockers.toString());
        assertTrue(blockers.contains("Recorded sale: the recording was cut off (no footer)"), blockers.toString());
    }
}
