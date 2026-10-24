package com.verifone.cat.report.html;

import java.util.Locale;
import java.util.Map;

/**
 * Glyph, colour and label for every outcome a run report shows.
 *
 * <p>The Python CAT's {@code petrotest.report.html.OutcomeInfo}. Two vocabularies meet here:
 * the harness outcomes ({@code pass}, {@code fail}, {@code error}, {@code timeout},
 * {@code repaired}, {@code unrepairable}, as {@code petrotest.harness.base.Outcome} spells
 * them) and the wire's test outcomes ({@code passed}, {@code failed}, {@code skipped}, plus
 * {@code repairing}). Every harness value has its own glyph, so the six read apart in
 * monochrome as well as in colour.
 *
 * <p>An unknown outcome throws {@link IllegalArgumentException}, as the Python raises
 * {@code ValueError}: a new value must get a glyph here rather than be drawn as nothing.
 *
 * <p>Glyphs are written as unicode escapes so the source file's encoding cannot change them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: rendering only, checked by fake-backed tests.
 */
final class OutcomeInfo {

    /** One outcome's glyph and colour. */
    record Style(String glyph, String color) {}

    private static final Map<String, Style> STYLES = Map.ofEntries(
            // Harness outcomes.
            Map.entry("pass", new Style("✔", "#28a745")),
            Map.entry("fail", new Style("✖", "#dc3545")),
            Map.entry("error", new Style("⊘", "#fd7e14")),
            Map.entry("timeout", new Style("⟲", "#ff9800")),
            Map.entry("repaired", new Style("⟳", "#0275d8")),
            Map.entry("unrepairable", new Style("⊗", "#8b1a1a")),
            // Wire test outcomes.
            Map.entry("passed", new Style("✔", "#28a745")),
            Map.entry("failed", new Style("✖", "#dc3545")),
            Map.entry("skipped", new Style("·", "#999999")),
            // `cat.report.events.Outcome.REPAIRING`, legal on preflight and recovery events.
            Map.entry("repairing", new Style("⟳", "#0275d8")));

    private OutcomeInfo() {}

    /** The glyph for {@code outcome}. Throws for a value neither vocabulary has. */
    static String glyph(String outcome) {
        return style(outcome).glyph();
    }

    /** The colour for {@code outcome}, as {@code #RRGGBB}. Throws for an unknown value. */
    static String color(String outcome) {
        return style(outcome).color();
    }

    /** The upper-case label for {@code outcome}, e.g. {@code UNREPAIRABLE}. Throws for an unknown value. */
    static String label(String outcome) {
        style(outcome);
        return outcome.toUpperCase(Locale.ROOT);
    }

    private static Style style(String outcome) {
        Style style = outcome == null ? null : STYLES.get(outcome);
        if (style == null) {
            throw new IllegalArgumentException("Unknown outcome: " + outcome);
        }
        return style;
    }
}
