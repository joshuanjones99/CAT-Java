package com.verifone.cat.runconfig;

import java.util.List;

/**
 * The Downloads section as the Config form sees it: the Python's {@code DownloadsModel} parts that
 * {@code cat.web.form.ConfigForm} reads. The web layer's Downloads service implements it; the form restores it from
 * the saved file, saves it on every change, puts its blockers in {@link ConfigForm#missingRequirements()} and its
 * choice in the launch fields.
 */
public interface DownloadsState {

    /**
     * The saved-file keys, in the Python's {@code DownloadChoice} field order ({@code _SAVED_DOWNLOADS}): the
     * values {@link #saved()} returns and {@link #restore} takes.
     */
    List<String> KEYS = List.of("config-cmd-branch", "config-cmd-build", "config-upgrade", "config-sim-branch",
            "config-sim-build", "config-fnd-branch", "config-fnd-build", "config-dist", "config-cmd-local-build");

    /** The choice: what a run or an install takes from the Downloads section. */
    record Choice(String commanderBranch, String commanderBuild, String upgradeMode, String simBranch,
            String simBuild, String foundationBranch, String foundationBuild, String dist, String localBuild) {

        /** The values in {@link #KEYS} order. */
        public List<String> values() {
            return List.of(commanderBranch, commanderBuild, upgradeMode, simBranch, simBuild, foundationBranch,
                    foundationBuild, dist, localBuild);
        }
    }

    /** The current choice. */
    Choice current();

    /** Sets every choice at once (a restore from the saved file); listings follow. Never notifies the listener. */
    void restore(Choice choice);

    /** What these choices lack before a run or an install can start, in the Python's words. */
    List<String> problems();

    /** {@code listener} runs after every change the operator makes (the form saves then). */
    void onChange(Runnable listener);
}
