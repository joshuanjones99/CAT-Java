package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.authoring.Case;
import com.verifone.cat.authoring.Draft;
import com.verifone.cat.authoring.Replay;
import com.verifone.cat.authoring.Session;
import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.RunScope;

/**
 * The Authoring page's state through Capture, Approve, Replay and the verdict, and Draft with Claude, against a fake run
 * service that writes the run's {@code events.ndjson} as a run would and a fake drafting model. Port of the flows the
 * Python's {@code test_cat_web_authoring.py} and {@code test_cat_replay_gate.py} drive. Nothing reaches a rig, Jira or
 * Claude; evidence about this repository only.
 */
class AuthoringServiceTest {

    static final String GOOD_STEPS = "    Given the register is idle\n"
            + "    When the cashier rings up PLU 9999\n"
            + "    And the cashier totals the sale\n"
            + "    And the customer pays 11.00 in cash\n"
            + "    Then the receipt matches its baseline\n";

    @TempDir
    Path tmp;

    /** A run service that takes one run, and ends it when told, writing what the run "printed". */
    final class FakeRuns implements AuthoringRuns {
        final List<LaunchConfig> started = new ArrayList<>();
        boolean busy;
        String runId;
        boolean fakePos;

        @Override
        public LaunchConfig baseConfig() {
            return new LaunchConfig.Builder("lab").upgradeMode(UpgradeMode.SKIP).fakePos(fakePos).build();
        }

        @Override
        public String start(LaunchConfig config) {
            if (busy) {
                return "a run is already in progress";
            }
            started.add(config);
            busy = true;
            return null;
        }

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public String lastRunId() {
            return runId;
        }

        @Override
        public Path resultsRoot() {
            return tmp.resolve("results");
        }

        /** Ends the run as {@code id}, each written case with {@code outcome}; a capture leaves a receipt beside it. */
        void finish(String id, String outcome) throws Exception {
            LaunchConfig config = started.get(started.size() - 1);
            List<String> lines = new ArrayList<>();
            lines.add("{\"type\": \"run.started\", \"run_id\": \"" + id + "\", \"cmd\": \""
                    + String.join(" ", config.snapshotUpdate() ? List.of("launch", "--snapshot-update") : List.of("launch"))
                    + "\", \"fake_pos\": false}");
            for (String suite : config.suites()) {
                String node = suite.replace("\\", "\\\\");
                lines.add("{\"type\": \"test.started\", \"nodeid\": \"" + node + "\"}");
                lines.add("{\"type\": \"test.finished\", \"nodeid\": \"" + node + "\", \"outcome\": \"" + outcome + "\"}");
                if (config.snapshotUpdate()) {
                    Path written = Path.of(suite);
                    String key = written.getFileName().toString().replace("-TestCase.feature", "");
                    Files.writeString(written.resolveSibling(key + "-CATReceipt.txt"), "TOTAL 11.00\n");
                }
            }
            lines.add("{\"type\": \"run.finished\", \"run_id\": \"" + id + "\", \"outcome\": \"passed\", "
                    + "\"cancelled\": false}");
            Path dir = tmp.resolve("results").resolve(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("events.ndjson"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            runId = id;
            busy = false;
        }
    }

    private AuthoringService service(FakeRuns runs, Draft.Asker asker) {
        return new AuthoringService(tmp.resolve("sessions"), tmp.resolve("generated"), () -> {
            throw new AssertionError("Jira must not be asked");
        }, runs, asker, Runnable::run);
    }

    private static void act(AuthoringService service, AuthoringService.Action action) {
        service.act(action);
    }

    private static Case aGoodCase(AuthoringService service) {
        service.act(() -> service.newCase("Cash sale"));
        Case c = service.selected();
        service.saveDraft(c.id(), "@PETROCORE-1\nFeature: x\n\n  Scenario: Cash sale\n" + GOOD_STEPS);
        return c;
    }

    @Test
    void captureApproveReplayThenTheGatePassesAndPublishShowsThePlan() throws Exception {
        FakeRuns runs = new FakeRuns();
        AuthoringService service = service(runs, null);
        aGoodCase(service);

        act(service, service::capture);
        assertTrue(service.message().startsWith("capturing baselines for 1 case(s) from "), service.message());
        assertTrue(service.replaying());
        assertTrue(runs.started.get(0).snapshotUpdate());
        assertEquals(RunScope.Mode.CASES, runs.started.get(0).scope().mode());
        assertTrue(service.view(AuthoringPage::workbench).contains("Open the Run page"));
        act(service, service::replay);
        assertEquals("✖ a replay is already running", service.message());

        runs.finish("c1", "passed");
        service.poll();
        assertFalse(service.replaying());
        assertTrue(service.message().startsWith("capture c1 kept 1 baseline file(s)"), service.message());
        assertNull(service.session().replay(), "a capture is never a verdict");

        act(service, service::approve);
        act(service, service::replay);
        assertFalse(runs.started.get(1).snapshotUpdate());
        runs.finish("r1", "passed");
        String html = service.view(AuthoringPage::workbench);
        // The story ticket is left unset so the dry run never reads a petroapps checkout: that is the one reason left.
        assertEquals("✖ refused after replay r1: no story ticket (D17)", service.message());
        assertEquals(List.of("no story ticket (D17)"), service.verdict().reasons());
        Replay kept = Session.load(service.session().directory()).replay();
        assertEquals("r1", kept.runId());

        act(service, service::publish);
        assertTrue(service.publishLines().get(1).equals("A publish would be REFUSED (1 reason(s)):")
                , service.publishLines().toString());
        assertFalse(service.publishLines().stream().anyMatch(l -> l.contains("not replayed")),
                service.publishLines().toString());
        assertTrue(html.contains("Session "), html);
    }

    @Test
    void aFailedReplayIsRefusedAndAnEditAfterItAsksForAnother() throws Exception {
        FakeRuns runs = new FakeRuns();
        AuthoringService service = service(runs, null);
        Case c = aGoodCase(service);
        act(service, service::replay);
        runs.finish("r2", "failed");
        service.poll();
        assertTrue(service.message().startsWith("✖ refused after replay r2: "), service.message());
        assertTrue(service.verdict().reasons().contains("Cash sale: failed in replay r2"),
                service.verdict().reasons().toString());
        service.saveDraft(c.id(), "@PETROCORE-1\nFeature: x\n\n  Scenario: Cash sale\n" + GOOD_STEPS + "    # edited\n");
        assertTrue(service.verdict().reasons().contains("the cases changed after replay r2; replay them again"));
    }

    @Test
    void aFakePosCaptureIsRefusedAndARefusedStartWritesNoSuite() {
        FakeRuns runs = new FakeRuns();
        runs.fakePos = true;
        AuthoringService service = service(runs, null);
        aGoodCase(service);
        act(service, service::capture);
        assertEquals("✖ baselines are only ever captured on a rig, never the fake POS (§3.5)", service.message());
        runs.fakePos = false;
        runs.busy = true;
        act(service, service::replay);
        assertEquals("✖ a run is in progress; wait for it to finish", service.message());
        assertFalse(Files.exists(tmp.resolve("generated")));
    }

    @Test
    void draftWithClaudeAddsTheDraftsAndReportsTheCost() throws Exception {
        Draft.Asker asker = prompt -> new Draft.ClaudeAnswer(Map.of(
                "cases", List.of(Map.of("title", "Cash sale accepts the round-up",
                        "steps", List.of("Given the register is idle", "When the cashier rings up PLU 9999"))),
                "not_drafted", List.of(Map.of("title", "Loyalty", "missing_phrases", List.of("a loyalty phrase"))),
                "notes", "first try"), 0.25, "claude-test-model");
        AuthoringService service = service(new FakeRuns(), asker);
        act(service, service::draft);
        assertEquals("✖ ingest a ticket first: Draft works from the session's spec", service.message());
        Session session = service.session();
        session.setSpecTicket("PETROCORE-5");
        Files.writeString(session.specPath(), "# PETROCORE-5: Round up\n");

        act(service, service::draft);

        assertEquals("Claude drafted 1 case(s), 1 need a new phrase ($0.25) — review every step before replaying",
                service.message());
        assertEquals(List.of("drafted"), session.cases().stream().map(Case::source).toList());
        assertEquals(AuthoringService.TAB_REVIEW, service.tab());
        String review = service.view(AuthoringPage::workbench);
        assertTrue(review.contains("not drafted: Loyalty — needs a phrase: a loyalty phrase"), review);
        assertTrue(review.contains("1 case(s), 1 clean, 1 round(s), claude-test-model, $0.25"), review);
        assertFalse(service.waiting());
    }
}
