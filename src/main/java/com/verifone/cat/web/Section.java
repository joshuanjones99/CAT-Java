package com.verifone.cat.web;

/**
 * The sidebar's pages, in order, as docs/web-ui.md lays them out. Run's Overview, Config
 * and Log are tabs inside Run, so they are routes under {@code /run}, not sections.
 *
 * <p>POS is not in the Python CAT. It is the proof of concept this port started from, kept
 * as a page of the shell until the Python pages it stands beside are ported.
 */
public enum Section {
    RUN("Run", "/run"),
    RESULTS("Results", "/results"),
    AUTHORING("Authoring", "/authoring"),
    UPGRADE("Install Upgrade", "/upgrade"),
    SIMULATORS("Launch Simulators", "/simulators"),
    SETUP("Setup", "/setup"),
    SETTINGS("Settings", "/settings"),
    TOOLS("Tools", "/tools"),
    POS("POS", "/pos");

    private final String label;
    private final String path;

    Section(String label, String path) {
        this.label = label;
        this.path = path;
    }

    public String label() {
        return label;
    }

    public String path() {
        return path;
    }
}
