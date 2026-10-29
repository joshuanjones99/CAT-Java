package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;
import static com.verifone.cat.web.RunBoardHtml.badge;
import static com.verifone.cat.web.RunBoardHtml.dialogGet;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.verifone.cat.devices.printer.TopazPrinter;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.runservice.ReceiptRoll;
import com.verifone.cat.runservice.RunState;
import com.verifone.cat.runservice.RunView;
import com.verifone.cat.runservice.SideBySide;
import com.verifone.cat.runservice.Validations;

/**
 * A drawn run's dialogs and its Validations view: the Python CAT's {@code cat.web.components} {@code open_step},
 * {@code open_case}, {@code open_receipts} and {@code ValidationsView}, with the facts the TUI's step detail shows
 * ({@code cat.app.detail}: state, elapsed, progress, now, result, the checklist and its counts).
 *
 * <p>A dialog is one element swapped into {@link RunBoardHtml#DIALOG}; on a live run it polls itself, and the server
 * answers {@code 204} when it would draw the same, so what the reader has open stays put. Close removes it.
 *
 * <p>A passed validation's comparison is drawn when it is first opened (its {@code <details>} fetches it), so a
 * plan's few hundred passes stay cheap; a failed one is open, and drawn, at once.
 */
final class RunDialogsHtml {

    /** Characters of a checklist row's detail shown under it; the Log has the rest. */
    static final int DETAIL_WIDTH = 300;

    /** A checklist row's glyph by outcome (the TUI's {@code SUBSTEP_GLYPHS}). */
    static final Map<String, String> GLYPHS = new LinkedHashMap<>();

    static {
        GLYPHS.put("running", "◐");
        GLYPHS.put("passed", "✔");
        GLYPHS.put("failed", "✖");
        GLYPHS.put("warned", "⚠");
        GLYPHS.put("skipped", "⊘");
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private RunDialogsHtml() {}

    /** A dialog: its title, its body, a foot with {@code extra} and Close; polling {@code url} on a live run. */
    static String modal(String id, String title, String body, String extra, boolean wide, String url, boolean live) {
        String inner = "<div class=\"run-modal" + (wide ? " wide" : "") + "\" role=\"dialog\" aria-modal=\"true\""
                + " aria-label=\"" + esc(title) + "\"><h2>" + esc(title) + "</h2>" + body
                + "<div class=\"run-modal-foot\">" + extra
                + "<button type=\"button\" onclick=\"catRun.close(this)\">Close</button></div></div>";
        return RunBoardHtml.polled("div", id, "run-modal-back", url, inner, live);
    }

    // ------------------------------------------------------------------ the step dialog

    /** The step dialog on step {@code index}: a tab per step with its state, then that step's facts and rows. */
    static String step(RunState state, int index, RunLinks links) {
        int shown = Math.max(0, Math.min(index, RunState.STEP_NAMES.size() - 1));
        List<RunView.StepRow> rows = RunView.stepRows(state);
        StringBuilder tabs = new StringBuilder("<nav class=\"run-step-tabs\" aria-label=\"Steps\">");
        for (int i = 0; i < rows.size(); i++) {
            tabs.append("<button type=\"button\" ").append(dialogGet(links.step(i)))
                    .append(i == shown ? " aria-current=\"true\"" : "").append("><span>").append(i + 1).append(". ")
                    .append(esc(rows.get(i).name())).append("</span>").append(badge(rows.get(i).status()))
                    .append("</button>");
        }
        tabs.append("</nav>");
        RunState.StepState step = state.steps().get(shown);
        StringBuilder body = new StringBuilder("<div class=\"run-rows\">");
        body.append("<div class=\"run-row\"><strong>").append(esc(step.name())).append("</strong> ")
                .append(badge(rows.get(shown).status()));
        if (step.elapsed() > 0) {
            body.append(" <span class=\"muted\">").append(esc(RunView.elapsedText(step.elapsed()))).append("</span>");
        }
        body.append("</div>");
        if (step.progressTotal() != null && step.progressTotal() != 0) {
            body.append("<div>progress ").append(step.progressDone()).append(" of ").append(step.progressTotal())
                    .append("</div>");
        } else if ("running".equals(step.status())) {
            body.append("<div class=\"muted\">progress indeterminate</div>");
        }
        if ("running".equals(step.status()) && !step.progressDetail().isEmpty()) {
            body.append("<div class=\"run-pre\">now ").append(esc(step.progressDetail())).append("</div>");
        }
        if (!step.detail().isEmpty()) {
            body.append("<div class=\"run-pre\">result ").append(esc(step.detail())).append("</div>");
        }
        body.append("<hr>");
        if (RunState.STEP_NAMES.get(shown).equals("Tests Run")) {
            body.append(testsRunRows(state));
        } else if (!step.checks().isEmpty()) {
            body.append("<div><strong>").append(esc(checklistCounts(step.checks(), "check"))).append("</strong></div>");
            body.append(subRows(step.checks()));
        } else if ("pending".equals(step.status())) {
            body.append("<p class=\"muted\">Not started yet.</p>");
        } else {
            body.append("<p class=\"muted\">This step keeps no checklist; its log is on the Log tab.</p>");
        }
        body.append("</div>");
        String content = "<div class=\"run-step-dialog\">" + tabs + "<div>" + body + "</div></div>";
        String logLink = "<a class=\"button\" href=\"" + esc(links.stepLog(step.name())) + "\">Show this step's log</a>";
        return modal("run-modal", "Step " + (shown + 1) + ": " + step.name(), content, logLink, false,
                links.step(shown), links.live());
    }

    /** Tests Run's rows: every case reached so far, in the order it started, then how many plan cases did not run. */
    static String testsRunRows(RunState state) {
        if (state.tests().isEmpty()) {
            return "<p class=\"muted\">No test case has started yet.</p>"
                    + notRunNote(state);
        }
        Map<String, RunView.NodeLabel> labels = RunView.stateLabels(state.cases(), state.tests().keySet());
        StringBuilder out = new StringBuilder();
        List<RunState.SubStep> rows = new java.util.ArrayList<>();
        for (RunState.TestState test : state.tests().values()) {
            RunView.NodeLabel label = labels.get(test.nodeid());
            String text = label == null ? test.nodeid()
                    : (label.text() + (label.function().isEmpty() ? "" : "  " + label.function()));
            rows.add(new RunState.SubStep(text, testOutcome(test.status()), RunView.reason(test.excType(),
                    test.detail()), test.duration(), 0));
        }
        out.append("<div><strong>").append(esc(checklistCounts(rows, "case"))).append("</strong></div>");
        out.append(subRows(rows));
        out.append(notRunNote(state));
        out.append("<p class=\"muted\">Each case's own steps: click the case on the board.</p>");
        return out.toString();
    }

    private static String notRunNote(RunState state) {
        int count = state.countNotRun();
        return count > 0 ? "<p class=\"muted\">" + count + " plan case(s) not run (yet)</p>" : "";
    }

    /** A test's status as a checklist row's outcome (the TUI's {@code _TEST_OUTCOMES}). */
    static String testOutcome(String status) {
        return switch (status) {
            case "running" -> "running";
            case "passed" -> "passed";
            case "failed", "error", "timeout" -> "failed";
            case "warned" -> "warned";
            default -> "skipped";
        };
    }

    /** {@code 16 checks · 14 passed · 1 warned · 1 failed}: only the outcomes present ({@code checklist_counts}). */
    static String checklistCounts(List<RunState.SubStep> rows, String noun) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RunState.SubStep row : rows) {
            counts.merge(row.outcome(), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder().append(rows.size()).append(' ').append(noun)
                .append(rows.size() == 1 ? "" : "s");
        for (String outcome : GLYPHS.keySet()) {
            Integer n = counts.get(outcome);
            if (n != null && n > 0) {
                out.append(" · ").append(n).append(' ').append(outcome);
            }
        }
        return out.toString();
    }

    /** Checklist rows: a badge, the label, its detail under it, its time beside it. */
    static String subRows(List<RunState.SubStep> rows) {
        StringBuilder out = new StringBuilder("<div class=\"run-rows\">");
        for (RunState.SubStep row : rows) {
            out.append("<div class=\"run-row\">").append(badge(row.outcome())).append("<div class=\"grow\"><div>")
                    .append(esc(row.label())).append("</div>");
            if (!row.detail().isEmpty()) {
                String detail = row.detail().length() > DETAIL_WIDTH ? row.detail().substring(0, DETAIL_WIDTH)
                        : row.detail();
                out.append("<div class=\"sub\">").append(esc(detail)).append("</div>");
            }
            out.append("</div>");
            if (row.elapsed() > 0) {
                out.append("<span class=\"muted\">").append(esc(seconds(row.elapsed()))).append("</span>");
            }
            out.append("</div>");
        }
        return out.append("</div>").toString();
    }

    /** {@code 8.2s} / {@code 2m 05s}: a row's time. */
    static String seconds(double value) {
        if (value < 60) {
            return String.format(Locale.ROOT, "%.1fs", value);
        }
        long whole = (long) value;
        return String.format(Locale.ROOT, "%dm %02ds", whole / 60, whole % 60);
    }

    // ------------------------------------------------------------------ the case dialog

    /** A case's tests and their {@code .feature} steps. */
    static String caseDetail(RunState state, String key, RunLinks links) {
        RunState.CaseState planned = state.cases().get(key);
        String title = RunView.caseLine(key, planned == null ? "" : planned.summary());
        List<RunState.TestState> tests = RunView.caseTests(state, key);
        StringBuilder body = new StringBuilder("<div class=\"run-rows\">");
        if (planned != null && planned.node() == null) {
            body.append("<p class=\"muted\">No migrated test: this case is counted as Not Run.</p>");
        }
        if (tests.isEmpty()) {
            body.append("<p class=\"muted\">Not reached yet.</p>");
        }
        for (RunState.TestState test : tests) {
            String name = test.nodeid().substring(test.nodeid().lastIndexOf("::") < 0 ? 0
                    : test.nodeid().lastIndexOf("::") + 2);
            body.append("<div class=\"run-row\">").append(badge(test.status())).append(" <span class=\"mono\">")
                    .append(esc(name)).append("</span>");
            if (test.duration() > 0) {
                body.append(" <span class=\"muted\">").append(esc(RunView.elapsedText(test.duration()))).append("</span>");
            }
            body.append("</div>");
            String failure = String.join(": ", nonEmpty(test.excType(), test.detail()));
            if (!failure.isEmpty()) {
                body.append("<pre class=\"run-pre error\">").append(esc(failure)).append("</pre>");
            }
            if (!test.steps().isEmpty()) {
                body.append(subRows(test.steps()));
            }
        }
        body.append("</div>");
        return modal("run-modal", title, body.toString(), "", false, links.caseDetail(key), links.live());
    }

    private static List<String> nonEmpty(String... parts) {
        List<String> out = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the receipt roll

    /** The Receipt Printer's roll: each receipt under its case, its check's verdict, a failed check's diff beside it. */
    static String receipts(RunState state, RunLinks links) {
        String port = "";
        for (RunState.SimulatorState sim : state.simulators()) {
            if (TopazPrinter.PRINTER_NAME.equals(sim.name())) {
                port = sim.bind();
            }
        }
        List<ReceiptRoll.Item> roll = ReceiptRoll.parse(state.logLinesFrom(TopazPrinter.RECEIPT_LOGGER));
        Map<String, String> statuses = caseStatuses(state);
        StringBuilder body = new StringBuilder("<p class=\"muted\">").append(esc(ReceiptRoll.head(roll, port)))
                .append("</p>");
        Map<String, Integer> perCase = new LinkedHashMap<>();
        for (ReceiptRoll.Item item : roll) {
            if (item instanceof ReceiptRoll.Note note) {
                boolean loud = !"INFO".equals(note.level()) && !"DEBUG".equals(note.level());
                body.append("<p class=\"").append(loud ? "warn" : "muted").append("\">").append(esc(clock(note.at())))
                        .append("  ").append(esc(note.text())).append("</p>");
                continue;
            }
            ReceiptRoll.Verdict verdict = item instanceof ReceiptRoll.Verdict v ? v : ((ReceiptRoll.Receipt) item).verdict();
            String caseKey = item instanceof ReceiptRoll.Verdict v ? v.caseKey() : ((ReceiptRoll.Receipt) item).caseKey();
            body.append("<div class=\"run-vgroup\"><h3>").append(esc(caseHeader(state, caseKey)));
            if (verdict != null) {
                body.append(' ').append(badge(verdict.passed() ? "passed" : "failed"));
                if (!verdict.baseline().isEmpty()) {
                    body.append(" <span class=\"muted\">vs ").append(esc(verdict.baseline())).append("</span>");
                }
            }
            if (item instanceof ReceiptRoll.Receipt receipt) {
                int nth = perCase.merge(caseKey, 1, Integer::sum);
                body.append(" <span class=\"muted\">printed ").append(esc(clock(receipt.at())))
                        .append(nth > 1 ? " · its receipt " + nth : "").append("</span></h3>");
                if (!caseKey.isEmpty()) {
                    String status = statuses.get(caseKey);
                    boolean running = status == null || "running".equals(status);
                    body.append("<div class=\"").append(verdict == null ? "muted" : verdict.passed() ? "ok" : "error")
                            .append("\">").append(esc(ReceiptRoll.verdictLine(verdict, running))).append("</div>");
                }
                body.append("<div class=\"run-receipt\"><pre>").append(esc(String.join("\n", receipt.lines())))
                        .append("</pre>");
                if (verdict != null && !verdict.passed() && !verdict.diff().isEmpty()) {
                    body.append("<pre class=\"diff\">").append(esc(String.join("\n", verdict.diff()))).append("</pre>");
                }
                body.append("</div></div>");
            } else {
                body.append("</h3><div class=\"muted\">a receipt not printed here was checked</div><div class=\"")
                        .append(verdict.passed() ? "ok" : "error").append("\">")
                        .append(esc(ReceiptRoll.verdictLine(verdict, false))).append("</div>");
                if (!verdict.diff().isEmpty()) {
                    body.append("<pre class=\"run-receipt diff\">").append(esc(String.join("\n", verdict.diff())))
                            .append("</pre>");
                }
                body.append("</div>");
            }
        }
        String title = TopazPrinter.PRINTER_NAME + (port.isEmpty() ? "" : " (" + port + ")");
        return modal("run-modal", title, body.toString(), "", true, links.receipts(), links.live());
    }

    private static String caseHeader(RunState state, String caseKey) {
        if (caseKey.isEmpty()) {
            return "No test case running (CAT's own hold of the port)";
        }
        RunState.CaseState planned = state.cases().get(caseKey);
        return RunView.caseLine(caseKey, planned == null ? "" : planned.summary());
    }

    /** Each case key's status, from its test. */
    static Map<String, String> caseStatuses(RunState state) {
        Map<String, String> out = new LinkedHashMap<>();
        for (RunState.TestState test : state.tests().values()) {
            String key = ReportPaths.caseKey(test.nodeid());
            if (!key.isEmpty()) {
                out.put(key, test.status());
            }
        }
        return out;
    }

    private static String clock(java.time.Instant at) {
        return at == null ? "" : CLOCK.format(at);
    }

    // ------------------------------------------------------------------ validations

    /** The Validations dialog: the view, in a wide dialog. */
    static String validationsDialog(RunState state, String tab, RunLinks links) {
        String shown = tab == null || tab.isEmpty() ? Validations.firstTab(state.validations()) : tab;
        return modal("run-modal", "Validations", validations(state, shown, links, true), "", true,
                links.validationsDialog(shown), links.live());
    }

    /** The Validations view as a page's own tab (Results), its tab buttons swapping the view in place. */
    static String validations(RunState state, String tab, RunLinks links) {
        return validations(state, tab, links, false);
    }

    /**
     * Every validation of the run on one tab (Receipts, TLog, Packets, Other), grouped by case: a row each, a failed
     * one open with the baseline and the capture side by side. {@code tab} empty opens the first failure's tab.
     */
    static String validations(RunState state, String tab, RunLinks links, boolean inDialog) {
        List<RunState.Validation> all = state.validations();
        String shown = tab == null || tab.isEmpty() ? Validations.firstTab(all) : tab;
        StringBuilder out = new StringBuilder("<div id=\"run-validations\" class=\"run-rows\">");
        if (all.isEmpty()) {
            out.append("<p class=\"muted\">No validations yet. Each receipt, TLog and packet check a test case makes "
                    + "is listed here as it is made, passed or failed, with the baseline and the capture side by "
                    + "side.</p>");
        } else {
            boolean failed = all.stream().anyMatch(v -> !v.passed());
            out.append("<p class=\"").append(failed ? "error" : "muted").append("\">")
                    .append(esc(Validations.counts(all))).append(" this run, oldest first.</p>");
        }
        out.append("<nav class=\"run-tabs\" aria-label=\"Validation kinds\">");
        for (Validations.Tab each : Validations.TABS) {
            List<RunState.Validation> rows = Validations.ofTab(all, each.id());
            if ("other".equals(each.id()) && rows.isEmpty()) {
                continue;
            }
            long bad = rows.stream().filter(v -> !v.passed()).count();
            out.append("<button type=\"button\" ")
                    .append(inDialog ? dialogGet(links.validationsDialog(each.id()))
                            : "hx-get=\"" + esc(links.validations(each.id()))
                                    + "\" hx-target=\"#run-validations\" hx-swap=\"outerHTML\"")
                    .append(each.id().equals(shown) ? " aria-current=\"true\"" : "").append('>')
                    .append(esc(each.label() + " " + rows.size() + (bad > 0 ? " · " + bad + " failed" : "")))
                    .append("</button>");
        }
        out.append("</nav>");
        List<RunState.Validation> rows = Validations.ofTab(all, shown);
        if (rows.isEmpty()) {
            out.append("<p class=\"muted\">None in this run yet.</p>");
        }
        Map<String, String> statuses = caseStatuses(state);
        for (Map.Entry<String, List<RunState.Validation>> group : Validations.groupByCase(rows).entrySet()) {
            String caseKey = group.getKey();
            String heading;
            if (caseKey.startsWith("PETROCORE-")) {
                RunState.CaseState planned = state.cases().get(caseKey);
                heading = RunView.caseLine(caseKey, planned == null ? "" : planned.summary());
            } else {
                heading = caseKey.isEmpty() ? "No test case running" : caseKey;
            }
            out.append("<section class=\"run-vgroup\"><h3>").append(esc(heading));
            String status = statuses.get(caseKey);
            if (status != null) {
                out.append(' ').append(badge(status));
            }
            out.append(" <span class=\"muted\">").append(esc(Validations.counts(group.getValue())))
                    .append("</span></h3>");
            for (RunState.Validation validation : group.getValue()) {
                int index = all.indexOf(validation);
                out.append(row(validation, index, links));
            }
            out.append("</section>");
        }
        return out.append("</div>").toString();
    }

    /** One validation's row: a passed one fetches its comparison when first opened; a failed one is open. */
    static String row(RunState.Validation validation, int index, RunLinks links) {
        StringBuilder out = new StringBuilder();
        if (validation.passed()) {
            out.append("<details hx-get=\"").append(esc(links.validation(index)))
                    .append("\" hx-trigger=\"toggle once\" hx-target=\"find .vdetail\" hx-swap=\"innerHTML\">");
        } else {
            out.append("<details open>");
        }
        out.append("<summary>").append(badge(validation.passed() ? "passed" : "failed"))
                .append("<span class=\"kind\">").append(esc(Validations.kindLabel(validation.kind()))).append("</span>")
                .append("<span class=\"mono\">").append(esc(validation.name().isEmpty() ? "its baseline"
                        : validation.name())).append("</span>")
                .append("<span class=\"").append(validation.passed() ? "muted" : "error").append("\">")
                .append(validation.passed() ? "matches" : "differs").append("</span>")
                .append("<span class=\"muted\">").append(esc(clock(validation.at()))).append("</span></summary>")
                .append("<div class=\"vdetail\">");
        if (!validation.passed()) {
            out.append(detail(validation));
        } else {
            out.append("<span class=\"muted\">Reading…</span>");
        }
        return out.append("</div></details>").toString();
    }

    /** A validation's comparison: the two texts side by side, else its diff as sent. */
    static String detail(RunState.Validation validation) {
        StringBuilder out = new StringBuilder();
        if (!validation.expected().isEmpty() || !validation.actual().isEmpty()) {
            if (validation.expected().isEmpty()) {
                out.append("<p class=\"error\">No baseline: every line below is what this run captured.</p>");
            } else if ("receipt".equals(validation.kind())) {
                out.append("<p class=\"muted\">Lines the shared receipt rule ignores (dates, counters) can differ "
                        + "in a pass.</p>");
            }
            out.append(sideBySide(validation.expected(), validation.actual()));
            if (!validation.diff().isEmpty() && !validation.passed()) {
                out.append("<details><summary>The failure as reported</summary>").append(diffLines(validation.diff()))
                        .append("</details>");
            }
            return out.toString();
        }
        if (validation.passed()) {
            return "<p class=\"muted\">Matches. This run recorded no texts to compare side by side.</p>";
        }
        return diffLines(validation.diff());
    }

    /** A diff, a line each, coloured as a diff reads. */
    static String diffLines(String diff) {
        List<String> lines = diff.isEmpty() ? List.of("(no diff was given)") : List.of(diff.split("\r?\n", -1));
        StringBuilder out = new StringBuilder("<pre class=\"run-pre diff\">");
        for (String line : lines) {
            String css = Validations.diffClass(line);
            out.append(css.isEmpty() ? "<span>" : "<span class=\"" + css + "\">").append(esc(line.isEmpty() ? " " : line))
                    .append("</span>\n");
        }
        return out.append("</pre>").toString();
    }

    /** Both texts in two columns, every line numbered on its side, a differing one coloured. */
    static String sideBySide(String expected, String actual) {
        StringBuilder rows = new StringBuilder();
        int left = 0;
        int right = 0;
        for (SideBySide.Row row : SideBySide.rows(expected, actual)) {
            rows.append("<tr class=\"").append(row.kind()).append("\">");
            if (row.left() == null) {
                rows.append("<td class=\"n\"></td><td class=\"l gap\"></td>");
            } else {
                left++;
                rows.append("<td class=\"n\">").append(left).append("</td><td class=\"l\">")
                        .append(row.left().isEmpty() ? " " : esc(row.left())).append("</td>");
            }
            if (row.right() == null) {
                rows.append("<td class=\"n\"></td><td class=\"r gap\"></td>");
            } else {
                right++;
                rows.append("<td class=\"n\">").append(right).append("</td><td class=\"r\">")
                        .append(row.right().isEmpty() ? " " : esc(row.right())).append("</td>");
            }
            rows.append("</tr>");
        }
        return "<table class=\"run-sbs\"><colgroup><col class=\"n\"><col><col class=\"n\"><col></colgroup>"
                + "<thead><tr><th colspan=\"2\">Expected (baseline)</th><th colspan=\"2\">Actual (this run)</th></tr>"
                + "</thead><tbody>" + rows + "</tbody></table>";
    }
}
