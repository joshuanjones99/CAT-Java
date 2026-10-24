package com.verifone.cat.commander.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Outputs recorded from the Python CAT itself (CPython 3.12 {@code difflib.unified_diff(n=0)}, and
 * {@code commander_snapshot.normalise}, {@code differences} and {@code merge_document}, 2026-10-24), compared with the
 * port's. The goldens are resources, so this runs without the Python checkout. Evidence about this repository only.
 */
class PythonParityTest {

    private static JsonNode golden(String name) throws IOException {
        try (InputStream in = PythonParityTest.class.getResourceAsStream("/commander/snapshot/" + name)) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void unifiedDiffMatchesDifflibLineForLine() throws IOException {
        JsonNode cases = golden("difflib-golden.json");
        assertTrue(cases.size() >= 10);
        for (JsonNode c : cases) {
            List<String> a = strings(c.get("a"));
            List<String> b = strings(c.get("b"));
            assertEquals(strings(c.get("full")), Difflib.unifiedDiff(a, b, "snapshot", "now"), c.toString());
            List<String> filtered = new ArrayList<>();
            for (String line : Difflib.unifiedDiff(a, b, "snapshot", "now")) {
                if (!line.startsWith("---") && !line.startsWith("+++") && !line.startsWith("@@")) {
                    filtered.add(line);
                }
            }
            assertEquals(strings(c.get("lines")), filtered);
        }
    }

    @Test
    void normaliseDifferencesAndMergeMatchThePython() throws IOException {
        for (JsonNode c : golden("normalise-golden.json")) {
            String area = c.get("area").asText();
            String a = c.get("a").asText();
            String b = c.get("b").asText();
            assertEquals(c.get("norm_a").asText(), Normalise.normalise(area, a), area);
            assertEquals(c.get("norm_b").asText(), Normalise.normalise(area, b), area);
            assertEquals(strings(c.get("differences")), Normalise.differences(area, a, b, 40), area);
            JsonNode merged = c.get("merged");
            if (merged.has("error")) {
                Merge.NotMergeable e = assertThrows(Merge.NotMergeable.class, () -> Merge.mergeDocument(area, a, b));
                assertTrue(e.getMessage().startsWith(merged.get("error").asText()), e.getMessage());
            } else {
                Merge.Merged m = Merge.mergeDocument(area, a, b);
                assertEquals(merged.get("xml").asText(), m.xml(), area);
                assertEquals(strings(merged.get("notes")), m.notes(), area);
            }
        }
    }

    @Test
    void globsAreFnmatchcase() {
        assertTrue(Normalise.fnmatch("a/b/lastUpdated", "*/lastUpdated"));
        assertTrue(Normalise.fnmatch("panelCfg@creationDate", "panelCfg@creationDate"));
        assertTrue(Normalise.fnmatch("x1", "x[0-9]"));
        assertTrue(Normalise.fnmatch("xa", "x[!0-9]"));
        assertTrue(Normalise.fnmatch("x[", "x["));
        assertTrue(!Normalise.fnmatch("X1", "x?"));
        assertTrue(Normalise.fnmatch("a.b", "a.b") && !Normalise.fnmatch("axb", "a.b"));
    }

    /** The Python's {@code capture} refuses a folder in its repository: views carry site addresses. */
    @Test
    void aSnapshotIsNeverWrittenIntoThisRepository() {
        java.nio.file.Path inside = java.nio.file.Path.of("target", "never-a-snapshot");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Capture.capture(null, List.of("fuelcfg"), inside, new Capture.Options()));
        assertTrue(e.getMessage().contains("inside this repository"), e.getMessage());
        assertTrue(!java.nio.file.Files.exists(inside));
    }

    @Test
    void splitLinesIsPythonsSplitlines() {
        assertEquals(List.of("a", "b", "", "c"), Normalise.splitLines("a\r\nb\n\rc\n"));
        assertEquals(List.of(), Normalise.splitLines(""));
    }
}
