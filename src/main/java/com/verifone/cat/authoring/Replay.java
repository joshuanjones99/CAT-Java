package com.verifone.cat.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A written suite and, once it has been launched, the run that replayed it. Port of {@code cat.authoring.replay.Replay}
 * and {@code ReplayCase}; {@link ReplayGate} writes and judges it.
 *
 * @param directory where the suite was written, relative to the generated root ({@code <session>/replay-<k>})
 * @param cases     each written case, in session order
 * @param runId     the run that replayed it, or the empty string before it ran
 * @param capture   a capture run (writes its own baselines), never a verdict
 */
public record Replay(String directory, List<ReplayCase> cases, String runId, boolean capture) {

    /**
     * One written case: which session case, from which bytes, as which run node.
     *
     * @param caseId the session case's id
     * @param digest {@link Session#digest} when it was written
     * @param nodeid the node the run reports it as: the written file's path (see {@link ReplayGate})
     * @param number its place in the session
     */
    public record ReplayCase(String caseId, String title, String digest, String nodeid, int number) {}

    public Replay {
        cases = List.copyOf(cases);
        runId = runId == null ? "" : runId;
    }

    /** The run's targets: the written files, in session order. */
    public List<String> suites() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ReplayCase c : cases) {
            int split = c.nodeid().indexOf("::");
            out.add(split < 0 ? c.nodeid() : c.nodeid().substring(0, split));
        }
        return new ArrayList<>(out);
    }

    /** The same suite, replayed by {@code id}. */
    public Replay withRunId(String id) {
        return new Replay(directory, cases, id, capture);
    }

    /** The {@code session.json} form, as the Python writes it. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("directory", directory);
        out.put("run_id", runId);
        out.put("capture", capture);
        List<Object> rows = new ArrayList<>();
        for (ReplayCase c : cases) {
            rows.add(List.of(c.caseId(), c.title(), c.digest(), c.nodeid(), c.number()));
        }
        out.put("cases", rows);
        return out;
    }

    /**
     * The inverse of {@link #toJson}.
     *
     * @throws SessionException on anything else
     */
    public static Replay fromJson(JsonNode data) {
        try {
            if (data == null || !data.isObject() || !data.has("directory") || !data.has("cases")) {
                throw new IllegalArgumentException("missing 'directory' or 'cases'");
            }
            List<ReplayCase> cases = new ArrayList<>();
            for (JsonNode row : data.get("cases")) {
                if (!row.isArray() || row.size() != 5) {
                    throw new IllegalArgumentException("a case row is not five values");
                }
                JsonNode number = row.get(4);
                if (!number.canConvertToInt() && !number.isTextual()) {
                    throw new IllegalArgumentException("a case's number is not a number");
                }
                cases.add(new ReplayCase(row.get(0).asText(), row.get(1).asText(), row.get(2).asText(), row.get(3).asText(),
                        number.isTextual() ? Integer.parseInt(number.asText()) : number.asInt()));
            }
            String runId = data.hasNonNull("run_id") ? data.get("run_id").asText() : "";
            return new Replay(data.get("directory").asText(), cases, runId, data.path("capture").asBoolean(false));
        } catch (RuntimeException e) {
            throw new SessionException("a replay record needs a directory and its cases: " + e.getMessage(), e);
        }
    }
}
