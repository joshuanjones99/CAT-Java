package com.verifone.cat.authoring;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.GitUtil;
import com.verifone.cat.validate.PyRepr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Publishing an Authoring session, as a dry run only. Port of {@code cat.authoring.publish}'s plan.
 *
 * <p>The plan says every Xray mutation, petroapps file and git command a publish would make, in order, and sends none of
 * them. There is no code path here that writes to Xray, Jira, Confluence, petroapps or a git remote. The only git calls are
 * read-only ref checks, to say whether the story's branch already exists (D23).
 *
 * <p>Not ported: the replay gate. {@code plan} takes the gate's refusal reasons as a list, where the Python takes its
 * {@code Verdict}; the caller decides them. Every Xray mutation's shape is the Python's, from Xray's GraphQL docs, never
 * sent from here.
 */
public final class Publish {

    /** Xray's project for authored tests. */
    public static final String PROJECT = "PETROCORE";

    /** Where a case's files go in petroapps. */
    public static final String CASES_DIR = "AutomationTests/testcases";

    /** The label every authored test carries in Jira, so they can be found as a set. */
    public static final String LABEL = "CAT-authored";

    private static final Pattern CASE_HEADER = Pattern.compile("\\s*Scenario( Outline)?:.*");
    private static final String CREATE_TEST =
            "mutation { createTest(testType: {name: \"Cucumber\"}, gherkin: %s, jira: {fields: %s}) "
                    + "{ test { issueId jira(fields: [\"key\"]) } warnings } }";

    private Publish() {}

    /** One step of the plan: its kind ({@code xray}, {@code file}, {@code git}, ...), what it does, and its detail. */
    public record Action(String kind, String what, String detail) {

        Action(String kind, String what) {
            this(kind, what, "");
        }
    }

    /** The plan: the reasons a publish is refused (empty when it would go), and the actions in order. */
    public static final class PublishPlan {
        final List<String> refused;
        final List<Action> actions = new ArrayList<>();

        PublishPlan(List<String> refused) {
            this.refused = List.copyOf(refused);
        }

        public List<String> refused() {
            return refused;
        }

        public List<Action> actions() {
            return actions;
        }

        public boolean ok() {
            return refused.isEmpty();
        }

        /** The plan as the Python prints it. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add("DRY RUN — nothing below is sent. The first live publish is the rig owner's.");
            if (!refused.isEmpty()) {
                out.add("A publish would be REFUSED (" + refused.size() + " reason(s)):");
                for (String reason : refused) {
                    out.add("  ✖ " + reason);
                }
                out.add("What it would do once the gate passes:");
            } else {
                out.add("The replay gate passed. What a publish would do, in order:");
            }
            int number = 0;
            for (Action action : actions) {
                number++;
                out.add(String.format("%3d. [%s] %s", number, action.kind(), action.what()));
                for (String line : action.detail().split("\n")) {
                    if (!line.isEmpty()) {
                        out.add("        " + line);
                    }
                }
            }
            return out;
        }
    }

    /**
     * The scenario's steps (and Examples) as Xray's Cucumber field holds them. Xray keeps the scenario's name as the test's
     * summary and its tags as labels, so the Feature line, tags, comments and the {@code Scenario:} line are not repeated.
     */
    public static String gherkinBody(String text) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (CASE_HEADER.matcher(lines[i]).matches()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        List<String> body = new ArrayList<>();
        for (int i = start + 1; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                body.add(lines[i]);
            }
        }
        int indent = Integer.MAX_VALUE;
        for (String line : body) {
            indent = Math.min(indent, line.length() - line.stripLeading().length());
        }
        if (body.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String line : body) {
            out.add(line.substring(Math.min(indent, line.length())));
        }
        return String.join("\n", out);
    }

    /**
     * The publish a session would make, as data. Reads only.
     *
     * @param gateReasons the replay gate's refusal reasons (empty when the gate passed)
     * @param petroapps   the petroapps checkout, or null for {@code $PETROAPPS_ROOT} (the default)
     * @param base        the branch the story's branch is cut from
     */
    public static PublishPlan plan(Session session, List<String> gateReasons, Path petroapps, String base) {
        Path repo = petroapps != null ? petroapps : Path.of(Collect.defaultPetroappsRoot());
        List<String> refused = new ArrayList<>(gateReasons);
        String story = session.ticket();
        String branch = story != null ? story : "<story ticket>";
        if (story != null && Files.exists(repo.resolve(".git"))) {
            String exists = branchState(repo, story);
            if (!exists.isEmpty()) {
                refused.add("petroapps already has branch " + story + " (" + exists + "): abort, write nothing (D23)");
            }
        } else if (story != null) {
            refused.add("no petroapps checkout at " + repo);
        }
        PublishPlan plan = new PublishPlan(refused);
        List<String> keys = new ArrayList<>();
        List<Case> cases = session.cases();
        for (int n = 1; n <= cases.size(); n++) {
            CaseReview review = session.review(cases.get(n - 1));
            String key = "<new key " + n + ">";
            keys.add(key);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("project", Map.of("key", PROJECT));
            fields.put("summary", review.title());
            fields.put("labels", List.of(LABEL));
            if (story != null) {
                fields.put("description", "Authored in CAT for " + story + ".");
            }
            plan.actions.add(new Action("xray",
                    "createTest — Cucumber — " + PyRepr.reprString(review.title()) + " -> " + key,
                    String.format(CREATE_TEST, PyJson.dumps(gherkinBody(review.text())), PyJson.dumps(fields))));
        }
        plan.actions.add(new Action("xray",
                "createTestSet with " + keys.size() + " test(s), in session order",
                "testIssueIds: [" + String.join(", ", keys) + "]  summary: " + branch + " — CAT authored cases"));
        List<String> files = new ArrayList<>();
        for (int n = 1; n <= cases.size(); n++) {
            Case c = cases.get(n - 1);
            String key = "<new key " + n + ">";
            String folder = CASES_DIR + "/" + key;
            files.add(folder + "/" + key + "-TestCase.feature  (tag @" + key + ")");
            for (Path baseline : session.baselineFiles(c)) {
                String tail = baseline.getFileName().toString().substring(Session.BASE.length());
                files.add(folder + "/" + key + tail);
            }
        }
        plan.actions.add(new Action("file",
                files.size() + " file(s) into petroapps, nothing PATS-shaped (D18)", String.join("\n", files)));
        plan.actions.add(new Action("git", "git -C " + repo + " fetch origin " + base));
        plan.actions.add(new Action("git",
                "commit those files on a new local branch " + branch + ", parent origin/" + base,
                "through a throwaway index, as `petrotest publish` does: working tree untouched"));
        plan.actions.add(new Action("git", "git -C " + repo + " push origin " + branch,
                "never master, never --force (D17, D21)"));
        plan.actions.add(new Action("xray",
                "createTestPlan for " + branch + " with the " + keys.size() + " test(s)",
                "or addTestsToTestPlan on an existing plan"));
        plan.actions.add(new Action("confluence", "add the plan's row to the Automated-Features page (D16)",
                "a surgical insertion and a versioned PUT; its body shape is unconfirmed (1.12)"));
        return plan;
    }

    /** Empty when the branch is free; else where it already exists, read from the refs (never written). */
    static String branchState(Path petroapps, String branch) {
        List<String> found = new ArrayList<>();
        for (String ref : List.of("refs/heads/" + branch, "refs/remotes/origin/" + branch)) {
            GitUtil.Output done = GitUtil.run(petroapps, List.of("rev-parse", "--verify", "--quiet", ref),
                    GitUtil.NO_PROMPT, null, Duration.ofSeconds(30));
            if (done.exitCode() == 0) {
                found.add(ref);
            }
        }
        return String.join(", ", found);
    }
}
