package com.verifone.cat.pos.driver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.microsoft.playwright.Playwright;
import com.verifone.cat.core.CatHome;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Where Playwright's browsers live, and the one way CAT-Java starts Playwright.
 *
 * <p>CAT keeps the Chromium Playwright launches (the fake POS needs it; a CDP attach to a register does not) in the
 * tools root, beside the JDK and the simulators: {@code <tools root>\ms-playwright}, {@code C:\CICD\Tools\ms-playwright}
 * by default. {@code PLAYWRIGHT_BROWSERS_PATH}, when set, wins. A Chromium installed before this, in Playwright's own
 * per-user folder ({@code %LOCALAPPDATA%\ms-playwright}), is still used while the tools root has none.
 *
 * <p>{@link #create()} starts Playwright with that folder and with the browser download switched off, so starting
 * Playwright never downloads anything; the Setup page's Install button is the one download.
 */
public final class PlaywrightHome {

    /** The environment variable that moves Playwright's browsers folder. */
    public static final String BROWSERS_ENV = "PLAYWRIGHT_BROWSERS_PATH";

    /** Set, Playwright for Java does not download browsers when it starts. */
    public static final String SKIP_DOWNLOAD_ENV = "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD";

    /** The browsers folder's name, under the tools root as under {@code %LOCALAPPDATA%}. */
    public static final String FOLDER = "ms-playwright";

    /** The Chromium build Playwright for Java 1.49.0 installs (HANDOFF.md §6, item 12). */
    public static final String CHROMIUM_DIR = "chromium-1148";

    private PlaywrightHome() {}

    /** Where CAT installs the browsers: {@code PLAYWRIGHT_BROWSERS_PATH}, else {@code <tools root>\ms-playwright}. */
    public static Path installDir(Map<String, String> vars) {
        String configured = blankToNull(vars.get(BROWSERS_ENV));
        if (configured != null) {
            return Path.of(configured);
        }
        return ToolsRoot.resolve(vars.get(ToolsRoot.ENV), null).resolve(FOLDER);
    }

    /**
     * Where Playwright looks for the browsers: {@link #installDir}, unless only the per-user folder holds the Chromium
     * (installed there before CAT kept it in the tools root).
     */
    public static Path browsersDir(Map<String, String> vars, Path home) {
        Path install = installDir(vars);
        if (blankToNull(vars.get(BROWSERS_ENV)) != null || Files.isDirectory(install.resolve(CHROMIUM_DIR))) {
            return install;
        }
        Path legacy = perUser(vars, home);
        return Files.isDirectory(legacy.resolve(CHROMIUM_DIR)) ? legacy : install;
    }

    /** Playwright's own default: {@code %LOCALAPPDATA%\ms-playwright}. */
    static Path perUser(Map<String, String> vars, Path home) {
        String localAppData = blankToNull(vars.get("LOCALAPPDATA"));
        Path local = localAppData == null ? home.resolve("AppData").resolve("Local") : Path.of(localAppData);
        return local.resolve(FOLDER);
    }

    /** What the Playwright driver is started with: the browsers folder, and no download. */
    public static Map<String, String> driverEnv(Map<String, String> vars, Path home) {
        return Map.of(BROWSERS_ENV, browsersDir(vars, home).toString(), SKIP_DOWNLOAD_ENV, "1");
    }

    /** {@code Playwright.create()} over this process's environment, with the browsers folder above. */
    public static Playwright create() {
        return Playwright.create(new Playwright.CreateOptions().setEnv(driverEnv(System.getenv(), CatHome.user())));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
