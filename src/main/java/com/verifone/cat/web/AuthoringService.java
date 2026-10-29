package com.verifone.cat.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.authoring.Actions;
import com.verifone.cat.authoring.Case;
import com.verifone.cat.authoring.Draft;
import com.verifone.cat.authoring.Draft.DraftResult;
import com.verifone.cat.authoring.Ingest;
import com.verifone.cat.authoring.Publish;
import com.verifone.cat.authoring.Replay;
import com.verifone.cat.authoring.ReplayGate;
import com.verifone.cat.authoring.ReplayGate.Verdict;
import com.verifone.cat.authoring.ReplayRun;
import com.verifone.cat.authoring.Session;
import com.verifone.cat.authoring.SessionException;
import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.RunService;

/**
 * What the Authoring page holds: the open session, the selected case, the tab, the step filter, the last message, the
 * last draft, the running replay and the replayed runs it has read. Port of the state half of
 * {@code cat/web/pages/authoring.py} ({@code AuthoringState}).
 *
 * <p>Every action is called through {@link #act}: a refusal (a bad ticket, no case selected, an unreadable Jira, no rig)
 * becomes the page's message, never a failed request. <b>Draft with Claude</b> runs off the request thread (Claude takes
 * minutes) and posts its progress as the message; <b>Capture</b> and <b>Replay</b> write the suite
 * ({@link ReplayGate#writeSuite}) and hand it to the Run page's run service as an ordinary run ({@link AuthoringRuns});
 * the page notices the run's end when it next draws ({@link #poll}), keeps a capture's baselines
 * ({@link Session#importCapture}) or records the replay and judges it ({@link ReplayGate#judge}). <b>Publish</b> is the
 * Python's dry run, behind the real verdict.
 *
 * <p>The session root, the generated-suite root, Jira, Claude and the run service are injected, so a test never touches
 * {@code ~/.cat}, Jira, Claude or a rig. Opening the page creates nothing: a session is created only by an action that
 * needs one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no authored case has been captured or replayed at a rig through this page.
 */
final class AuthoringService {

    static final String TAB_FEATURE = "feature";
    static final String TAB_STEPS = "steps";
    static final String TAB_SPEC = "spec";
    static final String TAB_REVIEW = "review";
    static final List<String> TABS = List.of(TAB_FEATURE, TAB_STEPS, TAB_SPEC, TAB_REVIEW);

    /** The branch a publish would cut its story branch from (the Python's default). */
    static final String BASE_BRANCH = "master";

    private static final Duration JIRA_TIMEOUT = Duration.ofSeconds(20);

    /** A page action that may refuse by throwing. */
    @FunctionalInterface
    interface Action {
        void run();
    }

    private final Path root;
    private final Path generatedRoot;
    private final Supplier<AtlassianClient> atlassian;
    private final AuthoringRuns runs;
    private final Draft.Asker asker;
    private final Consumer<Runnable> background;
    private boolean loaded;
    private Session session;
    private int cursor;
    private String message = "";
    private String tab = TAB_FEATURE;
    private String stepFilter = "";
    private String ticketField = "";
    private String specField = "";
    private List<String> publishLines;
    private String busy = "";
    private boolean replaying;
    private Replay pending;
    private DraftResult draftResult;
    /** Replayed runs' folded state by run id; a null value is a run that could not be read. */
    private final Map<String, ReplayRun> runStates = new HashMap<>();

    AuthoringService(Path root, Supplier<AtlassianClient> atlassian) {
        this(root, root.resolveSibling("generated"), atlassian, AuthoringRuns.unwired(root.resolveSibling("results")),
                null, AuthoringService::daemon);
    }

    /**
     * @param generatedRoot where replayed suites are written
     * @param runs          the run service a replay goes through
     * @param asker         who answers a draft; null asks Claude Code
     * @param background    runs a draft off the request thread (a test passes {@code Runnable::run})
     */
    AuthoringService(Path root, Path generatedRoot, Supplier<AtlassianClient> atlassian, AuthoringRuns runs,
            Draft.Asker asker, Consumer<Runnable> background) {
        this.root = root;
        this.generatedRoot = generatedRoot;
        this.atlassian = atlassian;
        this.runs = runs;
        this.asker = asker;
        this.background = background;
    }

    /**
     * The operator's own: {@code $CAT_AUTHORING_DIR}, else {@code ~/.cat/java/authoring}; Jira from the credentials file;
     * no run service, so Replay and Capture say they need one (see {@link #standard(RunService, ConfigForm)}).
     */
    static AuthoringService standard() {
        return standard(AuthoringRuns.unwired(AuthoringRuns.resultsRoot(System.getenv())));
    }

    /** The operator's own, replaying through the Run page's {@code run} service with the Config tab's choices. */
    static AuthoringService standard(RunService run, ConfigForm form) {
        return standard(AuthoringRuns.over(run, form, AuthoringRuns.resultsRoot(System.getenv())));
    }

    private static AuthoringService standard(AuthoringRuns runs) {
        Path fallback = Path.of(System.getProperty("user.home"), ".cat", "java", "authoring");
        return new AuthoringService(Session.authoringRoot(System.getenv(), fallback), ReplayGate.defaultRoot(),
                () -> AtlassianClient.forCredentialsFile(AtlassianCredentials.defaultPath(), JIRA_TIMEOUT), runs,
                null, AuthoringService::daemon);
    }

    private static void daemon(Runnable work) {
        Thread thread = new Thread(work, "cat-authoring-worker");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs {@code view} with the state held still, so one page is drawn from one moment; a finished replay is noticed first. */
    synchronized <T> T view(Function<AuthoringService, T> view) {
        poll();
        return view.apply(this);
    }

    /** Runs one action; a refusal becomes the message. */
    synchronized void act(Action action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            say("✖ " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ------------------------------------------------------------ reads

    /** The open session, or null when there is none. Loads the newest one on first use. */
    synchronized Session session() {
        load();
        return session;
    }

    /** The selected case's index, or -1 when there is no case. */
    synchronized int cursor() {
        Session s = session();
        if (s == null || s.cases().isEmpty()) {
            return -1;
        }
        return cursor >= 0 && cursor < s.cases().size() ? cursor : 0;
    }

    /** The selected case, or null. */
    synchronized Case selected() {
        int index = cursor();
        return index < 0 ? null : session.cases().get(index);
    }

    synchronized String message() {
        return message;
    }

    synchronized String tab() {
        return tab;
    }

    synchronized String stepFilter() {
        return stepFilter;
    }

    /** What the story ticket input holds: the typed text after a refusal, else the session's ticket. */
    synchronized String ticketField() {
        load();
        return ticketField;
    }

    synchronized String specField() {
        load();
        return specField;
    }

    /** The last dry run's lines, or null before one. Cleared when a draft changes. */
    synchronized List<String> publishLines() {
        return publishLines == null ? null : List.copyOf(publishLines);
    }

    /** What a background worker is doing ({@code Claude is drafting}), or the empty string. */
    synchronized String busy() {
        return busy;
    }

    /** Whether a replay or capture this page started is still running. */
    synchronized boolean replaying() {
        return replaying;
    }

    /** The last draft's outcome, or null. */
    synchronized DraftResult draftResult() {
        return draftResult;
    }

    /** Whether the page should keep asking for news: a draft or a replay is under way. */
    synchronized boolean waiting() {
        return !busy.isEmpty() || replaying;
    }

    // ------------------------------------------------------------ the editor

    /**
     * Saves a case's draft as the editor sent it, when {@code caseId} is the selected case and the text changed.
     * Browsers send a textarea's line breaks as CRLF; the file keeps LF.
     */
    synchronized void saveDraft(String caseId, String text) {
        Case c = selected();
        if (text == null || c == null || !c.id().equals(caseId)) {
            return;
        }
        String normal = text.replace("\r\n", "\n");
        if (!normal.equals(session.text(c))) {
            session.writeText(c, normal);
            publishLines = null;
        }
    }

    /** A phrase's example goes under the last non-blank line of the selected case's draft. */
    synchronized void insertStep(String example) {
        Case c = selected();
        if (c == null) {
            throw new SessionException("add a case first: a phrase goes into a case's draft");
        }
        session.writeText(c, AuthoringSteps.insertExample(session.text(c), example));
        publishLines = null;
        tab = TAB_FEATURE;
    }

    synchronized void filterSteps(String text) {
        stepFilter = text == null ? "" : text;
    }

    synchronized void selectTab(String name) {
        if (TABS.contains(name)) {
            tab = name;
        }
    }

    synchronized void select(int index) {
        Session s = session();
        if (s != null && index >= 0 && index < s.cases().size()) {
            cursor = index;
        }
    }

    // ------------------------------------------------------------ case edits

    /** A new case after the selected one (or last), named {@code name} or {@code Case N}. */
    synchronized void newCase(String name) {
        Session s = sessionOrCreate();
        int index = cursor();
        int at = index < 0 ? s.cases().size() : index + 1;
        String title = name == null || name.isBlank() ? "Case " + (s.cases().size() + 1) : name;
        Case c = s.insert(at, title);
        cursor = s.cases().indexOf(c);
        say("added " + c.title() + " as case " + (at + 1) + "; write its steps on the Feature tab");
        tab = TAB_FEATURE;
    }

    synchronized void renameCase(String name) {
        int index = cursor();
        if (index < 0) {
            throw new SessionException("no case to rename");
        }
        Case c = session.rename(index, name);
        say("case " + (index + 1) + " is now " + c.title());
    }

    synchronized void moveCase(int delta) {
        int index = cursor();
        if (index < 0) {
            return;
        }
        cursor = session.move(index, delta);
    }

    /** Drops the selected case from the session; its draft file stays on disk. */
    synchronized void removeCase() {
        int index = cursor();
        if (index < 0) {
            throw new SessionException("no case to remove");
        }
        Case c = session.remove(index);
        say("removed " + c.title() + "; its draft stays at " + session.featurePath(c));
        cursor = Math.max(0, Math.min(index, session.cases().size() - 1));
    }

    synchronized void setTicket(String text) {
        ticketField = text == null ? "" : text;
        Session s = sessionOrCreate();
        String ticket = s.setTicket(ticketField);
        ticketField = ticket == null ? "" : ticket;
        say(ticket == null ? "story ticket cleared" : "story ticket " + ticket);
    }

    synchronized void newSession() {
        if (replaying || !busy.isEmpty()) {
            throw new SessionException("wait for the replay or draft to finish first");
        }
        session = Session.create(root);
        loaded = true;
        cursor = 0;
        publishLines = null;
        draftResult = null;
        ticketField = "";
        specField = "";
        say("new session " + session.slug());
    }

    // ------------------------------------------------------------ ingest and draft

    /** Reads a Jira spec ticket into the session (read-only). Creates the session when there is none. */
    synchronized void ingest(String key) {
        if (!busy.isEmpty()) {
            throw new SessionException("wait: " + busy + " is still running");
        }
        String typed = key == null ? "" : key.strip();
        specField = typed;
        if (typed.isEmpty()) {
            throw new SessionException("type the ticket to ingest, e.g. PETROCORE-678");
        }
        Session s = sessionOrCreate();
        Ingest.Spec spec = Ingest.intoSession(s, typed, atlassian.get());
        specField = spec.key();
        int pages = spec.pages().size();
        String more = pages == 0 ? "" : ", " + pages + " Confluence page(s)";
        say("ingested " + spec.key() + ": " + spec.summary() + more + " — read it on the Spec tab, then Draft");
        tab = TAB_SPEC;
    }

    /** Asks Claude for cases from the spec, off the request thread. */
    synchronized void draft() {
        if (!busy.isEmpty()) {
            throw new SessionException("wait: " + busy + " is still running");
        }
        Session s = sessionOrCreate();
        Path path = s.specPath();
        if (path == null || !Files.isRegularFile(path)) {
            throw new SessionException("ingest a ticket first: Draft works from the session's spec");
        }
        busy = "Claude is drafting";
        say("Claude is drafting…");
        background.accept(() -> {
            DraftResult result;
            try {
                result = Actions.draftInto(s, this::progress, asker);
            } catch (RuntimeException e) {
                workerDone("✖ " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), null);
                return;
            }
            drafted(result);
        });
    }

    private synchronized void progress(String line) {
        say(line);
    }

    private synchronized void drafted(DraftResult result) {
        draftResult = result;
        int refused = result.cases.size() - result.clean().size();
        String more = refused > 0 ? ", " + refused + " still refused by the checker" : "";
        String missing = result.notDrafted.isEmpty() ? "" : ", " + result.notDrafted.size() + " need a new phrase";
        workerDone("Claude drafted " + result.cases.size() + " case(s)" + more + missing + " ($"
                + String.format(java.util.Locale.ROOT, "%.2f", result.costUsd)
                + ") — review every step before replaying", TAB_REVIEW);
    }

    private synchronized void workerDone(String text, String showTab) {
        busy = "";
        publishLines = null;
        say(text);
        if (showTab != null) {
            tab = showTab;
        }
    }

    // ------------------------------------------------------------ capture, approve, replay

    private void launch(boolean capture) {
        if (replaying) {
            throw new SessionException("a replay is already running");
        }
        if (runs.busy()) {
            throw new SessionException("a run is in progress; wait for it to finish");
        }
        Session s = session();
        if (s == null || s.cases().isEmpty()) {
            throw new SessionException("add a case first");
        }
        LaunchConfig base = runs.baseConfig();
        if (capture && base.fakePos()) {
            throw new SessionException("baselines are only ever captured on a rig, never the fake POS (§3.5)");
        }
        Replay suite = ReplayGate.writeSuite(s, generatedRoot, capture);
        String refusal = runs.start(ReplayGate.replayConfig(base, suite));
        if (refusal != null) {
            deleteTree(generatedRoot.resolve(suite.directory()));
            throw new SessionException(refusal);
        }
        replaying = true;
        pending = suite;
        String verb = capture ? "capturing baselines for" : "replaying";
        say(verb + " " + suite.cases().size() + " case(s) from " + suite.directory() + "; follow it on the Run page");
    }

    /** Replays the session's cases as an ordinary run. */
    synchronized void replay() {
        launch(false);
    }

    /** Runs the session's cases on a rig with {@code --snapshot-update}, to keep what they print. */
    synchronized void capture() {
        launch(true);
    }

    /** Makes the selected case's captured baselines its reference. */
    synchronized void approve() {
        Case c = selected();
        if (c == null) {
            throw new SessionException("no case selected");
        }
        int count = session.approveBaselines(c);
        say(c.title() + ": " + count + " baseline(s) approved — ▶ Replay to prove them");
    }

    /** Notices that the replay this page started has ended: keeps a capture's baselines, or judges a replay. */
    synchronized void poll() {
        if (!replaying || runs.busy()) {
            return;
        }
        replaying = false;
        Replay suite = pending;
        pending = null;
        String runId = runs.lastRunId();
        if (runId == null || runId.isEmpty() || suite == null) {
            say("✖ the run did not reach the end; nothing to judge");
            return;
        }
        Replay finished = suite.withRunId(runId);
        if (session != null) {
            if (suite.capture()) {
                int taken = session.importCapture(generatedRoot, finished);
                say("capture " + runId + " kept " + taken + " baseline file(s); read them on the Review tab and "
                        + "Approve each case's");
                session.replay(null);
                tab = TAB_REVIEW;
                return;
            }
            session.replay(finished);
        }
        Verdict verdict = verdict();
        if (verdict != null && runId.equals(verdict.runId())) {
            if (verdict.ok()) {
                say("✔ replay " + runId + " passed the gate; Publish shows what would be sent");
            } else {
                int more = verdict.reasons().size() - 1;
                say("✖ refused after replay " + runId + ": " + verdict.reasons().get(0)
                        + (more > 0 ? " (+" + more + " more on the Review tab)" : ""));
            }
        }
    }

    /** The replay gate's verdict on the session as it is now, or null when there is no session. */
    synchronized Verdict verdict() {
        Session s = session();
        if (s == null) {
            return null;
        }
        Replay last = s.replay();
        ReplayRun run = null;
        if (last != null && !last.runId().isEmpty()) {
            String id = last.runId();
            if (!runStates.containsKey(id)) {
                runStates.put(id, ReplayGate.runState(id, runs.resultsRoot()));
            }
            run = runStates.get(id);
        }
        return ReplayGate.judge(s.publishBlockers(), ReplayGate.fingerprint(s), last, run);
    }

    // ------------------------------------------------------------ publish (dry run)

    /**
     * The dry run: what a publish would send, and why it would be refused (the replay gate's verdict first). Sends
     * nothing; reads the petroapps checkout for the story branch.
     */
    synchronized void publish() {
        Session s = session();
        if (s == null || s.cases().isEmpty()) {
            throw new SessionException("nothing to publish yet");
        }
        Verdict verdict = verdict();
        Publish.PublishPlan plan = Publish.plan(s, verdict.reasons(), null, BASE_BRANCH);
        publishLines = plan.lines();
        say("publish dry run: nothing was sent"
                + (plan.ok() ? "" : "; it would be refused (" + plan.refused().size() + " reason(s))"));
        tab = TAB_REVIEW;
    }

    // ------------------------------------------------------------ helpers

    private synchronized void say(String text) {
        message = text;
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        session = Session.latest(root);
        if (session != null) {
            ticketField = session.ticket() == null ? "" : session.ticket();
            specField = session.specTicket() == null ? "" : session.specTicket();
        }
    }

    private Session sessionOrCreate() {
        Session s = session();
        if (s == null) {
            s = Session.create(root);
            session = s;
            loaded = true;
        }
        return s;
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // best effort: a written suite left behind is never reused
        }
    }

    /** Whether the session folder exists on disk (tests check that opening the page created nothing). */
    boolean rootExists() {
        return Files.isDirectory(root);
    }
}
