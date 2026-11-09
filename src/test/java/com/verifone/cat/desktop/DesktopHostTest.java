package com.verifone.cat.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.verifone.cat.prefs.WebSettings.OpenIn;

/** Opening CAT, with a fake spawner: nothing is started on this PC. */
class DesktopHostTest {

    private static final Path HOME = Path.of("C:\\Users\\op");
    private static final Path EDGE = Path.of("C:\\PF", "Microsoft", "Edge", "Application", "msedge.exe");
    private static final String URL = "http://127.0.0.1:8090/";

    private final List<List<String>> spawned = new ArrayList<>();

    private DesktopHost host(Set<Path> installed, boolean spawnFails) {
        return new DesktopHost.Real(Map.of("ProgramFiles", "C:\\PF"), HOME, installed::contains, argv -> {
            spawned.add(argv);
            if (spawnFails && argv.get(0).endsWith("msedge.exe")) {
                throw new IOException("access denied");
            }
        }, (argv, timeout) -> {
            throw new AssertionError("no command runs here");
        }, () -> HOME);
    }

    @Test
    void theWindowChoiceOpensAnEdgeAppWindowOnCatsProfile() {
        String what = host(Set.of(EDGE), false).open(URL, OpenIn.WINDOW);

        assertEquals(List.of(AppWindow.command(EDGE, URL, AppWindow.profileDir(HOME))), spawned);
        assertTrue(what.startsWith("opened CAT's window on " + URL), what);
    }

    @Test
    void theBrowserChoiceOpensTheDefaultBrowser() {
        String what = host(Set.of(EDGE), false).open(URL, OpenIn.BROWSER);

        assertEquals(List.of(List.of("rundll32", "url.dll,FileProtocolHandler", URL)), spawned);
        assertEquals("opened the browser on " + URL, what);
    }

    @Test
    void withNoEdgeOrChromeTheWindowFallsBackToTheBrowserAndSaysWhy() {
        String what = host(Set.of(), false).open(URL, OpenIn.WINDOW);

        assertEquals(List.of(List.of("rundll32", "url.dll,FileProtocolHandler", URL)), spawned);
        assertTrue(what.startsWith("no Edge or Chrome for CAT's window; opened the browser"), what);
    }

    @Test
    void aWindowThatWillNotStartFallsBackToTheBrowser() {
        String what = host(Set.of(EDGE), true).open(URL, OpenIn.WINDOW);

        assertEquals(2, spawned.size());
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", URL), spawned.get(1));
        assertTrue(what.contains("access denied") && what.endsWith("opened the browser on " + URL), what);
    }
}
