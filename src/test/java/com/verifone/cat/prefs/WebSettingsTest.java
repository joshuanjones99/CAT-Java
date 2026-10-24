package com.verifone.cat.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.prefs.WebSettings.Appearance;

class WebSettingsTest {

    @TempDir
    Path tmp;

    @Test
    void theDefaultIsUnderTheJavaFolderNotThePythonCatsFile() {
        Path path = WebSettings.defaultPath(Map.of(), "C:\\Users\\op");

        assertEquals(Path.of("C:\\Users\\op", ".cat", "java", "web-settings.json"), path);
    }

    @Test
    void theEnvironmentOverrideMovesTheFileAndABlankOneCountsAsUnset() {
        assertEquals(Path.of("X", "prefs.json"),
                WebSettings.defaultPath(Map.of("CAT_WEB_SETTINGS", "X\\prefs.json"), "C:\\u"));
        assertEquals(Path.of("C:\\u", ".cat", "java", "web-settings.json"),
                WebSettings.defaultPath(Map.of("CAT_WEB_SETTINGS", "  "), "C:\\u"));
    }

    @Test
    void aMissingFileMeansMatchWindows() {
        assertEquals(Appearance.AUTO, WebSettings.appearance(tmp.resolve("none.json")));
        assertTrue(WebSettings.read(tmp.resolve("none.json")).isEmpty());
    }

    @Test
    void aFileThatIsNotAnObjectOrNotReadableMeansTheDefaults() throws Exception {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "[1, 2", StandardCharsets.UTF_8);
        assertEquals(Appearance.AUTO, WebSettings.appearance(file));

        Files.writeString(file, "{\"appearance\": 7}", StandardCharsets.UTF_8);
        assertEquals(Appearance.AUTO, WebSettings.appearance(file));

        Files.writeString(file, "{\"appearance\": \"neon\"}", StandardCharsets.UTF_8);
        assertEquals(Appearance.AUTO, WebSettings.appearance(file));
    }

    @Test
    void updateWritesTheChoiceAndKeepsOtherKeys() throws Exception {
        Path file = tmp.resolve("java").resolve("web-settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"sidebar_collapsed\": true}", StandardCharsets.UTF_8);

        assertTrue(WebSettings.update(file, WebSettings.APPEARANCE_KEY, Appearance.DARK.value()));

        assertEquals(Appearance.DARK, WebSettings.appearance(file));
        assertEquals(Boolean.TRUE, WebSettings.read(file).get("sidebar_collapsed"));
    }

    @Test
    void updateCreatesTheFolderWhenItIsMissing() {
        Path file = tmp.resolve("made").resolve("deeper").resolve("web-settings.json");

        assertTrue(WebSettings.update(file, WebSettings.APPEARANCE_KEY, Appearance.LIGHT.value()));

        assertEquals(Appearance.LIGHT, WebSettings.appearance(file));
    }

    @Test
    void updateReportsFailureInsteadOfThrowing() throws Exception {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);

        assertFalse(WebSettings.update(blocker.resolve("web-settings.json"), WebSettings.APPEARANCE_KEY, "dark"));
    }

    @Test
    void appearanceParsesOnlyTheThreeStoredNames() {
        assertEquals(Appearance.AUTO, Appearance.parse("auto"));
        assertEquals(Appearance.LIGHT, Appearance.parse(" light "));
        assertEquals(Appearance.DARK, Appearance.parse("dark"));
        assertNull(Appearance.parse("Dark"));
        assertNull(Appearance.parse(null));
        assertEquals("Match Windows", Appearance.AUTO.label());
    }
}
