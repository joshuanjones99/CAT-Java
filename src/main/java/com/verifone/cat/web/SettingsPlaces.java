package com.verifone.cat.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.prefs.WebSettings;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.results.RunIndex;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.runconfig.SavedChoices;
import com.verifone.cat.sims.ToolsRoot;

/**
 * The folders CAT reads or writes, for the Settings page's "Where CAT keeps things". Each row is
 * resolved by the same code that uses it, so the page cannot disagree with the tree. Read-only:
 * nothing is created here.
 */
final class SettingsPlaces {

    /** One row: what it holds, where it is now, and the environment variable that moves it (blank if none). */
    record Place(String what, String where, String env) {}

    private SettingsPlaces() {}

    static List<Place> rows(Map<String, String> env, String userHome) {
        return List.of(
                new Place("Run results", ReportPaths.resultsRoot(env).toString(), ReportPaths.RESULTS_DIR_ENV),
                new Place("Results index", RunIndex.indexPath(env).toString(), RunIndex.INDEX_ENV),
                new Place("Downloaded builds", BuildDiscovery.buildsRoot(env).toString(),
                        BuildDiscovery.BUILDS_ROOT_ENV),
                new Place("Simulators and tools", ToolsRoot.resolve(env.get(ToolsRoot.ENV), null).toString(),
                        ToolsRoot.ENV),
                new Place("petroapps checkout", petroapps(env), "PETROAPPS_ROOT"),
                new Place("Config choices", SavedChoices.defaultPath(env, userHome).toString(),
                        SavedChoices.ENV_OVERRIDE),
                new Place("Web settings", WebSettings.defaultPath(env, userHome).toString(),
                        WebSettings.ENV_OVERRIDE),
                new Place("Rig files", RigLoader.rigsDir(env).toString(), RigLoader.RIGS_DIR_ENV),
                // The plan catalogue cache is the Python CAT's ~/.cat, which PlanCatalogue.offline also uses.
                new Place("Plan catalogue cache", Path.of(userHome, ".cat").toString(), ""));
    }

    /** {@code $PETROAPPS_ROOT} when set and not blank, else the documented default. */
    static String petroapps(Map<String, String> env) {
        String value = env.get("PETROAPPS_ROOT");
        return value == null || value.isEmpty() ? Collect.DEFAULT_PETROAPPS_ROOT : value;
    }
}
