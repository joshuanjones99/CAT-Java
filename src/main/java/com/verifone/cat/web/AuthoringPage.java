package com.verifone.cat.web;

/** Authoring. Not built yet: a placeholder for {@code cat/web/pages/authoring.py}. */
final class AuthoringPage {

    private AuthoringPage() {}

    static String body() {
        return Placeholder.html(
                "Jira, case and validation toolbars above the cases table, and the Feature, Steps, Spec and "
                        + "Review editor. Record and fake POS controls are omitted by request.",
                "cat/web/pages/authoring.py");
    }
}
