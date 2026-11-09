package com.verifone.cat.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Which browser CAT's window uses, and its command line. Pure: nothing is looked up on this PC or started. */
class AppWindowTest {

    private static final Map<String, String> ENV = Map.of(
            "ProgramFiles(x86)", "C:\\PF86",
            "ProgramFiles", "C:\\PF",
            "LOCALAPPDATA", "C:\\Users\\op\\AppData\\Local");

    @Test
    void edgeIsTriedFirstThenChromeInEveryPlaceItInstalls() {
        assertEquals(List.of(
                Path.of("C:\\PF86", "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.of("C:\\PF", "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.of("C:\\PF", "Google", "Chrome", "Application", "chrome.exe"),
                Path.of("C:\\PF86", "Google", "Chrome", "Application", "chrome.exe"),
                Path.of("C:\\Users\\op\\AppData\\Local", "Google", "Chrome", "Application", "chrome.exe")),
                AppWindow.candidates(ENV));
    }

    @Test
    void aVariableThatIsNotSetAddsNoCandidate() {
        assertEquals(List.of(Path.of("C:\\PF", "Microsoft", "Edge", "Application", "msedge.exe"),
                Path.of("C:\\PF", "Google", "Chrome", "Application", "chrome.exe")),
                AppWindow.candidates(Map.of("ProgramFiles", "C:\\PF", "LOCALAPPDATA", " ")));
    }

    @Test
    void theFirstInstalledBrowserIsChosen() {
        Path chrome = Path.of("C:\\Users\\op\\AppData\\Local", "Google", "Chrome", "Application", "chrome.exe");
        Path edge = Path.of("C:\\PF", "Microsoft", "Edge", "Application", "msedge.exe");

        assertEquals(Optional.of(chrome), AppWindow.find(ENV, Set.of(chrome)::contains));
        assertEquals(Optional.of(edge), AppWindow.find(ENV, Set.of(chrome, edge)::contains));
        assertTrue(AppWindow.find(ENV, path -> false).isEmpty());
    }

    @Test
    void theCommandIsAnAppWindowOnCatsOwnProfile() {
        Path home = Path.of("C:\\Users\\op");
        Path profile = AppWindow.profileDir(home);

        assertEquals(Path.of("C:\\Users\\op", ".cat", "java", "window-profile"), profile);
        assertEquals(List.of("C:\\PF\\msedge.exe", "--app=http://127.0.0.1:8090/", "--user-data-dir=" + profile,
                "--no-first-run", "--no-default-browser-check", "--window-size=1440,900"),
                AppWindow.command(Path.of("C:\\PF\\msedge.exe"), "http://127.0.0.1:8090/", profile));
    }
}
