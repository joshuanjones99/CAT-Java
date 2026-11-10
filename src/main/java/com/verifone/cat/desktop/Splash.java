package com.verifone.cat.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import com.verifone.cat.core.CatHome;

/**
 * The splash the CAT-Java shortcut shows while CAT starts: the expanded sidebar's Commander-dots CAT logo, alone.
 *
 * <p>{@link Shortcuts} puts {@link #FLAG} on {@code CAT-Java.lnk}; {@code run.cmd}, seeing it, starts the repository's
 * {@code splash.ps1} before it checks the build, so the logo shows within a second of the click whatever comes next
 * (a build, the Java 17 download, the JVM). The window is the script's, not Java's: Java's part is one file,
 * {@code ~/.cat/java/}{@value #READY_NAME}, written when CAT's page has opened ({@code WebServe}: the first page's
 * poll, or {@link #GIVE_UP} after opening it). The splash closes once that file is newer than itself, when
 * {@code bootstrap.ps1} is about to show a failure (it writes the same file), or when {@code run.cmd} has ended.
 *
 * <p>UNVERIFIED ON A CONTROLLER (Java port): the splash was seen only on a development PC.
 */
public final class Splash {

    /** The {@code web} option, and {@code run.cmd}'s cue, for "the shortcut's splash is showing". */
    public static final String FLAG = "--splash";

    /** The file under {@code ~/.cat/java} that tells the splash to close; {@code splash.ps1} reads the same name. */
    public static final String READY_NAME = "splash.ready";

    /** With no page polling this long after CAT opened it, the splash closes anyway. */
    public static final Duration GIVE_UP = Duration.ofSeconds(60);

    private Splash() {}

    /** {@code ~/.cat/java/}{@value #READY_NAME}. */
    public static Path readyFile() {
        return CatHome.java().resolve(READY_NAME);
    }

    /** Tells the splash to close by writing {@code file} now. Never throws. @return what was done, for the log */
    public static String close(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now().toString() + "\n", StandardCharsets.UTF_8);
            return "Closed the splash (" + file + ")";
        } catch (IOException | RuntimeException e) {
            return "Could not close the splash through " + file + ": " + e.getMessage();
        }
    }
}
