package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.RunScope;

/**
 * The replay gate: the written suite, the run it is handed to, the run's events folded, and the verdict, one refusal
 * reason at a time. Port of the Python's {@code test_cat_replay_gate.py} (its end-to-end replay through the real
 * launcher waits on the launch slice's suite handling: see the report). Evidence about this repository, not a rig.
 */
class ReplayGateTest {

    static final String NODE = "C:/generated/s/replay-1/PETROCORE-1-TestCase.feature";

    @TempDir
    Path tmp;

    private static final List<ReplayGate.Fingerprint> CURRENT = List.of(new ReplayGate.Fingerprint("case-1", "abc"));
    private static final Replay LAST = new Replay("s/replay-1",
            List.of(new Replay.ReplayCase("case-1", "sale", "abc", NODE, 1)), "r1", false);

    /** A run's {@code events.ndjson}: the run started (with {@code cmd}), the seven steps, and {@code tail}. */
    private ReplayRun run(String cmd, boolean fakePos, List<String> tail) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("{\"type\": \"run.started\", \"run_id\": \"r1\", \"rig\": \"lab\", \"cmd\": \"" + cmd
                + "\", \"fake_pos\": " + fakePos + "}");
        lines.addAll(tail);
        Path dir = tmp.resolve("results").resolve("r1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ReplayGate.EVENTS_FILE), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return ReplayGate.runState("r1", tmp.resolve("results"));
    }

    private static List<String> green(String outcome) {
        List<String> out = new ArrayList<>();
        for (String step : ReplayRun.STEP_NAMES) {
            out.add("{\"type\": \"step.entered\", \"step\": \"" + step + "\"}");
            out.add("{\"type\": \"step.finished\", \"step\": \"" + step + "\", \"state\": \"complete\", \"elapsed\": 1}");
        }
        out.add(4 * 2, "{\"type\": \"test.started\", \"nodeid\": \"" + NODE + "\"}");
        out.add(4 * 2 + 1, "{\"type\": \"test.finished\", \"nodeid\": \"" + NODE + "\", \"outcome\": \"" + outcome
                + "\", \"exc_type\": \"ValidationsFailed\", \"detail\": \"receipt differs\"}");
        out.add("{\"type\": \"run.finished\", \"run_id\": \"r1\", \"outcome\": \"passed\", \"cancelled\": false}");
        return out;
    }

    @Test
    void aCleanRigReplayPasses() throws Exception {
        ReplayGate.Verdict verdict = ReplayGate.judge(List.of(), CURRENT, LAST, run("cat-java launch", false,
                green("passed")));
        assertTrue(verdict.ok(), verdict.reasons().toString());
        assertEquals("r1", verdict.runId());
    }

    @Test
    void aWarningIsStillAPassAndAnOutlinesRowsCount() throws Exception {
        List<String> tail = green("repairing");
        assertTrue(ReplayGate.judge(List.of(), CURRENT, LAST, run("x", false, tail)).ok());
        List<String> rows = new ArrayList<>(green("passed"));
        rows.replaceAll(line -> line.replace(NODE + "\"", NODE.replace('/', '\\').replace("\\", "\\\\") + "[AMEX]\""));
        ReplayGate.Verdict outline = ReplayGate.judge(List.of(), CURRENT, LAST, run("x", false, rows));
        assertTrue(outline.ok(), outline.reasons().toString());
    }

    @Test
    void eachRefusalIsNamed() throws Exception {
        assertEquals(List.of("not replayed: ▶ Replay runs the cases as an ordinary run (§3.6)"),
                ReplayGate.judge(List.of(), CURRENT, null, null).reasons());
        assertEquals(List.of("no story ticket (D17)", "not replayed: ▶ Replay runs the cases as an ordinary run (§3.6)"),
                ReplayGate.judge(List.of("no story ticket (D17)"), CURRENT, LAST.withRunId(""), null).reasons());
        assertEquals(List.of("run r1 captured baselines, so it is not a verdict: approve them, then ▶ Replay"),
                ReplayGate.judge(List.of(), CURRENT, LAST, run("cat-java launch --snapshot-update", false,
                        green("passed"))).reasons());
        assertEquals(List.of("the cases changed after replay r1; replay them again"),
                ReplayGate.judge(List.of(), List.of(new ReplayGate.Fingerprint("case-1", "new")), LAST,
                        run("x", false, green("passed"))).reasons());
        assertEquals(List.of("replay r1 has no readable events.ndjson"),
                ReplayGate.judge(List.of(), CURRENT, LAST, null).reasons());
        assertEquals(List.of("replay r1 ran on the fake POS: evidence about this repository, not a rig"),
                ReplayGate.judge(List.of(), CURRENT, LAST, run("x", true, green("passed"))).reasons());
        assertEquals(List.of("sale: failed in replay r1 — ValidationsFailed: receipt differs"),
                ReplayGate.judge(List.of(), CURRENT, LAST, run("x", false, green("failed"))).reasons());
    }

    @Test
    void anUnfinishedRunAFailedStepARecoveryAndAMissingCaseAreRefused() throws Exception {
        List<String> tail = new ArrayList<>(List.of(
                "{\"type\": \"step.finished\", \"step\": \"Commander Configuration\", \"state\": \"failed\", "
                        + "\"detail\": \"close-day refused\"}",
                "{\"type\": \"recovery.rung\", \"nodeid\": \"" + NODE + "\", \"rung\": 2, \"outcome\": \"passed\"}"));
        ReplayGate.Verdict verdict = ReplayGate.judge(List.of(), CURRENT, LAST, run("x", false, tail));
        assertEquals(List.of("replay r1 never finished",
                "replay r1: Commander Configuration failed: close-day refused",
                "sale: never ran in replay r1",
                "replay r1: a recovery fired for " + NODE + " (rung 2, passed)"), verdict.reasons());
        List<String> stopped = new ArrayList<>(List.of("{\"type\": \"test.started\", \"nodeid\": \"" + NODE + "\"}",
                "{\"type\": \"run.finished\", \"outcome\": \"failed\", \"cancelled\": true}"));
        assertEquals(List.of("replay r1 was stopped before it finished",
                "sale: did not finish in replay r1 (an error in setup?)"),
                ReplayGate.judge(List.of(), CURRENT, LAST, run("x", false, stopped)).reasons());
    }

    @Test
    void anUnreadableRunIsNull() {
        assertNull(ReplayGate.runState("nosuch", tmp));
    }

    // -- write_suite and the run it is handed to ---------------------------------------

    private Session session(String steps) {
        Session session = Session.create(tmp.resolve("sessions"));
        Case c = session.insert(0, "Cash sale");
        session.writeText(c, "@PETROCORE-1\nFeature: x\n\n  Scenario: Cash sale\n" + steps);
        return session;
    }

    @Test
    void theSuiteIsWrittenInANewDirectoryWithItsApprovedBaselines() throws Exception {
        Session session = session(SessionTest.GOOD_STEPS);
        Case c = session.cases().get(0);
        Path folder = session.baselinesDir(c);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("CASE-CATReceipt.txt"), "TOTAL\n");
        session.approveBaselines(c);
        Path root = tmp.resolve("generated");

        Replay first = ReplayGate.writeSuite(session, root, false);
        Replay capture = ReplayGate.writeSuite(session, root, true);
        Replay second = ReplayGate.writeSuite(session, root, false);

        assertEquals(session.slug() + "/replay-1", first.directory());
        assertEquals(session.slug() + "/capture-1", capture.directory());
        assertEquals(session.slug() + "/replay-2", second.directory());
        Path written = root.resolve(first.directory());
        assertTrue(Files.readString(written.resolve("PETROCORE-1-TestCase.feature")).startsWith("@PETROCORE-1\n"));
        assertEquals("TOTAL\n", Files.readString(written.resolve("PETROCORE-1-CATReceipt.txt")));
        assertFalse(Files.exists(root.resolve(capture.directory()).resolve("PETROCORE-1-CATReceipt.txt")),
                "a capture writes no baseline");
        Replay.ReplayCase entry = first.cases().get(0);
        assertEquals(List.of(new ReplayGate.Fingerprint(entry.caseId(), entry.digest())), ReplayGate.fingerprint(session));
        assertEquals(List.of(written.resolve("PETROCORE-1-TestCase.feature").toAbsolutePath().toString()),
                first.suites());

        LaunchConfig base = new LaunchConfig.Builder("lab").upgradeMode(UpgradeMode.SKIP).xrayReport(true)
                .dist("buypass").build();
        LaunchConfig config = ReplayGate.replayConfig(base, capture);
        assertEquals(capture.suites(), config.suites());
        assertEquals(List.of(), config.testPlans());
        assertTrue(config.snapshotUpdate());
        assertTrue(config.xrayReport());
        assertEquals(RunScope.Mode.CASES, config.scope().mode());
        assertEquals(List.of("PETROCORE-1"), config.scope().cases());
        assertFalse(ReplayGate.replayConfig(base, first).snapshotUpdate());
    }

    @Test
    void aCaseTheCheckerRefusesIsNeverWritten() {
        Session session = session("    Given the cashier dances\n");
        SessionException e = assertThrows(SessionException.class,
                () -> ReplayGate.writeSuite(session, tmp.resolve("generated"), false));
        assertTrue(e.getMessage().startsWith("fix the cases first: Cash sale: line 5"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("generated")));
        assertEquals("no case to replay", assertThrows(SessionException.class,
                () -> ReplayGate.writeSuite(Session.create(tmp.resolve("empty")), tmp, false)).getMessage());
    }

    @Test
    void aCaptureIsImportedUnapprovedAndTheReplayIsKeptInTheSession() throws Exception {
        Session session = session(SessionTest.GOOD_STEPS);
        Path root = tmp.resolve("generated");
        Replay capture = ReplayGate.writeSuite(session, root, true).withRunId("c1");
        Path dir = root.resolve(capture.directory());
        Files.writeString(dir.resolve("PETROCORE-1-CATReceipt.txt"), "R\n");
        Files.writeString(dir.resolve("PETROCORE-1-Tlog.xml"), "<t/>\n");

        assertEquals(2, session.importCapture(root, capture));
        CaseReview review = session.review(session.cases().get(0));
        assertEquals(2, review.baselines().size());
        assertFalse(review.approved());
        assertEquals("c1", review.capturedIn());

        Replay replayed = ReplayGate.writeSuite(session, root, false).withRunId("r9");
        session.replay(replayed);
        Session again = Session.load(session.directory());
        assertEquals(replayed, again.replay());
        assertEquals(Map.of("directory", replayed.directory()).get("directory"), again.replay().directory());
    }
}
