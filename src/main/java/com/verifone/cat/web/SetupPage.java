package com.verifone.cat.web;

/** Setup. Not built yet: a placeholder for {@code cat/web/pages/setup.py}. */
final class SetupPage {

    private SetupPage() {}

    static String body() {
        return Placeholder.html(
                "Every setup item and its result, Repair, the sign-in forms (filled with what is saved, secrets "
                        + "masked), and Check again.",
                "cat/web/pages/setup.py");
    }
}
