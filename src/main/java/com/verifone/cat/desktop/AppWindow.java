package com.verifone.cat.desktop;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.verifone.cat.core.CatHome;

/**
 * CAT in a window of its own: Microsoft Edge (else Google Chrome) in app mode, {@code --app=<url>}, on a profile kept
 * for CAT under {@code ~/.cat/java/window-profile}. App mode has no tabs, address bar or browser menus; the separate
 * profile makes it a browser instance of its own, so CAT gets its own window and taskbar entry, and closing it leaves
 * the operator's browser alone. The pages are the same ones the browser shows. The browser draws that entry's icon
 * from the 16 px favicon, stretched and blurred; {@link WindowIcon} replaces it with {@code cat.ico}'s own sizes.
 *
 * <p>No title bar, once CAT is installed (2026-11-10, at the operator's request, HANDOFF §2). CAT's pages link a web
 * app manifest ({@code /static/manifest.json}) that asks for the browser's Window Controls Overlay: the page fills
 * the whole window, the browser draws only its window buttons over the top right corner, and the page's top strip
 * drags the window ({@code cat.css}, {@code .window-drag}). Edge gives that only to an installed web app, so
 * Settings → Opening CAT has a one-time Hide the title bar button, which shows Edge's own Install dialog; Edge then
 * reopens the window as the app. From then on {@link #appCommand} opens CAT by its app id ({@link #appId}); an {@code --app}
 * window of an installed app still has its title bar. The app is installed only in CAT's profile, never in the
 * operator's own browser profile. Edge offers no unattended install here: its DevTools {@code PWA.install} is not
 * served (Edge 157, 2026-11-10).
 *
 * <p>Both commands pass {@code --disable-sync}: Edge signs the profile in with the Windows work account by itself and
 * syncs everything, apps included, which would put a {@code 127.0.0.1} CAT app on the operator's other devices.
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

    /** The browser profile inside CAT's user-data folder: the only one, the browser's default. */
    static final String PROFILE = "Default";

    /** The manifest's {@code id} ({@code public/manifest.json}), resolved against CAT's origin. */
    static final String MANIFEST_ID = "/";

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

    /** The command that opens {@code url} as CAT's window, before CAT is installed: an app window with a title bar. */
    public static List<String> command(Path browser, String url, Path profile) {
        return List.of(browser.toString(),
                "--app=" + url,
                "--user-data-dir=" + profile,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-sync",
                "--window-size=" + WINDOW_SIZE);
    }

    /**
     * The command that opens the installed CAT app {@code appId} in CAT's profile: no title bar. It opens the
     * manifest's start URL ({@code /}); Edge keeps the window's size and place per app.
     */
    public static List<String> appCommand(Path browser, String appId, Path profile) {
        return List.of(browser.toString(),
                "--app-id=" + appId,
                "--profile-directory=" + PROFILE,
                "--user-data-dir=" + profile,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-sync");
    }

    /**
     * The browser's id for the web app CAT serves at {@code url}: Chromium's {@code GenerateAppIdFromManifestId},
     * the extension-id form (the first 16 bytes of a SHA-256, each hex digit written as {@code a}..{@code p}) of the
     * SHA-256 of the manifest id. The manifest's {@code id} is {@code /}, so the manifest id is the URL's origin with
     * a {@code /} path. Checked against Edge 157 (2026-11-10): {@code http://127.0.0.1:18765/} installed as
     * {@code mdjbodlgealdbiggeehcemhakapikekc}.
     */
    public static String appId(String url) {
        URI uri = URI.create(url);
        String manifestId = uri.getScheme() + "://" + uri.getRawAuthority() + MANIFEST_ID;
        byte[] once = sha256(manifestId.getBytes(StandardCharsets.UTF_8));
        byte[] twice = sha256(once);
        StringBuilder id = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            id.append((char) ('a' + ((twice[i] >> 4) & 0xF))).append((char) ('a' + (twice[i] & 0xF)));
        }
        return id.toString();
    }

    /**
     * The folder the browser keeps an installed app's icons in, in CAT's profile: there exactly while the app is
     * installed (an uninstall removes it; seen on Edge 157), so its presence is how CAT tells it is installed.
     */
    public static Path installedMarker(Path profile, String appId) {
        return profile.resolve(PROFILE).resolve("Web Applications").resolve("Manifest Resources").resolve(appId);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
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
