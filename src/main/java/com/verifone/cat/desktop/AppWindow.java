package com.verifone.cat.desktop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.verifone.cat.core.CatHome;

/**
 * CAT in a window of its own: Microsoft Edge (else Google Chrome) in app mode, {@code --app=<url>}, on a profile kept
 * for CAT under {@code ~/.cat/java/window-profile}. App mode has no tabs, address bar or browser menus; the separate
 * profile makes it a browser instance of its own, so CAT gets its own window and taskbar entry (with CAT's favicon)
 * and closing it leaves the operator's browser alone. The pages are the same ones the browser shows.
 *
 * <p>Pure: which browser, and the command line. {@link DesktopHost} spawns it and never waits on or stops it; closing
 * the window ends its heartbeat, and the auto-close does the rest, exactly as closing a browser tab does.
 *
 * <p>Why a browser's app mode and not an embedded view (HANDOFF §2): Edge ships with Windows 11 and every controller,
 * so there is no new dependency, where JavaFX's WebView is an old WebKit, JCEF is 100 MB+ of native libraries, and
 * WebView2 through JNA is a new dependency for the same Edge engine.
 *
 * <p>UNVERIFIED ON A CONTROLLER (Java port): the window was checked only on a development PC (Windows 11, Edge).
 */
public final class AppWindow {

    /** The profile folder's name under {@code ~/.cat/java}. */
    public static final String PROFILE_DIR = "window-profile";

    /** The window's first size; Edge remembers the operator's size after that, per profile. */
    static final String WINDOW_SIZE = "1440,900";

    private AppWindow() {}

    /**
     * Where Edge and then Chrome install, in the order they are tried: Edge under both Program Files, then Chrome
     * under both Program Files and the per-user {@code %LOCALAPPDATA%}. A variable that is not set adds nothing.
     */
    public static List<Path> candidates(Map<String, String> env) {
        List<Path> out = new ArrayList<>();
        for (String root : List.of("ProgramFiles(x86)", "ProgramFiles")) {
            add(out, env.get(root), "Microsoft", "Edge", "Application", "msedge.exe");
        }
        for (String root : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            add(out, env.get(root), "Google", "Chrome", "Application", "chrome.exe");
        }
        return out;
    }

    /** The first candidate that exists, or empty when neither browser is installed. */
    public static Optional<Path> find(Map<String, String> env, Predicate<Path> exists) {
        return candidates(env).stream().filter(exists).findFirst();
    }

    /** {@code ~/.cat/java/window-profile}. */
    public static Path profileDir(Path home) {
        return CatHome.java(home).resolve(PROFILE_DIR);
    }

    /** The command that opens {@code url} as CAT's window. */
    public static List<String> command(Path browser, String url, Path profile) {
        return List.of(browser.toString(),
                "--app=" + url,
                "--user-data-dir=" + profile,
                "--no-first-run",
                "--no-default-browser-check",
                "--window-size=" + WINDOW_SIZE);
    }

    private static void add(List<Path> out, String root, String... parts) {
        if (root == null || root.isBlank()) {
            return;
        }
        Path path = Path.of(root.strip());
        for (String part : parts) {
            path = path.resolve(part);
        }
        if (!out.contains(path)) {
            out.add(path);
        }
    }
}
