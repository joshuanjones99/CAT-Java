package com.verifone.cat.xray;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianUnavailable;
import com.verifone.cat.xray.Adf.Block;
import com.verifone.cat.xray.Summary.Built;
import com.verifone.cat.xray.Summary.SummaryFacts;
import com.verifone.cat.xray.Summary.Verdict;
import com.verifone.cat.xray.XrayRunState.TestState;

/**
 * Folds the run's events and pushes each case's status as it changes. Port of the Python CAT's
 * {@code cat.xray.reporter.XrayReporter}.
 *
 * <p>What it pushes, when:
 * <ul>
 * <li>At {@code plan.resolved}: one Test Execution per plan key, linked to the plan, every case
 * {@code TODO} in the plan's order, summary {@code Automation Test Execution - <plan> (<key>)}. The
 * key is written to {@code xray.json} at once.</li>
 * <li>While the run goes: a started case is {@code EXECUTING}; a case with a verdict gets its status,
 * its start and finish, and a comment (the exception, or "Passed in N s"). Changes are coalesced into
 * requests of at most fifty cases, sent at most once per flush interval.</li>
 * <li>At the end: every case once more, then the summary as the execution's description and a short
 * comment, the {@code Close} transition, and {@code xray-summary.md}.</li>
 * </ul>
 * A push that fails is kept and retried after a backoff; nothing here throws past
 * {@link #feed}, {@link #flush} or {@link #finish}. A fake-POS run is never pushed. A dry run
 * ({@code client == null}) writes each request to {@code xray-requests.ndjson} instead.
 *
 * <p>Statuses: {@code PASSED}, {@code FAILED}, {@code EXECUTING} and {@code TODO} are Xray's
 * built-ins; {@code PASSED-WITH-ERRORS} is this site's custom status for a warning; {@code ABORTED}
 * is Xray Cloud's built-in for a stopped run. A batch the site rejects with HTTP 400 is re-sent with
 * the two custom statuses replaced by their nearest built-in.
 *
 * <p>Single-threaded: the sidecar feeds and flushes from one thread. The Java port has only met a
 * loopback fake; the live site accepted these requests from the Python CAT.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the tests run against a loopback fake, never the live site.
 */
public final class XrayReporter {

    private static final Logger LOG = LoggerFactory.getLogger(XrayReporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter WIRE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** The Jira project executions are created in: PATS's {@code app.jira.project}. */
    public static final String PROJECT = "PETROCORE";

    /** Workflow transitions by name, as PATS's {@code app.jira.transition.names=Start,Close}. */
    public static final String START_TRANSITION = "Start";
    public static final String FINISH_TRANSITION = "Close";

    /** Case verdict to Xray status. */
    public static final Map<String, String> XRAY_STATUS = Map.of(
            "passed", "PASSED",
            "failed", "FAILED",
            "warned", "PASSED-WITH-ERRORS",
            "skipped", "ABORTED");

    /** The custom statuses above and what to send if the site rejects them. */
    static final Map<String, String> STATUS_FALLBACK = Map.of("PASSED-WITH-ERRORS", "PASSED", "ABORTED", "TODO");

    public static final String STATUS_FILE = "xray.json";
    public static final String SUMMARY_FILE = "xray-summary.md";
    public static final String REQUESTS_FILE = "xray-requests.ndjson";

    /** Longest per-case comment sent to Xray. */
    static final int COMMENT_MAX = 1500;

    /** After a failed push, how long (seconds) before the next attempt. */
    static final double PUSH_BACKOFF_SECONDS = 60.0;

    /** A case's cumulative push state, for the diff. */
    record Pushed(String status, String comment) {}

    /** One case as it should now read in Xray. */
    record Wanted(String key, String status, String comment, String start, String finish) {}

    /** One Test Execution: the plan it reports, its cases, and what has been pushed. */
    public static final class Execution {

        final String planKey;
        final String planName;
        final Map<String, String> cases;
        final Map<String, Pushed> pushed = new HashMap<>();
        final List<String> errors = new ArrayList<>();
        String key = "";

        Execution(String planKey, String planName, Map<String, String> cases) {
            this.planKey = planKey;
            this.planName = planName;
            this.cases = cases;
        }

        /** The browse link, or "" before the execution exists. */
        public String url() {
            return key.isEmpty() ? "" : AtlassianClient.SITE + "/browse/" + key;
        }
    }

    private final Path runDir;
    private final XrayClient client;
    private final SummaryFacts facts;
    private final double flushIntervalSeconds;
    private final Function<String, List<com.verifone.cat.plans.PlanCase>> planCases;
    private final Function<String, String> planName;
    private final Function<XrayRunState, Map<String, List<Verdict>>> history;
    private final DoubleSupplier clock;

    private final XrayRunState state = new XrayRunState();
    private final Map<String, Execution> executions = new LinkedHashMap<>();
    private final Map<String, String> startedAt = new HashMap<>();
    private final Map<String, String> finishedAt = new HashMap<>();
    private String disabledReason = "";
    private boolean finished;
    private boolean runOver;
    private double lastFlush;
    private double nextPushAllowed;

    /**
     * @param runDir               the run's folder, where the status, summary and dry-run files go
     * @param client               the Xray and Jira client, or {@code null} for a dry run
     * @param facts                what the launcher knows about the run
     * @param flushIntervalSeconds the coalescing interval for pushes
     * @param planCases            a plan's cases in Xray's order, or {@code null} to use the stream's
     * @param planName             a plan's catalogue name, "" when it has none
     * @param history              earlier verdicts for the summary; a failure is tolerated
     * @param clock                a monotonic clock in seconds
     */
    public XrayReporter(Path runDir, XrayClient client, SummaryFacts facts, double flushIntervalSeconds,
            Function<String, List<com.verifone.cat.plans.PlanCase>> planCases,
            Function<String, String> planName,
            Function<XrayRunState, Map<String, List<Verdict>>> history,
            DoubleSupplier clock) {
        this.runDir = runDir;
        this.client = client;
        this.facts = facts == null ? SummaryFacts.none() : facts;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.planCases = planCases;
        this.planName = planName == null ? key -> "" : planName;
        this.history = history == null ? state -> Map.of() : history;
        this.clock = clock;
        this.lastFlush = clock.getAsDouble();
    }

    // ------------------------------------------------------------------ events

    /** Whether the stream has said the run is over: Cleanup done, or the run cancelled. */
    public boolean runOver() {
        return runOver;
    }

    /** The folded state the reporter reads. */
    public XrayRunState state() {
        return state;
    }

    /** The executions by plan key, in creation order. */
    public Map<String, Execution> executions() {
        return executions;
    }

    /** Folds one event; creates the executions once the plan is known. Never throws. */
    public void feed(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return;
        }
        state.apply(raw);
        String type = text(raw, "type");
        String nodeid = text(raw, "nodeid");
        if ("test.started".equals(type) && !nodeid.isEmpty()) {
            startedAt.putIfAbsent(nodeid, text(raw, "at"));
        } else if ("test.finished".equals(type) && !nodeid.isEmpty()) {
            finishedAt.put(nodeid, text(raw, "at"));
        } else if ("run.started".equals(type)
                && (raw.path("fake_pos").asBoolean(false) || raw.path("pos_double").asBoolean(false))) {
            disable("fake POS run: evidence about this repository, never about a rig");
        } else if (("run.finished".equals(type) && raw.path("cancelled").asBoolean(false))
                || ("step.finished".equals(type) && XrayRunState.STEP_NAMES.get(6).equals(text(raw, "step")))) {
            runOver = true;
        }
        if ("plan.resolved".equals(type) && disabledReason.isEmpty() && executions.isEmpty()) {
            createExecutions();
        }
    }

    /** Turns reporting off for this run and says why in {@code xray.json}. */
    public void disable(String reason) {
        if (disabledReason.isEmpty()) {
            disabledReason = reason;
            LOG.info("Xray reporting off: {}", reason);
            writeStatus();
        }
    }

    // ------------------------------------------------------------------ create

    private Map<String, String> casesFor(String planKey) {
        Map<String, String> cases = new LinkedHashMap<>();
        if (planCases != null) {
            List<com.verifone.cat.plans.PlanCase> rows = List.of();
            try {
                rows = planCases.apply(planKey);
            } catch (RuntimeException e) {
                // A cache miss is not a reason to report nothing.
                LOG.warn("could not load {}'s case list: {}", planKey, e.getMessage());
            }
            if (!rows.isEmpty()) {
                rows.forEach(row -> cases.put(row.key(), row.node()));
                return cases;
            }
        }
        if (state.testPlans().size() == 1) {
            state.cases().values().forEach(kase -> cases.put(kase.key(), kase.node()));
        }
        return cases;
    }

    private void createExecutions() {
        for (String planKey : state.testPlans()) {
            Map<String, String> cases = casesFor(planKey);
            if (cases.isEmpty()) {
                LOG.warn("{}: no case list, so no execution is created", planKey);
                continue;
            }
            Execution execution = new Execution(planKey, planName.apply(planKey), cases);
            executions.put(planKey, execution);
            List<Map<String, Object>> tests = new ArrayList<>();
            cases.keySet().forEach(key -> tests.add(Map.of("testKey", key, "status", "TODO")));
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("project", PROJECT);
            info.put("summary", Summary.executionSummaryLine(planKey, execution.planName));
            info.put("description", "Automated run of " + planKey + " by CAT on the " + state.rig()
                    + " rig, run ID " + state.runId() + ". Statuses update as each case finishes; "
                    + "the summary is written here when the run ends.");
            info.put("testPlanKey", planKey);
            // No testEnvironments: Xray only accepts names defined in the project, and the first live
            // send was refused over "lab". The rig is in the summary and the description instead.
            info.put("startDate", wireTime(state.startedMoment().orElse(null)));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("info", info);
            payload.put("tests", tests);
            try {
                execution.key = importCall(payload, "create");
                LOG.info("{}: created test execution {}", planKey, execution.key);
                setReporter(execution);
                transition(execution, START_TRANSITION);
            } catch (AtlassianUnavailable e) {
                execution.errors.add("create: " + e.getMessage());
                LOG.error("{}: could not create the test execution: {}", planKey, e.getMessage());
            }
        }
        writeStatus();
    }

    // ------------------------------------------------------------------ push

    /** Case key to what Xray should now say of it, in the execution's case order. */
    Map<String, Wanted> desired(Execution execution) {
        Map<String, String> verdicts = state.caseVerdicts();
        Map<String, List<String>> byFile = new HashMap<>();
        state.tests().keySet().forEach(nodeid ->
                byFile.computeIfAbsent(XrayRunState.nodeFile(nodeid), k -> new ArrayList<>()).add(nodeid));
        Map<String, Wanted> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : execution.cases.entrySet()) {
            String node = entry.getValue();
            if (node == null) {
                continue;
            }
            List<String> nodeids = byFile.getOrDefault(node, List.of());
            String key = entry.getKey();
            String verdict = verdicts.get(key);
            if (verdict != null) {
                List<String> starts = new ArrayList<>();
                List<String> ends = new ArrayList<>();
                for (String nodeid : nodeids) {
                    if (startedAt.containsKey(nodeid)) {
                        starts.add(startedAt.get(nodeid));
                    }
                    if (finishedAt.containsKey(nodeid)) {
                        ends.add(finishedAt.get(nodeid));
                    }
                }
                wanted.put(key, new Wanted(key, XRAY_STATUS.getOrDefault(verdict, "FAILED"),
                        comment(nodeids, verdict),
                        starts.isEmpty() ? "" : starts.stream().min(String::compareTo).orElse(""),
                        ends.isEmpty() ? "" : ends.stream().max(String::compareTo).orElse("")));
            } else if (nodeids.stream().anyMatch(n -> "running".equals(state.tests().get(n).status()))) {
                wanted.put(key, new Wanted(key, "EXECUTING", "", "", ""));
            }
        }
        return wanted;
    }

    private String comment(List<String> nodeids, String verdict) {
        List<TestState> tests = new ArrayList<>();
        for (String nodeid : nodeids) {
            if (state.tests().containsKey(nodeid)) {
                tests.add(state.tests().get(nodeid));
            }
        }
        if ("passed".equals(verdict)) {
            double seconds = tests.stream().mapToDouble(TestState::duration).sum();
            return "Passed in " + BigDecimal.valueOf(seconds).setScale(0, RoundingMode.HALF_EVEN).toPlainString() + " s";
        }
        if ("warned".equals(verdict)) {
            return "Passed, with warnings from the automation";
        }
        List<TestState> failing = tests.stream()
                .filter(t -> XrayRunState.VERDICT_STATUSES.contains(t.status()) && !"passed".equals(t.status()))
                .toList();
        TestState head = !failing.isEmpty() ? failing.get(0) : (tests.isEmpty() ? null : tests.get(0));
        if (head == null) {
            return "";
        }
        String text = head.excType().isEmpty() ? head.detail() : head.excType() + ": " + head.detail();
        return text.length() > COMMENT_MAX ? text.substring(0, COMMENT_MAX) : text;
    }

    /**
     * Pushes every case whose status changed since the last push, fifty to a request. Returns how
     * many cases were pushed. Respects the backoff unless {@code force}.
     */
    public int flush(boolean force) {
        if (!disabledReason.isEmpty() || executions.isEmpty()) {
            return 0;
        }
        double now = clock.getAsDouble();
        if (!force && now < nextPushAllowed) {
            return 0;
        }
        int sent = 0;
        for (Execution execution : executions.values()) {
            if (execution.key.isEmpty()) {
                continue;
            }
            List<Wanted> dirty = new ArrayList<>();
            for (Wanted row : desired(execution).values()) {
                if (!new Pushed(row.status(), row.comment()).equals(execution.pushed.get(row.key()))) {
                    dirty.add(row);
                }
            }
            for (int index = 0; index < dirty.size(); index += XrayClient.BATCH_SIZE) {
                List<Wanted> batch = dirty.subList(index, Math.min(index + XrayClient.BATCH_SIZE, dirty.size()));
                List<Map<String, Object>> tests = new ArrayList<>();
                batch.forEach(row -> tests.add(testEntry(row)));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("testExecutionKey", execution.key);
                payload.put("tests", tests);
                try {
                    importCall(payload, "update");
                } catch (AtlassianUnavailable e) {
                    execution.errors.add("update: " + e.getMessage());
                    LOG.error("{}: push of {} case(s) failed: {}", execution.key, batch.size(), e.getMessage());
                    nextPushAllowed = now + PUSH_BACKOFF_SECONDS;
                    break;
                }
                batch.forEach(row -> execution.pushed.put(row.key(), new Pushed(row.status(), row.comment())));
                sent += batch.size();
            }
        }
        lastFlush = now;
        if (sent > 0) {
            writeStatus();
        }
        return sent;
    }

    /** {@link #flush(boolean)} unless the flush interval has not passed since the last one. */
    public int maybeFlush() {
        if (clock.getAsDouble() - lastFlush < flushIntervalSeconds) {
            return 0;
        }
        return flush(false);
    }

    private static Map<String, Object> testEntry(Wanted row) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("testKey", row.key());
        entry.put("status", row.status());
        if (!row.comment().isEmpty()) {
            entry.put("comment", row.comment());
        }
        if (!row.start().isEmpty()) {
            entry.put("start", row.start());
        }
        if (!row.finish().isEmpty()) {
            entry.put("finish", row.finish());
        }
        return entry;
    }

    /**
     * Sends one import, or records it on a dry run. A 400 naming a status is re-sent once with the
     * built-in fallbacks. Returns the execution key (or "DRY-RUN").
     */
    private String importCall(Map<String, Object> payload, String what) {
        if (client == null) {
            record(what, payload);
            return "DRY-RUN";
        }
        try {
            return client.importExecution(payload);
        } catch (AtlassianUnavailable e) {
            if (!String.valueOf(e.getMessage()).contains("HTTP 400") || !hasFallbackStatus(payload)) {
                throw e;
            }
            LOG.warn("{} rejected ({}); retrying with built-in statuses", what, e.getMessage());
            return client.importExecution(withFallbackStatuses(payload));
        }
    }

    private static boolean hasFallbackStatus(Map<String, Object> payload) {
        return tests(payload).stream().anyMatch(t -> STATUS_FALLBACK.containsKey(String.valueOf(t.get("status"))));
    }

    private static Map<String, Object> withFallbackStatuses(Map<String, Object> payload) {
        List<Map<String, Object>> tests = new ArrayList<>();
        for (Map<String, Object> test : tests(payload)) {
            Map<String, Object> copy = new LinkedHashMap<>(test);
            String status = String.valueOf(test.get("status"));
            copy.put("status", STATUS_FALLBACK.getOrDefault(status, status));
            tests.add(copy);
        }
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("tests", tests);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tests(Map<String, Object> payload) {
        Object tests = payload.get("tests");
        return tests instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** Dry run: the request, as it would have been sent, appended to a file. */
    private void record(String what, Object payload) {
        try {
            Files.createDirectories(runDir);
            String line = JSON.writeValueAsString(Map.of("what", what, "payload", payload)) + "\n";
            Files.writeString(runDir.resolve(REQUESTS_FILE), line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("could not record {}: {}", what, e.getMessage());
        }
    }

    /** Makes the execution's reporter the Jira user whose token ran this (the rig owner's rule). */
    private void setReporter(Execution execution) {
        if (client == null || execution.key.isEmpty()) {
            record("reporter", Map.of("key", execution.key));
            return;
        }
        try {
            XrayClient.Myself me = client.myself();
            client.setReporter(execution.key, me.accountId());
            LOG.info("{}: reporter set to {}", execution.key, me.displayName());
        } catch (AtlassianUnavailable e) {
            execution.errors.add("reporter: " + e.getMessage());
            LOG.warn("{}: could not set the reporter: {}", execution.key, e.getMessage());
        }
    }

    private void transition(Execution execution, String name) {
        if (client == null || execution.key.isEmpty()) {
            record("transition:" + name, Map.of("key", execution.key));
            return;
        }
        try {
            boolean applied = client.transition(execution.key, name);
            LOG.info("{}: transition '{}' {}", execution.key, name, applied ? "applied" : "not offered");
        } catch (AtlassianUnavailable e) {
            execution.errors.add("transition " + name + ": " + e.getMessage());
            LOG.warn("{}: transition '{}' failed: {}", execution.key, name, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ finish

    /** The final push, the summary, the comment, the transition, and the files. Idempotent. */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (!disabledReason.isEmpty()) {
            writeStatus();
            return;
        }
        if (executions.isEmpty()) {
            String reason = state.testPlans().isEmpty()
                    ? "no test plan was chosen, so there is no plan to report against"
                    : "the plan never resolved to a case list, so there is nothing to report";
            disable(reason);
            return;
        }
        nextPushAllowed = 0.0;
        flush(true);
        Map<String, List<Verdict>> verdicts;
        try {
            verdicts = history.apply(state);
        } catch (RuntimeException e) {
            // History is a nicety; the summary stands without it.
            LOG.warn("no run history: {}", e.getMessage());
            verdicts = Map.of();
        }
        Map<String, String> planNames = new LinkedHashMap<>();
        executions.forEach((plan, execution) -> planNames.put(plan, execution.planName));
        Built built = Summary.build(state, facts, planNames, verdicts, runDir.toString());
        writeSummaryFile(built.blocks());
        JsonNode description = Adf.toAdf(built.blocks());
        JsonNode comment = Adf.toAdf(Summary.commentBlocks(built.digest()));
        for (Execution execution : executions.values()) {
            if (execution.key.isEmpty()) {
                continue;
            }
            if (client == null) {
                record("describe", Map.of("key", execution.key, "description", description));
                record("comment", Map.of("key", execution.key, "body", comment));
            } else {
                try {
                    client.setDescription(execution.key, description);
                } catch (AtlassianUnavailable e) {
                    execution.errors.add("describe: " + e.getMessage());
                    LOG.error("{}: describe failed: {}", execution.key, e.getMessage());
                }
                try {
                    client.addComment(execution.key, comment);
                } catch (AtlassianUnavailable e) {
                    execution.errors.add("comment: " + e.getMessage());
                    LOG.error("{}: comment failed: {}", execution.key, e.getMessage());
                }
            }
            transition(execution, FINISH_TRANSITION);
        }
        writeStatus();
    }

    private void writeSummaryFile(List<Block> blocks) {
        try {
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve(SUMMARY_FILE), Adf.toMarkdown(blocks), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("could not write {}: {}", SUMMARY_FILE, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ status

    /** What {@code xray.json} holds: the state, the executions with their keys and errors. */
    public ObjectNode status() {
        ObjectNode out = JSON.createObjectNode();
        out.put("state", !disabledReason.isEmpty() ? "disabled" : (finished ? "finished" : "reporting"));
        out.put("reason", disabledReason);
        out.put("run_id", state.runId());
        out.put("updated_at", Instant.now().toString());
        ObjectNode plans = out.putObject("executions");
        for (Execution execution : executions.values()) {
            ObjectNode row = plans.putObject(execution.planKey);
            row.put("key", execution.key);
            row.put("url", execution.url());
            row.put("plan_name", execution.planName);
            row.put("cases", execution.cases.size());
            row.put("pushed", execution.pushed.size());
            List<String> errors = execution.errors;
            ArrayList<String> last = new ArrayList<>(errors.subList(Math.max(0, errors.size() - 5), errors.size()));
            row.set("errors", JSON.valueToTree(last));
        }
        out.put("summary_file", finished ? runDir.resolve(SUMMARY_FILE).toString() : "");
        return out;
    }

    private void writeStatus() {
        try {
            Files.createDirectories(runDir);
            Path path = runDir.resolve(STATUS_FILE);
            Path tmp = runDir.resolve(STATUS_FILE + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(status()),
                    StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("could not write {}: {}", STATUS_FILE, e.getMessage());
        }
    }

    /** {@code xray.json} for a run, or null when the sidecar never wrote one. */
    public static ObjectNode readStatus(Path runDir) {
        try {
            JsonNode node = JSON.readTree(runDir.resolve(STATUS_FILE).toFile());
            return node instanceof ObjectNode object ? object : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** One line per execution, for a step log or a pane. */
    public static List<String> statusLines(JsonNode status) {
        List<String> lines = new ArrayList<>();
        if ("disabled".equals(text(status, "state"))) {
            lines.add("Xray: not reported — " + text(status, "reason"));
            return lines;
        }
        JsonNode executions = status.path("executions");
        executions.fieldNames().forEachRemaining(plan -> {
            JsonNode execution = executions.get(plan);
            String key = text(execution, "key");
            if (!key.isEmpty()) {
                lines.add("Xray: " + plan + " -> test execution " + key + " (" + text(execution, "url") + "), "
                        + execution.path("pushed").asInt(0) + " of " + execution.path("cases").asInt(0)
                        + " case(s) pushed");
            } else {
                List<String> errors = new ArrayList<>();
                execution.path("errors").forEach(e -> errors.add(e.asText()));
                lines.add("Xray: " + plan + " -> "
                        + (errors.isEmpty() ? "no execution created yet" : String.join("; ", errors)));
            }
        });
        if (lines.isEmpty()) {
            lines.add("Xray: reporting started, no execution yet");
        }
        return lines;
    }

    private static String wireTime(OffsetDateTime moment) {
        return (moment == null ? OffsetDateTime.now(ZoneOffset.UTC) : moment)
                .withNano(0).format(WIRE_TIME);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
