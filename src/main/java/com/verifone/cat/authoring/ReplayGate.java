package com.verifone.cat.authoring;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.RunScope;

/**
 * The replay gate (P11.5): write a session's cases as a suite, and judge the run that ran it. Port of
 * {@code cat.authoring.replay} (CAT_RESULTS_AND_GENERATE.md 3.6).
 *
 * <p>The suite runs <b>through the ordinary run</b>: the Authoring page hands the Run tab's run service a
 * {@link LaunchConfig} whose suites are the written files ({@link #replayConfig}); there is no verification mode and no
 * second copy of the seven steps, and the run lands in Results like any other.
 *
 * <p>{@link #writeSuite} writes one {@code PETROCORE-<n>-TestCase.feature} per case, {@code n} its place in the session
 * (D25), into a new {@code <generated root>/<session>/replay-<k>/} (or {@code capture-<k>/}). That directory is never
 * reused (D23). A replay writes each case's <i>approved</i> baselines beside it; a <b>capture</b> writes none and runs
 * with {@code --snapshot-update}, so the rig's own receipt and TLog land there for {@link Session#importCapture}.
 *
 * <p>{@link #judge} is the gate publishing must pass. It is pure, and refuses unless every case passed on a rig, from the
 * drafts and baselines as they are now, with no recovery; a capture is never a verdict.
 *
 * <p>Deviations: the generated root is {@code ~/.cat/java/generated} (CAT-Java writes only under {@code ~/.cat/java}),
 * where the Python writes the repository's gitignored {@code tests/generated}. A case's node is its written file's
 * absolute path, which the run reports it by ({@code launch.CaseEntry}'s nodeid), where the Python's is
 * {@code <file>::test_<scenario>}; {@link #judge} also takes {@code <node>[...]} and {@code <node>::...} results, so an
 * Outline's rows count. The replay's {@link LaunchConfig} names the cases as a {@code cases} {@link RunScope} too.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no authored suite has been replayed on a rig.
 */
public final class ReplayGate {

    /** The pytest flag a capture run carries; a run with it is never a verdict. */
    public static final String SNAPSHOT_UPDATE = "--snapshot-update";

    /** The run's event file in its folder. */
    public static final String EVENTS_FILE = "events.ndjson";

    /** A case counts as passed in these states; {@code warned} is a pass that raised a warning. */
    private static final Set<String> PASSED = Set.of("passed", "warned");

    /** Steps 1-4: if one failed the tests never ran, and the step says why better than "no result" does. */
    private static final List<String> PREPARATION = ReplayRun.STEP_NAMES.subList(0, 4);

    private ReplayGate() {}

    /** The gate's answer. Publishing may proceed only when {@code reasons} is empty. */
    public record Verdict(List<String> reasons, String runId) {

        public Verdict {
            reasons = List.copyOf(reasons);
            runId = runId == null ? "" : runId;
        }

        public boolean ok() {
            return reasons.isEmpty();
        }
    }

    /** One case's id and the digest of what a replay would run for it, in session order. */
    public record Fingerprint(String caseId, String digest) {}

    /** Each case's id and digest, in order. */
    public static List<Fingerprint> fingerprint(Session session) {
        List<Fingerprint> out = new ArrayList<>();
        for (Case c : session.cases()) {
            out.add(new Fingerprint(c.id(), session.digest(c)));
        }
        return out;
    }

    /** {@code ~/.cat/java/generated}: where replayed suites are written. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), ".cat", "java", "generated");
    }

    /**
     * Writes every case into a new {@code <root>/<session>/(replay|capture)-<k>/}.
     *
     * @throws SessionException writing nothing, when there is no case or any case fails the checker: a line the library
     *                          does not say would only fail on the rig, later and slower
     */
    public static Replay writeSuite(Session session, Path root, boolean capture) {
        if (session.cases().isEmpty()) {
            throw new SessionException("no case to replay");
        }
        List<String> blockers = session.replayBlockers();
        if (!blockers.isEmpty()) {
            throw new SessionException("fix the cases first: " + String.join("; ", blockers));
        }
        String kind = capture ? "capture" : "replay";
        Path base = root.resolve(session.slug());
        int attempt = 1;
        while (Files.exists(base.resolve(kind + "-" + attempt))) {
            attempt++;
        }
        Path directory = base.resolve(kind + "-" + attempt);
        List<Replay.ReplayCase> cases = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            for (Case c : session.cases()) {
                CaseReview review = session.review(c);
                String key = "PETROCORE-" + review.number();
                Path path = directory.resolve(key + "-TestCase.feature");
                Files.writeString(path, review.text(), StandardCharsets.UTF_8);
                if (!capture && review.approved()) {
                    for (Session.Tail tail : session.baselineTails(c)) {
                        Files.copy(tail.path(), directory.resolve(key + tail.tail()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                String nodeid = path.toAbsolutePath().toString();
                cases.add(new Replay.ReplayCase(c.id(), review.title(), session.digest(c), nodeid, review.number()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String relative = root.relativize(directory).toString().replace('\\', '/');
        return new Replay(relative, cases, "", capture);
    }

    /**
     * The run that replays (or captures) {@code suite}: the form's choices with the written files as its suites, no plan,
     * and the cases named as a {@code cases} scope. A capture runs with {@code --snapshot-update}.
     *
     * @param base the Run tab's config for the rig (its rig, upgrade, simulators and Xray choice are kept)
     */
    public static LaunchConfig replayConfig(LaunchConfig base, Replay suite) {
        List<String> keys = new ArrayList<>();
        for (Replay.ReplayCase c : suite.cases()) {
            keys.add("PETROCORE-" + c.number());
        }
        LaunchConfig.Builder builder = new LaunchConfig.Builder(base.rig())
                .upgradeMode(base.upgradeMode() == null ? UpgradeMode.SKIP : base.upgradeMode())
                .fakePos(base.fakePos())
                .fakePinpad(base.fakePinpad())
                .snapshotUpdate(suite.capture())
                .scope(new RunScope(RunScope.Mode.CASES, "", keys, null, "", false))
                .xrayReport(base.xrayReport())
                .coverage(base.coverage())
                .commanderConnect(base.commanderConnect())
                .commanderConnect(base.commanderConnectSettings())
                .dist(base.dist())
                .testPlanBranch(base.testPlanBranch())
                .executionMode(base.executionMode())
                .buypassHost(base.buypassHost())
                .captureSnapshot(base.captureSnapshot())
                .ccRequirementWaived(base.ccRequirementWaived())
                .commanderBranch(base.commanderBranch())
                .commanderBuild(base.commanderBuild())
                .simBranch(base.simBranch())
                .simBuild(base.simBuild())
                .ifsfMode(base.ifsfMode())
                .localBuild(base.localBuild())
                .foundationBranch(base.foundationBranch())
                .foundationBuild(base.foundationBuild())
                .provisionRegister(base.provisionRegister())
                .keepSimulators(base.keepSimulators());
        suite.suites().forEach(builder::suite);
        base.simulators().forEach(builder::simulator);
        base.secondaryFeps().forEach(builder::secondaryFep);
        base.connections().forEach(builder::connection);
        return builder.build();
    }

    /**
     * The replayed run folded from {@code <root>/<runId>/events.ndjson}, or null when it cannot be read: an unreadable
     * run is judged as one, never thrown.
     */
    public static ReplayRun runState(String runId, Path root) {
        Path path = root.resolve(runId).resolve(EVENTS_FILE);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return ReplayRun.read(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String normal(String nodeid) {
        return nodeid.replace('\\', '/');
    }

    /** The node's results: itself, or one per Examples row of an Outline ({@code node[...]}, {@code node::...}). */
    static List<ReplayRun.Test> testsOf(ReplayRun state, String nodeid) {
        ReplayRun.Test exact = state.tests().get(nodeid);
        if (exact != null) {
            return List.of(exact);
        }
        String want = normal(nodeid);
        List<ReplayRun.Test> out = new ArrayList<>();
        for (Map.Entry<String, ReplayRun.Test> entry : state.tests().entrySet()) {
            String node = normal(entry.getKey());
            if (node.equals(want) || node.startsWith(want + "[") || node.startsWith(want + "::")) {
                out.add(entry.getValue());
            }
        }
        return out;
    }

    /**
     * Whether a publish may proceed, and every reason it may not.
     *
     * @param blockers the session's publish blockers ({@link Session#publishBlockers})
     * @param current  {@link #fingerprint} of the session now
     * @param last     the replay the session recorded, or null
     * @param state    that replay's run, or null
     */
    public static Verdict judge(List<String> blockers, List<Fingerprint> current, Replay last, ReplayRun state) {
        List<String> reasons = new ArrayList<>(blockers);
        if (last == null || last.runId().isEmpty()) {
            reasons.add("not replayed: ▶ Replay runs the cases as an ordinary run (§3.6)");
            return new Verdict(reasons, "");
        }
        String rid = last.runId();
        boolean captured = last.capture()
                || (state != null && Arrays.asList(state.cmd().strip().split("\\s+")).contains(SNAPSHOT_UPDATE));
        if (captured) {
            reasons.add("run " + rid + " captured baselines, so it is not a verdict: approve them, then ▶ Replay");
            return new Verdict(reasons, rid);
        }
        List<Fingerprint> replayed = new ArrayList<>();
        for (Replay.ReplayCase c : last.cases()) {
            replayed.add(new Fingerprint(c.caseId(), c.digest()));
        }
        if (!replayed.equals(current)) {
            reasons.add("the cases changed after replay " + rid + "; replay them again");
        }
        if (state == null) {
            reasons.add("replay " + rid + " has no readable " + EVENTS_FILE);
            return new Verdict(reasons, rid);
        }
        if ("cancelled".equals(state.status())) {
            reasons.add("replay " + rid + " was stopped before it finished");
        } else if (state.outcome() == null) {
            reasons.add("replay " + rid + " never finished");
        }
        if (state.fakePos()) {
            reasons.add("replay " + rid + " ran on the fake POS: evidence about this repository, not a rig");
        }
        for (ReplayRun.Step step : state.steps()) {
            if (PREPARATION.contains(step.name()) && "failed".equals(step.status())) {
                String detail = step.detail().isEmpty() ? "" : ": " + step.detail();
                reasons.add("replay " + rid + ": " + step.name() + " failed" + detail);
            }
        }
        for (Replay.ReplayCase c : last.cases()) {
            List<ReplayRun.Test> tests = testsOf(state, c.nodeid());
            if (tests.isEmpty()) {
                reasons.add(c.title() + ": never ran in replay " + rid);
                continue;
            }
            for (ReplayRun.Test test : tests) {
                if (test.status().equals("pending") || test.status().equals("running")) {
                    reasons.add(c.title() + ": did not finish in replay " + rid + " (an error in setup?)");
                } else if (!PASSED.contains(test.status())) {
                    List<String> parts = new ArrayList<>();
                    if (!test.excType().isEmpty()) {
                        parts.add(test.excType());
                    }
                    if (!test.detail().isEmpty()) {
                        parts.add(test.detail());
                    }
                    String why = String.join(": ", parts);
                    reasons.add(c.title() + ": " + test.status() + " in replay " + rid + (why.isEmpty() ? "" : " — " + why));
                }
            }
        }
        for (ReplayRun.Recovery recovery : state.recoveries()) {
            reasons.add("replay " + rid + ": a recovery fired for " + recovery.nodeid() + " (rung " + recovery.rung()
                    + ", " + recovery.outcome() + ")");
        }
        return new Verdict(reasons, rid);
    }
}
