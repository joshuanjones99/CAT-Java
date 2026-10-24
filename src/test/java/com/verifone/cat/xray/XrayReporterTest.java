package com.verifone.cat.xray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The reporter against a loopback Xray and Jira, ported from the Python CAT's reporter tests. */
class XrayReporterTest {

    private static final String PLAN = Events.PLAN;
    private static final String NODE_61396 = Events.node("PETROCORE-61396");
    private static final String NODE_37043 = Events.node("PETROCORE-37043");

    @TempDir
    Path tmp;

    private Path run() {
        return tmp.resolve("run");
    }

    private static List<ObjectNode> prefix(List<ObjectNode> events, int count) {
        return events.subList(0, count);
    }

    @Test
    void theExecutionIsCreatedAtPlanResolvedWithEveryCaseTodoInPlanOrder() {
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            reporter.feed(Events.runStarted());
            assertTrue(fake.imports().isEmpty());
            reporter.feed(Events.planResolved(1));

            JsonNode create = fake.imports().get(0);
            assertEquals(PLAN, create.at("/info/testPlanKey").asText());
            assertEquals("PETROCORE", create.at("/info/project").asText());
            assertFalse(create.at("/info").has("testEnvironments"));
            assertTrue(create.at("/info/summary").asText().startsWith("Automation Test Execution - "));
            assertTrue(create.at("/info/summary").asText().contains(PLAN));
            assertEquals(List.of("PETROCORE-61396", "PETROCORE-37043", "PETROCORE-61334", "PETROCORE-99999"),
                    keys(create.get("tests")));
            for (JsonNode test : create.get("tests")) {
                assertEquals("TODO", test.get("status").asText());
            }
            assertEquals("PETROCORE-900001", reporter.executions().get(PLAN).key);
            // The reporter is the token's own user, set right after creation.
            assertTrue(fake.seen.stream().anyMatch(r -> r.method().equals("PUT")
                    && r.path().endsWith("/issue/PETROCORE-900001")
                    && r.body().equals("{\"fields\":{\"reporter\":{\"accountId\":\"712020:abc\"}}}")));
            assertTrue(fake.seen.stream().anyMatch(r -> r.method().equals("POST")
                    && r.path().endsWith("/rest/api/3/issue/PETROCORE-900001/transitions")
                    && r.body().equals("{\"transition\":{\"id\":\"11\"}}")));
            // The key is on disk at once, for CAT's Generate Report step to read.
            ObjectNode status = XrayReporter.readStatus(run());
            assertEquals("PETROCORE-900001", status.at("/executions/" + PLAN + "/key").asText());
            assertTrue(status.at("/executions/" + PLAN + "/url").asText().endsWith("/browse/PETROCORE-900001"));
        }
    }

    @Test
    void aStartedCaseIsExecutingAndAFinishedOneGetsItsVerdict() {
        try (XrayFake fake = new XrayFake()) {
            double[] now = {0.0};
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake), now, null);
            List<ObjectNode> stream = Events.stream();
            for (ObjectNode event : prefix(stream, 3)) {
                reporter.feed(event);
            }
            assertEquals(1, reporter.flush(true));
            assertEquals(List.of("TODO", "EXECUTING"), fake.statuses("PETROCORE-61396"));

            reporter.feed(stream.get(3));
            assertEquals(1, reporter.flush(true));
            JsonNode last = lastTest(fake);
            assertEquals("PASSED", last.get("status").asText());
            assertEquals("Passed in 2 s", last.get("comment").asText());
            assertEquals("2026-10-07T10:00:00+00:00", last.get("start").asText());
            assertEquals("2026-10-07T10:01:00+00:00", last.get("finish").asText());
            // Nothing changed, so nothing is sent again.
            assertEquals(0, reporter.flush(true));
        }
    }

    @Test
    void aFailureCarriesItsExceptionAndDetailAsTheComment() {
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            for (ObjectNode event : prefix(Events.stream(), 6)) {
                reporter.feed(event);
            }
            reporter.flush(true);
            String comment = null;
            for (JsonNode batch : fake.imports()) {
                for (JsonNode test : batch.get("tests")) {
                    if ("PETROCORE-37043".equals(test.get("testKey").asText()) && "FAILED".equals(test.get("status").asText())) {
                        comment = test.get("comment").asText();
                    }
                }
            }
            assertNotNull(comment);
            assertTrue(comment.startsWith("AssertionError: PETROCORE-37043: 1 validation(s) failed"), comment);
        }
    }

    @Test
    void theUnmigratedCaseIsNeverPushedPastTodo() {
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            for (ObjectNode event : Events.stream()) {
                reporter.feed(event);
            }
            reporter.finish();
            assertEquals(List.of("TODO"), fake.statuses("PETROCORE-99999"));
            assertEquals(List.of("TODO"), fake.statuses("PETROCORE-61334"));  // migrated, never reached
        }
    }

    @Test
    void flushesAreCoalescedByTheInterval() {
        try (XrayFake fake = new XrayFake()) {
            double[] now = {0.0};
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake), now, null);
            for (ObjectNode event : prefix(Events.stream(), 4)) {
                reporter.feed(event);
            }
            assertEquals(0, reporter.maybeFlush());
            now[0] = 6.0;
            assertEquals(1, reporter.maybeFlush());
        }
    }

    @Test
    void fiftyCasesARequest() {
        List<com.verifone.cat.plans.PlanCase> many = new ArrayList<>();
        for (int n = 1; n <= 120; n++) {
            many.add(new com.verifone.cat.plans.PlanCase("PETROCORE-" + n, "case " + n,
                    "tests/suites/x/PETROCORE-" + n + "-TestCase.py"));
        }
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = new XrayReporter(run(), Events.xrayClient(fake), Events.facts(), 5.0,
                    key -> many, key -> "", null, () -> 0.0);
            reporter.feed(Events.runStarted());
            reporter.feed(manyPlan(many));
            for (int i = 0; i < many.size(); i++) {
                reporter.feed(Events.finished(i, "tests/suites/x/PETROCORE-" + (i + 1) + "-TestCase.py::test_x", "passed"));
            }
            assertEquals(120, reporter.flush(true));
            List<Integer> sizes = new ArrayList<>();
            fake.imports().stream().skip(1).forEach(batch -> sizes.add(batch.get("tests").size()));
            assertEquals(List.of(50, 50, 20), sizes);
        }
    }

    private static ObjectNode manyPlan(List<com.verifone.cat.plans.PlanCase> many) {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (com.verifone.cat.plans.PlanCase planned : many) {
            cases.add(Map.of("key", planned.key(), "summary", planned.summary(), "node", planned.node()));
        }
        return Events.event(1, "plan.resolved", Map.of("cases", cases));
    }

    @Test
    void finishWritesTheSummaryAsDescriptionAndCommentThenCloses() throws Exception {
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            for (ObjectNode event : Events.stream()) {
                reporter.feed(event);
            }
            assertTrue(reporter.runOver());
            reporter.finish();

            String key = "PETROCORE-900001";
            JsonNode put = fake.requests("PUT", "/issue/" + key).stream()
                    .map(r -> Events.json(r.body()))
                    .filter(b -> b.has("fields") && b.at("/fields").has("description"))
                    .findFirst().orElseThrow();
            JsonNode description = put.at("/fields/description");
            assertEquals("doc", description.get("type").asText());
            assertEquals(1, description.get("version").asInt());
            String text = description.toString();
            assertTrue(text.contains("Charity Donations Indoor"));
            assertTrue(text.contains("1 of the 2 cases that ran passed"));
            assertTrue(text.contains("1 failed"));
            assertTrue(text.contains("1 case needs a look"));
            assertTrue(text.contains("PETROCORE-37043") && text.contains("Login"));
            assertTrue(text.contains("Software under test: Solutions1052"));
            JsonNode comment = Events.json(fake.requests("POST", "/issue/" + key + "/comment").get(0).body());
            assertTrue(comment.toString().contains("Run finished: 1 of 2 cases passed, 1 failed."));
            // Close, after the description and the comment.
            List<String> transitions = new ArrayList<>();
            fake.seen.forEach(r -> {
                if (r.method().equals("POST") && r.path().endsWith("/transitions")) {
                    transitions.add(r.body());
                }
            });
            assertEquals("{\"transition\":{\"id\":\"31\"}}", transitions.get(transitions.size() - 1));
            assertEquals(fake.seen.size() - 1, indexOfLastTransition(fake));

            String summary = Events.read(run().resolve(XrayReporter.SUMMARY_FILE));
            assertTrue(summary.startsWith("# Automated test run"));
            assertTrue(summary.contains("| PETROCORE-37043 | Login |"));
            assertTrue(summary.contains("receipt differs"));
            assertTrue(summary.contains("Run ID run1234567890."));
            assertTrue(summary.contains("## Where to look"));
            ObjectNode status = XrayReporter.readStatus(run());
            assertEquals("finished", status.get("state").asText());
            assertEquals(2, status.at("/executions/" + PLAN + "/pushed").asInt());
            assertEquals(0, status.at("/executions/" + PLAN + "/errors").size());
            assertTrue(XrayReporter.statusLines(status).get(0)
                    .startsWith("Xray: " + PLAN + " -> test execution " + key));
        }
    }

    private static int indexOfLastTransition(XrayFake fake) {
        int index = -1;
        for (int i = 0; i < fake.seen.size(); i++) {
            XrayFake.Seen r = fake.seen.get(i);
            if (r.method().equals("POST") && r.path().endsWith("/transitions")) {
                index = i;
            }
        }
        return index;
    }

    @Test
    void aRunWhereEveryCasePassedHasNoWhereToLook() throws Exception {
        XrayReporter reporter = Events.reporter(run(), null);
        List<ObjectNode> stream = new ArrayList<>();
        stream.add(Events.runStarted());
        stream.add(Events.planResolved(1));
        int seq = 2;
        for (var planned : Events.CASES) {
            if (planned.node() != null) {
                stream.add(Events.started(seq, Events.node(planned.key())));
                stream.add(Events.finished(seq + 1, Events.node(planned.key()), "passed"));
                seq += 2;
            }
        }
        stream.add(Events.cleanup(9, "complete", 1.0));
        stream.forEach(reporter::feed);
        reporter.finish();
        String summary = Events.read(run().resolve(XrayReporter.SUMMARY_FILE));
        assertTrue(summary.contains("3 of the 3 cases that ran passed."));
        assertTrue(summary.contains("Run ID run1234567890."));
        assertFalse(summary.contains("Where to look"));
    }

    @Test
    void aRunThatStoppedBeforeTheTestsSaysSoFirst() throws Exception {
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            reporter.feed(Events.runStarted());
            reporter.feed(Events.planResolved(1));
            reporter.feed(Events.event(2, "step.entered", Map.of("step", "Commander Configuration")));
            reporter.feed(Events.event(3, "step.finished", Map.of("step", "Commander Configuration",
                    "state", "failed", "elapsed", 3.0, "detail", "provision failed")));
            reporter.feed(Events.cleanup(4, "complete", 1.0));
            reporter.finish();
            String summary = Events.read(run().resolve(XrayReporter.SUMMARY_FILE));
            assertTrue(summary.contains("**The run did not reach the tests.** It stopped at the Commander "
                    + "Configuration step: provision failed."), summary);
            assertTrue(summary.contains("The run itself needs attention first"));
            assertFalse(summary.contains("| PETROCORE"));
            assertTrue(summary.contains("## Where to look"));
            assertTrue(fake.requests("POST", "/comment").get(0).body().contains("stopped at Commander Configuration"));
        }
    }

    @Test
    void aFakePosRunIsNeverPushed() {
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            reporter.feed(Events.runStarted(Map.of("fake_pos", true)));
            reporter.feed(Events.planResolved(1));
            for (ObjectNode event : Events.stream().subList(2, Events.stream().size())) {
                reporter.feed(event);
            }
            reporter.finish();
            assertTrue(fake.seen.isEmpty());
            ObjectNode status = XrayReporter.readStatus(run());
            assertEquals("disabled", status.get("state").asText());
            assertTrue(status.get("reason").asText().contains("fake POS"));
            assertEquals("Xray: not reported — " + status.get("reason").asText(),
                    XrayReporter.statusLines(status).get(0));
        }
    }

    @Test
    void aRunWithNoPlanReportsNothingAndSaysWhy() {
        try (XrayFake fake = new XrayFake()) {
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            reporter.feed(Events.runStarted(Map.of("test_plans", List.of())));
            for (ObjectNode event : Events.stream().subList(2, Events.stream().size())) {
                reporter.feed(event);
            }
            reporter.finish();
            assertTrue(fake.seen.isEmpty());
            assertTrue(XrayReporter.readStatus(run()).get("reason").asText().contains("no test plan"));
        }
    }

    @Test
    void aFailedPushIsKeptAndRetriedAfterTheBackoff() {
        try (XrayFake fake = new XrayFake()) {
            double[] now = {0.0};
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake), now, null);
            for (ObjectNode event : prefix(Events.stream(), 4)) {
                reporter.feed(event);
            }
            fake.failImports.set(4);  // the client's own retries are off in tests, so the first push fails
            assertEquals(0, reporter.flush(true));
            assertFalse(reporter.executions().get(PLAN).errors.isEmpty());
            // Inside the backoff: nothing is sent.
            assertEquals(0, reporter.flush(false));
            now[0] = 61.0;
            assertEquals(1, reporter.flush(false));
            assertEquals("PASSED", last(fake.statuses("PETROCORE-61396")));
        }
    }

    @Test
    void aRejectedCustomStatusIsResentAsItsBuiltIn() {
        try (XrayFake fake = new XrayFake()) {
            fake.rejectStatuses.add("ABORTED");
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            reporter.feed(Events.runStarted());
            reporter.feed(Events.planResolved(1));
            reporter.feed(Events.finished(1, NODE_61396, "skipped", Map.of(
                    "exc_type", "XFailed",
                    "detail", "plan_end verdict untrusted: 1 earlier case(s) failed")));
            assertEquals(1, reporter.flush(true));
            assertEquals(List.of("TODO", "ABORTED", "TODO"), fake.statuses("PETROCORE-61396"));
            assertTrue(reporter.executions().get(PLAN).errors.isEmpty());
        }
    }

    @Test
    void aDeadSiteAtCreationLeavesTheErrorInTheStatusFile() {
        XrayClient client = new XrayClient(new com.verifone.cat.atlassian.AtlassianClient(
                java.net.URI.create("http://127.0.0.1:1"), java.net.URI.create("http://127.0.0.1:1/api/v2"),
                () -> Events.JIRA, () -> Events.XRAY, java.time.Duration.ofSeconds(2)),
                java.time.Duration.ZERO, null);
        XrayReporter reporter = Events.reporter(run(), client);
        reporter.feed(Events.runStarted());
        reporter.feed(Events.planResolved(1));  // must not throw
        ObjectNode status = XrayReporter.readStatus(run());
        assertEquals("", status.at("/executions/" + PLAN + "/key").asText());
        assertTrue(status.at("/executions/" + PLAN + "/errors/0").asText().startsWith("create: Xray authenticate failed"));
        assertTrue(XrayReporter.statusLines(status).get(0).startsWith("Xray: " + PLAN + " -> create:"));
    }

    @Test
    void aDryRunWritesTheRequestsInsteadOfSendingThem() throws Exception {
        XrayReporter reporter = Events.reporter(run(), null);
        for (ObjectNode event : Events.stream()) {
            reporter.feed(event);
        }
        reporter.finish();
        List<String> whats = new ArrayList<>();
        for (String line : Files.readAllLines(run().resolve(XrayReporter.REQUESTS_FILE))) {
            whats.add(Events.json(line).get("what").asText());
        }
        assertEquals(List.of("create", "reporter", "transition:Start", "update", "describe", "comment",
                "transition:Close"), whats);
        JsonNode first = Events.json(Files.readAllLines(run().resolve(XrayReporter.REQUESTS_FILE)).get(0));
        assertEquals(PLAN, first.at("/payload/info/testPlanKey").asText());
    }

    @Test
    void theTitleIsThePlanNameAndKeyAndTheBuildIsInTheSummary() throws Exception {
        try (XrayFake fake = new XrayFake()) {
            var facts = new Summary.SummaryFacts("Solutions90", "buypass", "", "", "", "");
            XrayReporter reporter = new XrayReporter(run(), Events.xrayClient(fake), facts, 5.0,
                    key -> Events.CASES, key -> Events.PLAN_NAME, null, () -> 0.0);
            for (ObjectNode event : Events.stream()) {
                reporter.feed(event);
            }
            reporter.finish();
            JsonNode info = fake.imports().get(0).get("info");
            assertEquals("Automation Test Execution - Charity Donations Indoor (PETROCORE-70960)",
                    info.get("summary").asText());
            assertTrue(info.get("description").asText().contains("run ID run1234567890"));
            String summary = Events.read(run().resolve(XrayReporter.SUMMARY_FILE));
            assertTrue(summary.contains("Software under test: Solutions90 (buypass), already on the rig."));
        }
    }

    @Test
    void aPushThatHitsARateLimitIsRetriedAndLands() {
        try (XrayFake fake = new XrayFake()) {
            fake.rateLimitImports.set(1);
            XrayClient client = new XrayClient(Events.client(fake), java.time.Duration.ZERO, null);
            XrayReporter reporter = Events.reporter(run(), client);
            reporter.feed(Events.runStarted());
            reporter.feed(Events.planResolved(1));  // create gets a 429, then succeeds
            assertEquals("PETROCORE-900001", reporter.executions().get(PLAN).key);
            // Two attempts reached the site: the refused one and the one that landed.
            assertEquals(2, fake.imports().size());
        }
    }

    @Test
    void anExpiredTokenIsRefreshedOnceAndTheCallLands() {
        try (XrayFake fake = new XrayFake()) {
            fake.expireNextImport.set(true);
            XrayReporter reporter = Events.reporter(run(), Events.xrayClient(fake));
            reporter.feed(Events.runStarted());
            reporter.feed(Events.planResolved(1));
            assertEquals("PETROCORE-900001", reporter.executions().get(PLAN).key);
            assertEquals(2, fake.authenticates.get());
        }
    }

    private static List<String> keys(JsonNode tests) {
        List<String> out = new ArrayList<>();
        tests.forEach(t -> out.add(t.get("testKey").asText()));
        return out;
    }

    private static JsonNode lastTest(XrayFake fake) {
        List<JsonNode> batches = fake.imports();
        JsonNode batch = batches.get(batches.size() - 1);
        return batch.get("tests").get(0);
    }

    private static String last(List<String> values) {
        return values.get(values.size() - 1);
    }
}
