package com.verifone.cat.web;

/** Launch Simulators. Not built yet: a placeholder for {@code cat/web/pages/simulators.py}. */
final class SimulatorsPage {

    private SimulatorsPage() {}

    static String body() {
        return Placeholder.html(
                "Each simulator and connector with what is running now. Start becomes a red Stop that closes "
                        + "every simulator. Select all and Select none.",
                "cat/web/pages/simulators.py");
    }
}
