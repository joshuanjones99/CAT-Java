package com.verifone.cat.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.verifone.cat.core.CatHome;
import com.verifone.cat.prefs.WebSettings.OpenIn;

/**
 * What CAT does on the operator's desktop: open itself (its own window, or the default browser) and add its
 * shortcut. The web UI and {@code WebServe} reach the desktop only through this, so tests pass a fake and nothing
 * opens or is written.
 *
 * <p>UNVERIFIED ON A CONTROLLER (Java port): the window was checked only on a development PC.
 */
public interface DesktopHost {

    /**
     * Opens CAT on {@code url} as the operator chose. {@link OpenIn#WINDOW} is {@link AppWindow}'s app window; when
     * neither Edge nor Chrome is installed or the spawn fails, it falls back to the default browser. Never throws.
     *
     * @return what was done, one line, for the log
     */
    String open(String url, OpenIn how);

    /** Opens {@code url} in the default browser. Never throws. @return what was done, one line */
    String openInBrowser(String url);

    /** Adds the CAT-Java shortcut to the Desktop and the Start menu ({@link Shortcuts}). Never throws. */
    Shortcuts.Outcome addShortcuts();

    /** Starts a program and leaves it running: no wait, no stop. */
    @FunctionalInterface
    interface Spawner {
        void spawn(List<String> argv) throws IOException;
    }

    /** Keeps CAT's window icon sharp ({@link WindowIcon#start}); {@code catJavaDir} holds the icon file. */
    @FunctionalInterface
    interface IconKeeper {
        void start(Path catJavaDir) throws IOException;
    }

    /** The real desktop: this process's environment and home, real processes. */
    static DesktopHost system() {
        return new Real(System.getenv(), CatHome.user(), Files::isRegularFile, DesktopHost::spawnDetached,
                DesktopHost::runToEnd, Shortcuts::repoRoot, WindowIcon::start);
    }

    /** {@link #system()}'s implementation, with every reach into the machine injected. */
    final class Real implements DesktopHost {

        private final Map<String, String> env;
        private final Path home;
        private final Predicate<Path> exists;
        private final Spawner spawner;
        private final Shortcuts.Runner runner;
        private final java.util.function.Supplier<Path> repoRoot;
        private final IconKeeper iconKeeper;

        public Real(Map<String, String> env, Path home, Predicate<Path> exists, Spawner spawner,
                Shortcuts.Runner runner, java.util.function.Supplier<Path> repoRoot, IconKeeper iconKeeper) {
            this.env = Objects.requireNonNull(env, "env");
            this.home = Objects.requireNonNull(home, "home");
            this.exists = Objects.requireNonNull(exists, "exists");
            this.spawner = Objects.requireNonNull(spawner, "spawner");
            this.runner = Objects.requireNonNull(runner, "runner");
            this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
            this.iconKeeper = Objects.requireNonNull(iconKeeper, "iconKeeper");
        }

        @Override
        public String open(String url, OpenIn how) {
            if (how != OpenIn.WINDOW) {
                return openInBrowser(url);
            }
            Optional<Path> browser = AppWindow.find(env, exists);
            if (browser.isEmpty()) {
                return "no Edge or Chrome for CAT's window; " + openInBrowser(url);
            }
            List<String> argv = AppWindow.command(browser.get(), url, AppWindow.profileDir(home));
            try {
                spawner.spawn(argv);
            } catch (IOException | RuntimeException e) {
                return "could not open CAT's window with " + browser.get() + " (" + e.getMessage() + "); "
                        + openInBrowser(url);
            }
            return "opened CAT's window on " + url + " with " + browser.get() + keepIcon();
        }

        /** Starts {@link WindowIcon} for the window just opened; a failure only leaves the browser's icon. */
        private String keepIcon() {
            try {
                iconKeeper.start(CatHome.java(home));
                return "";
            } catch (IOException | RuntimeException e) {
                return " (its taskbar icon stays the browser's: " + e.getMessage() + ")";
            }
        }

        @Override
        public String openInBrowser(String url) {
            try {
                spawner.spawn(List.of("rundll32", "url.dll,FileProtocolHandler", url));
                return "opened the browser on " + url;
            } catch (IOException | RuntimeException e) {
                return "could not open the browser on " + url + ": " + e.getMessage();
            }
        }

        @Override
        public Shortcuts.Outcome addShortcuts() {
            try {
                return Shortcuts.add(repoRoot.get(), CatHome.java(home), runner);
            } catch (RuntimeException e) {
                return new Shortcuts.Outcome(false, List.of("Adding the CAT-Java shortcut failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
    }

    /** Starts {@code argv} with its output discarded, so it never blocks on a full pipe, and returns at once. */
    static void spawnDetached(List<String> argv) throws IOException {
        new ProcessBuilder(argv).redirectInput(Redirect.PIPE).redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD).start().getOutputStream().close();
    }

    /** Runs {@code argv} to its end, or stops it at {@code timeout}; exit 1 on a timeout or a launch failure. */
    static Shortcuts.Ran runToEnd(List<String> argv, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(argv).start();
        } catch (IOException e) {
            return new Shortcuts.Ran(1, "", e.getMessage());
        }
        try {
            process.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Shortcuts.Ran(1, "", "timed out after " + timeout.toSeconds() + "s");
            }
            return new Shortcuts.Ran(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Shortcuts.Ran(1, "", "interrupted");
        } catch (Exception e) {
            process.destroyForcibly();
            return new Shortcuts.Ran(1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), Charset.defaultCharset());
        } catch (IOException e) {
            return "";
        }
    }
}
