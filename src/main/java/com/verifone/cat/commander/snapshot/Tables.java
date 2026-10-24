package com.verifone.cat.commander.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evidence-driven tables of {@code commander_snapshot.py}: what changes on its own ({@code VOLATILE}), what is
 * compared sorted ({@code UNORDERED}), the entity defaults ({@code DEFAULTS}), the operations that empty an upsert-only
 * table before a document is sent, and the keys that pair repeated siblings in a merge. Each entry carries the run
 * that justified it in the Python; grow them from {@code capture --twice}, never from guesses.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Tables {

    /**
     * Area -> paths to drop from every comparison. A path is local element names from the root, {@code /}-separated,
     * matched as a glob; {@code path@attr} drops an attribute.
     */
    static final Map<String, List<String>> VOLATILE = table(
            "panelcfg", "panelCfg@creationDate",
            "registercontrolscfg", "registerControlsConfig@creationDate",
            "scocategorycfg", "scoCategoryConfig@creationDate",
            "scoglobalcfg", "scoGlobalConfig@creationDate",
            "scoregistercfg", "scoRegisterConfig@creationDate",
            "screencfgv2", "screenCfg@creationDate");

    /** Area -> paths whose children's order carries no meaning, compared sorted. */
    static final Map<String, List<String>> UNORDERED = table(
            "emvinit", "popEMVInitialize/availablePOPEntries",
            "managedmodulecfg", "moduleConfigurations");

    /** A single-row area with no delete: its entity defaults, which a value equal to reads as absent. */
    static final Map<String, Map<String, String>> DEFAULTS = Map.of(
            "tlssite", defaults("unitOfMeasure", "G", "tankVariance", "0", "lowLimitAlarm", "0"));

    /** Areas whose update only upserts: the operation that empties the table, first in the document. */
    static final Map<String, String> EMPTY_FIRST = Map.of(
            "cashrecyclercfg", "<operation type=\"empty\"><cashRecycler id=\"1\"/></operation>",
            "imagecfg", "<operation type=\"empty\"/>",
            "menucfg", "<operation type=\"empty\"/>",
            "panelcfg", "<operation type=\"empty\"/>",
            "screencfgv2", "<operation type=\"empty\"/>");

    /** Areas whose empty works only inside one table's element. */
    static final Map<String, String> EMPTY_IN_TABLE = Map.of(
            "plupromocfg", "pluPromos",
            "restrictionscfg", "pluPromos");

    /** Areas whose operation never closes, so the empty is a document of its own. */
    static final Map<String, String> EMPTY_ALONE = Map.of(
            "empprefcfg", "<operation type=\"empty\"/><empPrefs sysid=\"1\">"
                    + "<virtualKeyboardCoordinates deviceType=\"topaz\"/></empPrefs>");

    /** The child or attribute that names one of several same-tag siblings, tried in order. */
    static final List<String> KEY_NAMES = List.of(
            "sysid", "id", "moduleName", "name", "number", "num", "code", "key");

    private Tables() {}

    private static Map<String, List<String>> table(String... keysAndPaths) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndPaths.length; i += 2) {
            out.computeIfAbsent(keysAndPaths[i], k -> new ArrayList<>()).add(keysAndPaths[i + 1]);
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> defaults(String... tagsAndValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < tagsAndValues.length; i += 2) {
            out.put(tagsAndValues[i], tagsAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

}
