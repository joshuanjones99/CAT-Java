package com.verifone.cat.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.authoring.Case;
import com.verifone.cat.authoring.Ingest;
import com.verifone.cat.authoring.Publish;
import com.verifone.cat.authoring.Session;
import com.verifone.cat.authoring.SessionException;

/**
 * What the Authoring page holds: the open session, the selected case, the tab, the step filter and the
 * last message. Port of the state half of {@code cat/web/pages/authoring.py} ({@code AuthoringState}).
 *
 * <p>Every action is called through {@link #act}: a refusal (a bad ticket, no case selected, an unreadable
 * Jira) becomes the page's message, never a failed request. Nothing here starts a rig, a replay or Claude;
 * the parts that need them are named as not built in {@link AuthoringPage}.
 *
 * <p>The session root is injected, so a test never touches {@code ~/.cat}. Opening the page creates nothing:
 * a session is created only by an action that needs one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): a session is files; Jira is read-only and the dry run sends nothing.
 */
final class AuthoringService {

    static final String TAB_FEATURE = "feature";
    static final String TAB_STEPS = "steps";
    static final String TAB_SPEC = "spec";
    static final String TAB_REVIEW = "review";
    static final List<String> TABS = List.of(TAB_FEATURE, TAB_STEPS, TAB_SPEC, TAB_REVIEW);

    /** The Python module that would build Draft with Claude. */
    static final String DRAFT_MODULE = "cat/authoring/draft.py";

    /** The Python module that would build Capture and Replay (write_suite, the replay gate). */
    static final String REPLAY_MODULE = "cat/authoring/replay.py";

    /** The gate reason a publish carries until the replay gate is ported: nothing has been replayed. */
    static final String GATE_REASON = "the replay gate is not built yet (" + REPLAY_MODULE
            + "): nothing has been replayed";

    /** The branch a publish would cut its story branch from (the Python's default). */
    static final String BASE_BRANCH = "master";

    private static final Duration JIRA_TIMEOUT = Duration.ofSeconds(20);

    /** A page action that may refuse by throwing. */
    @FunctionalInterface
    interface Action {
        void run();
    }

    private final Path root;
    private final Supplier<AtlassianClient> atlassian;
    private boolean loaded;
    private Session session;
    private int cursor;
    private String message = "";
    private String tab = TAB_FEATURE;
    private String stepFilter = "";
    private String ticketField = "";
    private String specField = "";
    private List<String> publishLines;

    AuthoringService(Path root, Supplier<AtlassianClient> atlassian) {
        this.root = root;
        this.atlassian = atlassian;
    }

    /** The operator's own: {@code $CAT_AUTHORING_DIR}, else {@code ~/.cat/java/authoring}; Jira from the credentials file. */
    static AuthoringService standard() {
        Path fallback = Path.of(System.getProperty("user.home"), ".cat", "java", "authoring");
        return new AuthoringService(Session.authoringRoot(System.getenv(), fallback),
                () -> AtlassianClient.forCredentialsFile(AtlassianCredentials.defaultPath(), JIRA_TIMEOUT));
    }

    /** Runs {@code view} with the state held still, so one page is drawn from one moment. */
    synchronized <T> T view(Function<AuthoringService, T> view) {
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
        session = Session.create(root);
        loaded = true;
        cursor = 0;
        publishLines = null;
        ticketField = "";
        specField = "";
        say("new session " + session.slug());
    }

    // ------------------------------------------------------------ ingest, approve, dry run

    /** Reads a Jira spec ticket into the session (read-only). Creates the session when there is none. */
    synchronized void ingest(String key) {
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
        say("ingested " + spec.key() + ": " + spec.summary() + more + " — read it on the Spec tab");
        tab = TAB_SPEC;
    }

    /** Makes the selected case's captured baselines its reference. */
    synchronized void approve() {
        Case c = selected();
        if (c == null) {
            throw new SessionException("no case selected");
        }
        int count = session.approveBaselines(c);
        say(c.title() + ": " + count + " baseline(s) approved");
    }

    /**
     * The dry run: what a publish would send, and why it would be refused. Sends nothing; reads the petroapps
     * checkout for the story branch. The replay gate is not built, so its reason is always listed.
     */
    synchronized void publish() {
        Session s = session();
        if (s == null || s.cases().isEmpty()) {
            throw new SessionException("nothing to publish yet");
        }
        List<String> gate = new ArrayList<>(s.publishBlockers());
        gate.add(GATE_REASON);
        Publish.PublishPlan plan = Publish.plan(s, gate, null, BASE_BRANCH);
        publishLines = new ArrayList<>(plan.lines());
        say("publish dry run: nothing was sent"
                + (plan.ok() ? "" : "; it would be refused (" + plan.refused().size() + " reason(s))"));
        tab = TAB_REVIEW;
    }

    // ------------------------------------------------------------ helpers

    private void say(String text) {
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

    /** Whether the session folder exists on disk (tests check that opening the page created nothing). */
    boolean rootExists() {
        return Files.isDirectory(root);
    }
}
