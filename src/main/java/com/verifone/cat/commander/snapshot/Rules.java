package com.verifone.cat.commander.snapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The comparison exceptions a diff, a restore or a merge applies: {@code VOLATILE} (paths dropped) and
 * {@code UNORDERED} (paths whose children are compared sorted). {@link #DEFAULT} is the committed tables in
 * {@link Tables}; a test that needs another entry builds one with {@link #withVolatile}, where the Python's tests
 * monkeypatch the module's dicts.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param volatilePaths area (or base area) -> paths to drop
 * @param unordered     area (or base area) -> paths whose children are sorted before comparing
 */
public record Rules(Map<String, List<String>> volatilePaths, Map<String, List<String>> unordered) {

    /** The committed tables. */
    public static final Rules DEFAULT = new Rules(Tables.VOLATILE, Tables.UNORDERED);

    public Rules {
        volatilePaths = Map.copyOf(Objects.requireNonNull(volatilePaths, "volatilePaths"));
        unordered = Map.copyOf(Objects.requireNonNull(unordered, "unordered"));
    }

    /** These rules with {@code area}'s volatile paths replaced by {@code paths}. */
    public Rules withVolatile(String area, String... paths) {
        Map<String, List<String>> table = new LinkedHashMap<>(volatilePaths);
        table.put(area, List.of(paths));
        return new Rules(table, unordered);
    }
}
