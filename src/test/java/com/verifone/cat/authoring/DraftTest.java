package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.authoring.Draft.DraftException;
import com.verifone.cat.authoring.Draft.DraftResult;

/**
 * Drafting through a stand-in for the {@code claude} CLI ({@link FakeClaude}), run as a real process through a
 * {@code .cmd} file, as npm's {@code claude.cmd} is: the argument quoting, stdin, the envelope and the repair loop are
 * exercised end to end. Port of the drafting half of the Python's {@code test_cat_authoring_backends.py}. Never a
 * model; evidence about this repository only.
 */
class DraftTest {

    @TempDir
    Path tmp;

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** A {@code claude.cmd} that runs {@link FakeClaude} in {@code mode} with this JVM and classpath. */
    private Path standIn(String mode) throws Exception {
        assumeTrue(windows(), "the stand-in is a .cmd file");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path argfile = tmp.resolve("fake-claude-" + mode + ".args");
        String classpath = System.getProperty("java.class.path").replace('\\', '/');
        Files.writeString(argfile, "-cp \"" + classpath + "\" " + FakeClaude.class.getName() + " " + mode + "\n");
        Path cmd = tmp.resolve("claude-" + mode + ".cmd");
        Files.writeString(cmd, "@\"" + java + "\" @\"" + argfile + "\" %*\r\n", StandardCharsets.US_ASCII);
        return cmd;
    }

    private Draft.Asker asker(Path standIn) {
        List<String> command = Draft.claudeCommand(Map.of("CAT_CLAUDE", standIn.toString()));
        return prompt -> Draft.runClaude(command, prompt, null, null, Draft.TIMEOUT, tmp.resolve("tmp"));
    }

    private Session sessionWithSpec() throws Exception {
        Session session = Session.create(tmp.resolve("s"));
        session.setSpecTicket("PETROCORE-5");
        Files.writeString(session.specPath(), "# PETROCORE-5: Round up for charity on cash\n\nOffer a donation.\n");
        return session;
    }

    @Test
    void draftAsksChecksRepairsAndAddsTheCases() throws Exception {
        Session session = sessionWithSpec();
        List<String> progress = new ArrayList<>();

        DraftResult result = Actions.draftInto(session, progress::add, asker(standIn("good")));

        assertEquals(2, result.rounds, "the invented phrase went back once");
        assertEquals(List.of("Cash sale accepts the round-up", "Card sale - declines"),
                result.clean().stream().map(Draft.Drafted::title).toList());
        assertEquals(0.5, result.costUsd, 1e-9);
        assertEquals("claude-test-model", result.model);
        assertEquals(List.of("the customer identifies with loyalty ID {id}"),
                result.notDrafted.get(0).get("missing_phrases"));
        assertEquals(List.of("drafted", "drafted"), session.cases().stream().map(Case::source).toList());
        CaseReview review = session.review(session.cases().get(0));
        assertTrue(review.analysis().ok(), review.analysis().diagnostics().toString());
        assertTrue(review.text().contains("# Drafted by Claude from PETROCORE-5"), review.text());
        assertTrue(review.text().contains("# Covers: AC 1"), review.text());
        JsonNode transcript = new ObjectMapper().readTree(Files.readString(result.transcript));
        assertEquals(2, transcript.get("exchanges").size());
        assertTrue(transcript.get("exchanges").get(0).get("prompt").asText().contains("<specification>"));
        assertTrue(progress.stream().anyMatch(line -> line.contains("checker refused 1 case")), progress.toString());
        assertTrue(Files.list(tmp.resolve("tmp")).findAny().isEmpty(), "Claude's working directory is removed");
    }

    @Test
    void draftNeedsASpec() {
        DraftException e = assertThrows(DraftException.class,
                () -> Actions.draftInto(Session.create(tmp.resolve("s")), line -> { }, prompt -> {
                    throw new AssertionError("Claude must not be asked");
                }));
        assertTrue(e.getMessage().contains("ingest a ticket first"), e.getMessage());
    }

    @Test
    void claudeErrorsAreReportedNotRaisedRaw() throws Exception {
        DraftException loggedOut = assertThrows(DraftException.class, () -> asker(standIn("error")).ask("x"));
        assertTrue(loggedOut.getMessage().contains("Not logged in"), loggedOut.getMessage());
        DraftException notJson = assertThrows(DraftException.class, () -> asker(standIn("notjson")).ask("x"));
        assertTrue(notJson.getMessage().contains("exited 3"), notJson.getMessage());
        DraftException missing = assertThrows(DraftException.class,
                () -> Draft.claudeCommand(Map.of("CAT_CLAUDE", "", "PATH", tmp.resolve("nowhere").toString())));
        assertTrue(missing.getMessage().contains("not installed"), missing.getMessage());
    }

    @Test
    void anEnvelopeWithoutStructuredOutputIsReadFromItsFencedResult() {
        Draft.ClaudeAnswer answer = Draft.parseEnvelope(
                "{\"result\": \"```json\\n{\\\"cases\\\": [], \\\"not_drafted\\\": []}\\n```\"}", "", 0);
        assertEquals(List.of(), answer.answer().get("cases"));
        assertThrows(DraftException.class, () -> Draft.parseEnvelope("{\"result\": \"no\"}", "", 0));
    }

    @Test
    void windowsArgumentsAreEscapedAsList2cmdlineEscapesThem() {
        assertEquals("\"\"", Draft.windowsArg(""));
        assertEquals("plain", Draft.windowsArg("plain"));
        assertEquals("{\\\"a\\\":1}", Draft.windowsArg("{\"a\":1}"));
        assertEquals("x\\\\\\\"y", Draft.windowsArg("x\\\"y"));
    }

    @Test
    void theRulesAreThePythonsSharedFile() {
        assertTrue(Draft.rules().startsWith("# Drafting authored cases for CAT\n"), Draft.rules());
        assertTrue(Draft.g(2.5).equals("2.5") && Draft.g(600).equals("600"));
    }
}
