package com.verifone.cat.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.verifone.cat.core.Waits;

/**
 * The sharp taskbar icon for CAT's window ({@link AppWindow}). Edge and Chrome make an {@code --app} window's icon
 * from the page's favicon at one times scale (16 px at 100%) stretched to the size Windows asks for, so CAT's taskbar
 * button was blurred whatever the page links. This sets each CAT window's big and small icons from {@code cat.ico} at
 * the PC's icon sizes, and sets them again whenever the browser puts its own back, which it does on every page and
 * title change: a thread in CAT's process looks every {@link #BUSY} while a CAT window is open, and every
 * {@link #IDLE} while none is.
 *
 * <p>A CAT window is a visible top-level browser window whose title ends with {@link #TITLE_SUFFIX}, which every page
 * of the web UI has. A CAT page in a normal browser window is not one: the browser adds its own name to that title.
 * The installed CAT app's window (no title bar, {@link AppWindow#appCommand}) is not one either, and needs no keeper:
 * its title is the app's name, {@code CAT-Java}, and Edge draws its icon from the manifest's 192 px icon, which was
 * already sharp (Edge 157, 2026-11-10), so {@link DesktopHost} does not start the keeper for it. The keeper only
 * sets icons; it never closes or stops anything. The native calls are {@link JnaWindowIcons} (JNA is already a
 * dependency, HANDOFF §2; the NoUiImports gate keeps AWT out).
 *
 * <p>UNVERIFIED ON A CONTROLLER (Java port): checked only on a development PC (Windows 11, Edge, 100% scale), where
 * the taskbar button became sharp and stayed so across navigations.
 */
public final class WindowIcon {

    /** The end of every CAT page's title ({@code web.Layout}): how a CAT window is told from the others. */
    public static final String TITLE_SUFFIX = " · CAT-Java";

    /** How often the keeper looks while a CAT window is open: the browser's own icon shows for at most this long. */
    static final Duration BUSY = Duration.ofMillis(100);

    /** How often it looks while none is. */
    static final Duration IDLE = Duration.ofSeconds(1);

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private WindowIcon() {}

    /** A visible top-level browser window: its handle and title. */
    record Window(long handle, String title) {}

    /** The window's two icons, as handles. */
    record Icons(long big, long small) {}

    /** The window calls the keeper makes; {@link JnaWindowIcons} is the real one. */
    interface Windows {

        /** The visible, unowned top-level windows of the Chromium browsers (Edge, Chrome). */
        List<Window> browserWindows();

        /** The window's icon now: {@code WM_GETICON}, 0 for none. */
        long icon(long handle, boolean big);

        /** {@code WM_SETICON}. */
        void setIcon(long handle, boolean big, long icon);
    }

    /** Whether a browser window with this title is CAT's own. */
    static boolean isCatWindow(String title) {
        return title.endsWith(TITLE_SUFFIX);
    }

    /** One look: each CAT window whose icons are not {@code icons} gets them. @return how many CAT windows */
    static int keep(Windows windows, Icons icons) {
        int cat = 0;
        for (Window window : windows.browserWindows()) {
            if (!isCatWindow(window.title())) {
                continue;
            }
            cat++;
            if (windows.icon(window.handle(), true) != icons.big()) {
                windows.setIcon(window.handle(), true, icons.big());
            }
            if (windows.icon(window.handle(), false) != icons.small()) {
                windows.setIcon(window.handle(), false, icons.small());
            }
        }
        return cat;
    }

    /**
     * Starts the keeper, once per process: writes {@code cat.ico} into {@code catJavaDir}, loads it at the PC's icon
     * sizes and starts the daemon thread {@code cat-window-icon}. A later call does nothing.
     *
     * @throws IOException when the icon cannot be written or loaded; a later call tries again
     */
    public static void start(Path catJavaDir) throws IOException {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            Path ico = catJavaDir.resolve(Shortcuts.ICON_NAME);
            Files.createDirectories(catJavaDir);
            copy(Shortcuts.ICON_RESOURCE, ico);
            JnaWindowIcons windows = new JnaWindowIcons();
            Icons icons = windows.load(ico);
            Thread thread = new Thread(() -> run(windows, icons), "cat-window-icon");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException | RuntimeException | LinkageError e) {
            STARTED.set(false);
            throw e instanceof IOException io ? io : new IOException(e.toString(), e);
        }
    }

    /** The keeper's loop, until the thread is interrupted; a failed look only waits for the next. */
    static void run(Windows windows, Icons icons) {
        Duration wait = IDLE;
        do {
            try {
                wait = keep(windows, icons) > 0 ? BUSY : IDLE;
            } catch (RuntimeException e) {
                wait = IDLE;
            }
        } while (Waits.pause(wait));
    }

    /** Copies a resource of this jar to {@code target}, replacing it. */
    static void copy(String resource, Path target) throws IOException {
        try (InputStream in = WindowIcon.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no " + resource + " in the jar");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
