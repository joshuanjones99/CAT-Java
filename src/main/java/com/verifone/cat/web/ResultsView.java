package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.verifone.cat.results.RunSummary;

/**
 * The words and small pieces the Results page shares: the segment labels, the status tones,
 * the times and the links, as the Python CAT's {@code cat.panels.results} and
 * {@code cat.labels} draw them. Everything here returns escaped HTML or plain text.
 */
final class ResultsView {

    /** The segments in the order the page offers them. Never mixed (CAT_RESULTS_AND_GENERATE.md §2.4). */
    static final List<String> SEGMENTS = List.of("rig", "fake_pos", "no rig");

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private ResultsView() {}

    /** The segment a query named, or {@code rig} for anything else. */
    static String segment(String requested) {
        return requested != null && SEGMENTS.contains(requested) ? requested : "rig";
    }

    static String segmentLabel(String segment) {
        return switch (segment) {
            case "fake_pos" -> "Fake POS — not a rig";
            case "no rig" -> "no rig named";
            default -> "rig runs";
        };
    }

    /** The CSS tone class for a verdict word: ok, error, warn or muted. */
    static String tone(String status) {
        if (status == null) {
            return "muted";
        }
        return switch (status) {
            case "passed", "warned" -> "ok";
            case "failed", "error", "timeout" -> "error";
            case "cancelled" -> "warn";
            default -> "muted";
        };
    }

    /** {@code 12s}, {@code 1m 05s} or {@code 1h 02m}, as {@code cat.labels.elapsed_text} shows them. */
    static String elapsed(double seconds) {
        long whole = (long) seconds;
        if (whole < 60) {
            return whole + "s";
        }
        long minutes = whole / 60;
        long secs = whole % 60;
        if (minutes < 60) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        }
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60);
    }

    /** A run's start in the operator's local time, or the first 16 characters when it does not parse. */
    static String local(String startedAt) {
        try {
            return OffsetDateTime.parse(startedAt).atZoneSameInstant(ZoneId.systemDefault()).format(LOCAL);
        } catch (RuntimeException e) {
            return startedAt.length() > 16 ? startedAt.substring(0, 16) : startedAt;
        }
    }

    /** The plans as the run chose them: each name with its key where it recorded one, else the keys. */
    static String plans(RunSummary row) {
        if (row.testPlans().isEmpty()) {
            return "(no plan)";
        }
        boolean named = row.planNames().size() == row.testPlans().size();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < row.testPlans().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            String key = row.testPlans().get(i);
            out.append(named ? row.planNames().get(i) + " (" + key + ")" : key);
        }
        return out.toString();
    }

    /** The rig as the table shows it: the rig's name, or "no rig", and "fake POS" for a fake run. */
    static String rig(RunSummary row) {
        String name = row.rig() == null || row.rig().isEmpty() ? "no rig" : row.rig();
        return row.fakePos() ? name + " · fake POS" : name;
    }

    /** A query value, encoded for a URL and then escaped for an attribute. */
    static String query(String value) {
        return esc(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
    }

    /** The status as a coloured word. The word is always shown, never only the colour. */
    static String badge(String status) {
        return "<span class=\"badge " + tone(status) + "\">" + esc(status) + "</span>";
    }
}
