package com.verifone.cat.coverage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Where each component's class files landed, and what could not be found: the Python's
 * {@code petrotest.coverage.classes.ClassFiles}.
 *
 * <p>{@link #toJson} writes each found path relative to {@link #root} (the platform's separator, as the Python's
 * {@code str(Path)}), so the cache's {@code stamp.json} reads the same in either CAT.
 *
 * @param root    the cache folder the files were extracted under
 * @param found   component name to the extracted jars and wars, in its pattern order
 * @param missing component name to the patterns no package matched
 */
public record ClassFiles(Path root, Map<String, List<Path>> found, Map<String, List<String>> missing) {

    public ClassFiles {
        found = copy(found);
        missing = copy(missing);
    }

    /** No class files at all. */
    public static ClassFiles none() {
        return new ClassFiles(Path.of(""), Map.of(), Map.of());
    }

    /** {@code {"found": {component: [relative path]}, "missing": {component: [pattern]}}}. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<String>> relative = new LinkedHashMap<>();
        found.forEach((name, paths) -> relative.put(name, paths.stream()
                .map(path -> root.relativize(path).toString()).toList()));
        out.put("found", relative);
        out.put("missing", missing);
        return out;
    }

    /** The class files a {@code stamp.json} names, under {@code root}. */
    public static ClassFiles fromJson(Path root, JsonNode data) {
        Map<String, List<Path>> found = new LinkedHashMap<>();
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = data.path("found").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            List<Path> paths = new ArrayList<>();
            entry.getValue().forEach(node -> paths.add(root.resolve(node.asText())));
            found.put(entry.getKey(), paths);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = data.path("missing").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            List<String> patterns = new ArrayList<>();
            entry.getValue().forEach(node -> patterns.add(node.asText()));
            missing.put(entry.getKey(), patterns);
        }
        return new ClassFiles(root, found, missing);
    }

    private static <T> Map<String, List<T>> copy(Map<String, List<T>> map) {
        Map<String, List<T>> out = new LinkedHashMap<>();
        if (map != null) {
            map.forEach((key, value) -> out.put(key, List.copyOf(value)));
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
