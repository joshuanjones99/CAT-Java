package com.verifone.cat.web;

/** Results. Not built yet: a placeholder for {@code cat/web/pages/results.py}. */
final class ResultsPage {

    private ResultsPage() {}

    static String body() {
        return Placeholder.html(
                "Runs by segment (rig, fake POS, no rig), a filter, and Statistics with the pass-rate chart. "
                        + "Click a run to open its board, Validations and log.",
                "cat/web/pages/results.py");
    }
}
