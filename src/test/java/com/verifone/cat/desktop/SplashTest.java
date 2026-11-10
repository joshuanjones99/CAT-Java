package com.verifone.cat.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shortcut's splash: Java's ready file, and the scripts that show and close the window, as text. Nothing here
 * runs PowerShell or opens a window; the ready file is a temp file.
 */
class SplashTest {

    @TempDir
    Path tmp;

    private static String read(String name) throws Exception {
        return Files.readString(Path.of(name), StandardCharsets.UTF_8);
    }

    @Test
    void closingWritesTheReadyFileNowMakingItsFolder() throws Exception {
        Path file = tmp.resolve("home").resolve(".cat").resolve("java").resolve(Splash.READY_NAME);
        Instant before = Instant.now().minusSeconds(1);

        String said = Splash.close(file);

        assertTrue(said.startsWith("Closed the splash"), said);
        FileTime written = Files.getLastModifiedTime(file);
        assertFalse(written.toInstant().isBefore(before), "newer than a splash that started before it");

        Files.setLastModifiedTime(file, FileTime.from(before.minusSeconds(3600)));
        Splash.close(file);
        assertTrue(Files.getLastModifiedTime(file).toInstant().isAfter(before), "a second close writes it again");
    }

    @Test
    void closingNeverThrows() throws Exception {
        Path blocked = Files.writeString(tmp.resolve("a-file"), "x");

        String said = Splash.close(blocked.resolve(Splash.READY_NAME));

        assertTrue(said.startsWith("Could not close the splash"), said);
    }

    @Test
    void runCmdStartsTheSplashOnItsConsoleBeforeTheBuildCheck() throws Exception {
        String run = read("run.cmd");
        int splash = run.indexOf("start \"\" /b powershell -NoProfile -ExecutionPolicy Bypass -File \"%~dp0splash.ps1\"");
        int buildCheck = run.indexOf("bootstrap.ps1\" -NeedsBuild");

        assertTrue(splash > 0, run);
        assertTrue(splash < buildCheck, "the logo shows before anything slow");
        assertTrue(run.contains("if /i \"%%~a\"==\"" + Splash.FLAG + "\" set \"SPLASH=1\""), run);
    }

    @Test
    void theSplashShowsTheExpandedSidebarsLogoAndWatchesJavasFile() throws Exception {
        String splash = read("splash.ps1");

        assertTrue(splash.contains("'.cat\\java\\" + Splash.READY_NAME + "'"), "the file Splash.close writes");
        assertTrue(splash.contains("'src\\main\\resources\\public\\icons\\cat-commander-logo.png'"), splash);
        assertTrue(splash.contains("$Background = '#101E26'"), "cat.css --cat-side");
        assertTrue(splash.contains("LastWriteTimeUtc -ge $script:started"), "a ready file left from before is not one");
        assertTrue(splash.contains("$script:parent.HasExited"), "closes when run.cmd ends");
        assertTrue(splash.contains("$window.ShowActivated = $false"), "takes no focus");
        try (InputStream logo = Splash.class.getResourceAsStream("/public/icons/cat-commander-logo.png")) {
            assertNotNull(logo, "the logo the sidebar shows expanded (Layout)");
        }
        assertTrue(read("src/main/resources/public/cat.css").contains("--cat-side: #101e26;"), "the sidebar's colour");
    }

    @Test
    void bootstrapClosesTheSplashBeforeShowingAFailure() throws Exception {
        String bootstrap = read("bootstrap.ps1");
        int notice = bootstrap.indexOf("function Show-Notice([string]$text) {");

        assertTrue(bootstrap.contains("'.cat\\java\\" + Splash.READY_NAME + "'"), bootstrap);
        int close = bootstrap.indexOf("    Close-Splash", notice);
        assertTrue(notice > 0 && close > notice && close < bootstrap.indexOf("MessageBox", notice),
                "Show-Notice closes the splash before its message box");
    }
}
