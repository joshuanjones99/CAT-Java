package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.verifone.cat.authoring.Case;
import com.verifone.cat.authoring.Check.Diagnostic;
import com.verifone.cat.authoring.CaseReview;
import com.verifone.cat.authoring.Draft;
import com.verifone.cat.authoring.ReplayGate;
import com.verifone.cat.authoring.Session;
import com.verifone.cat.validate.PyRepr;

/**
 * The Authoring page's evidence: the checker's findings under the editor, the cases table's cells, and the
 * Review tab. Ported from {@code render_diagnostics}, {@code render_review} and the cell helpers of
 * {@code cat/panels/authoring.py}; the wording is the Python's.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class AuthoringReview {

    /** The Spec tab's text when no spec was ingested. */
    static final String NO_SPEC = "No spec yet. Type a ticket key above and press Ingest: its description, acceptance "
            + "criteria, comments, links and Confluence pages are read into one file, which is all "
            + "Draft with Claude is given.";

    /** The Review tab shows at most this many lines of each baseline. */
    static final int BASELINE_LINES = 80;

    private AuthoringReview() {}

    /** A case's source as the table shows it. */
    static String source(String source) {
        return switch (source) {
            case "written" -> "written";
            case "recorded" -> "● recorded";
            case "drafted" -> "✎ Claude";
            default -> source;
        };
    }

    /** The Check column: errors, then warnings, then a tick. */
    static String checkCell(CaseReview review) {
        int errors = review.analysis().errors().size();
        int warnings = review.analysis().warnings().size();
        if (errors > 0) {
            return "<span class=\"bad\">✖ " + errors + "</span>";
        }
        if (warnings > 0) {
            return "<span class=\"warn\">⚠ " + warnings + "</span>";
        }
        return "<span class=\"ok\">✔</span>";
    }

    /** The Baselines column. */
    static String baselinesCell(CaseReview review) {
        if (review.baselines().isEmpty()) {
            return "—";
        }
        if (review.approved()) {
            return "<span class=\"ok\">✔ approved</span>";
        }
        return "<span class=\"warn\">" + review.baselines().size() + " to approve</span>";
    }

    /** Under the editor: what the checker says about the draft, by line. */
    static String diagnostics(CaseReview review) {
        if (review == null) {
            return "";
        }
        if (review.analysis().diagnostics().isEmpty()) {
            String touches = review.analysis().fixtures().isEmpty()
                    ? "nothing" : String.join(", ", review.analysis().fixtures());
            return "<p class=\"ok\">✔ every line is a library phrase · uses " + esc(touches) + "</p>";
        }
        StringBuilder out = new StringBuilder();
        for (Diagnostic d : review.analysis().diagnostics()) {
            String tone = "error".equals(d.severity()) ? "bad" : "warn";
            out.append("<div class=\"").append(tone).append("\">line ").append(d.line()).append(": ")
                    .append(esc(d.message())).append("</div>");
        }
        return out.toString();
    }

    /** The Spec tab: the ingested spec file, or the empty-state text. */
    static String spec(Session session) {
        Path path = session == null ? null : session.specPath();
        if (path == null || !Files.isRegularFile(path)) {
            return "<p class=\"muted\">" + esc(NO_SPEC) + "</p>";
        }
        try {
            return "<pre class=\"authoring-pre\">"
                    + esc(Files.readString(path, StandardCharsets.UTF_8)) + "</pre>";
        } catch (IOException e) {
            return "<p class=\"bad\">" + esc("(unreadable: " + e.getMessage() + ")") + "</p>";
        }
    }

    /**
     * The Review tab: the selected case's evidence (its recording, its baselines), the last draft, the replay gate's
     * verdict, and the last dry run. The Python's {@code render_review}.
     *
     * @param review  the selected case's review, or null
     * @param verdict the replay gate's verdict on the session now
     * @param draft   the last draft's outcome, or null
     * @param publish the last dry run's lines, or null
     */
    static String review(Session session, CaseReview review, ReplayGate.Verdict verdict, Draft.DraftResult draft,
            List<String> publish) {
        if (session == null) {
            return "<p class=\"muted\">No authoring session yet. Type a case name and press <b>New case</b>, "
                    + "or ingest a Jira ticket and press <b>Draft with Claude</b>.</p>";
        }
        StringBuilder out = new StringBuilder();
        if (review != null) {
            out.append("<p><b>").append(esc(review.title())).append("</b> &nbsp; case ")
                    .append(review.number()).append(" of ").append(session.cases().size()).append(" · ")
                    .append(esc(source(review.caseRecord().source()))).append("</p>");
            out.append("<p class=\"muted\">replays as PETROCORE-").append(review.number())
                    .append("-TestCase.feature — a placeholder until publishing gives it the key Xray "
                            + "assigns (D25)</p>");
            if (review.recording() != null) {
                if (review.fakePos()) {
                    out.append("<p class=\"warn\"><b>FAKE POS — evidence about this repository, not a rig</b></p>");
                }
                out.append("<p>").append(esc("recorded " + PyRepr.str(review.recording().header().get("started_at"))
                        + " from " + PyRepr.repr(review.recording().header().get("source")))).append("</p>");
            }
            if (review.recordingError() != null) {
                out.append("<p class=\"bad\">✖ recording unreadable: ").append(esc(review.recordingError()))
                        .append("</p>");
            }
            out.append("<h3>Baselines</h3>");
            if (review.baselines().isEmpty()) {
                out.append("<p class=\"muted\">none yet — ◉ Capture runs the cases on a rig and keeps what they "
                        + "print</p>");
            } else {
                String state = review.approved() ? "<span class=\"ok\">approved</span>"
                        : "<span class=\"warn\">awaiting approval</span>";
                out.append("<p>captured in run ").append(esc(review.capturedIn().isEmpty() ? "?" : review.capturedIn()))
                        .append(" · ").append(state).append("</p>");
                for (Path path : review.baselines()) {
                    out.append("<p><b>").append(esc(path.getFileName().toString())).append("</b></p>");
                    out.append("<pre class=\"authoring-pre\">").append(esc(baselineText(path))).append("</pre>");
                }
            }
        }
        if (draft != null) {
            out.append("<h3>Last draft</h3><p>").append(esc(draft.cases.size() + " case(s), " + draft.clean().size()
                    + " clean, " + draft.rounds + " round(s), " + (draft.model.isEmpty() ? "model ?" : draft.model)
                    + ", $" + String.format(Locale.ROOT, "%.2f", draft.costUsd))).append("</p>");
            for (Map<String, Object> item : draft.notDrafted) {
                List<String> missing = new ArrayList<>();
                if (item.get("missing_phrases") instanceof List<?> list) {
                    list.forEach(m -> missing.add(PyRepr.str(m)));
                }
                out.append("<div class=\"warn\">not drafted: ")
                        .append(esc(PyRepr.str(item.getOrDefault("title", "?")))).append(" — needs a phrase: ")
                        .append(esc(String.join("; ", missing))).append("</div>");
            }
            if (!draft.notes.isEmpty()) {
                out.append("<p class=\"muted\">").append(esc(draft.notes)).append("</p>");
            }
        }
        out.append("<h3>Replay gate</h3><p class=\"muted\">what publishing must pass</p>");
        if (verdict == null) {
            out.append("<p class=\"muted\">… reading the replayed run</p>");
        } else if (verdict.ok()) {
            out.append("<p class=\"ok\">✔ passed on replay ").append(esc(verdict.runId())).append("</p>");
        } else {
            for (String reason : verdict.reasons()) {
                out.append("<div class=\"bad\">✖ ").append(esc(reason)).append("</div>");
            }
        }
        if (publish != null) {
            out.append("<h3>Publish (dry run)</h3><pre class=\"authoring-pre\">");
            for (String line : publish) {
                out.append(esc(line)).append('\n');
            }
            out.append("</pre>");
        }
        return out.toString();
    }

    /** The baseline's first lines, decoded as UTF-8 with bad bytes replaced (the Python reads with errors=replace). */
    private static String baselineText(Path path) {
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            return String.join("\n", List.of(lines).subList(0, Math.min(lines.length, BASELINE_LINES)));
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
