package com.verifone.cat.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The CAT-Java shortcut on this user's Desktop and in their Start menu: the Python CAT's {@code setup.ps1} step 7b.
 *
 * <p>The target is {@code conhost.exe --headless "cmd.exe" /c "<repo>\run.cmd" web}: conhost's headless mode gives
 * CAT, and every console program it starts (git, ssh, PowerShell, the staged Python), a console nobody sees, where
 * {@code javaw} would leave each child to open a window of its own. The window style is minimised, the working folder
 * the repository and the icon CAT's own {@code cat.ico}, copied to {@code ~/.cat/java/cat.ico} so the shortcut does not
 * depend on the jar. A second click while CAT runs only opens it again ({@code WebServe}).
 *
 * <p>The file is {@code CAT-Java.lnk}: {@code CAT.lnk} is the Python CAT's, and the two run side by side on a
 * controller. The shortcuts are written by Windows PowerShell through {@code WScript.Shell}, as the Python's are; the
 * script is passed as {@code -EncodedCommand}, so its quotes survive (see {@code PowerShellProcessOps.encode}).
 *
 * <p>UNVERIFIED ON A CONTROLLER (Java port): the script is checked by tests as text; it has not been run on a
 * controller.
 */
public final class Shortcuts {

    /** The shortcut's file name, in each place. */
    public static final String LINK_NAME = "CAT-Java.lnk";

    /** The icon's name under {@code ~/.cat/java}, and its resource in the jar. */
    public static final String ICON_NAME = "cat.ico";
    static final String ICON_RESOURCE = "/public/icons/cat.ico";

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Runs one command to completion. {@link DesktopHost} and {@code cat-java setup} each pass their own. */
    @FunctionalInterface
    public interface Runner {
        Ran run(List<String> argv, Duration timeout);
    }

    /** A finished command: its exit code and what it printed. */
    public record Ran(int exitCode, String stdout, String stderr) {}

    /** What adding the shortcuts did: whether every place got one, and the lines to show. */
    public record Outcome(boolean ok, List<String> lines) {
        public Outcome {
            lines = List.copyOf(lines);
        }

        public String summary() {
            return String.join("\n", lines);
        }
    }

    private Shortcuts() {}

    /**
     * The repository root the shortcut runs {@code run.cmd} in: the folder above {@code target\} when CAT runs from
     * {@code target\cat-java.jar} (or {@code target\classes}), else {@code userDir}.
     */
    public static Path repoRoot(Path codeSource, Path userDir) {
        if (codeSource != null) {
            Path source = codeSource.toAbsolutePath().normalize();
            Path parent = source.getParent();
            if (parent != null && isTarget(parent) && parent.getParent() != null) {
                return parent.getParent();
            }
        }
        return userDir.toAbsolutePath().normalize();
    }

    /** The running CAT-Java's repository root ({@link #repoRoot(Path, Path)} on this jar). */
    public static Path repoRoot() {
        Path source = null;
        try {
            var code = Shortcuts.class.getProtectionDomain().getCodeSource();
            if (code != null && code.getLocation() != null) {
                source = Path.of(code.getLocation().toURI());
            }
        } catch (Exception e) {
            // No code source: the working folder.
        }
        return repoRoot(source, Path.of(System.getProperty("user.dir")));
    }

    /**
     * The PowerShell that writes {@link #LINK_NAME} to the Desktop and the Start menu's Programs folder. It prints
     * {@code MADE<tab>place} or {@code FAILED<tab>place<tab>why} per place, and never throws for one place.
     */
    public static String script(Path repoRoot, Path icon) {
        String runCmd = repoRoot.resolve("run.cmd").toString();
        return String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$shell = New-Object -ComObject WScript.Shell",
                "$target = Join-Path $env:SystemRoot 'System32\\conhost.exe'",
                "$cmd = Join-Path $env:SystemRoot 'System32\\cmd.exe'",
                "$arguments = '--headless \"' + $cmd + '\" /c \"' + " + quote(runCmd) + " + '\" web'",
                "foreach ($place in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {",
                "  if (-not $place) { continue }",
                "  try {",
                "    $link = $shell.CreateShortcut((Join-Path $place " + quote(LINK_NAME) + "))",
                "    $link.TargetPath = $target",
                "    $link.Arguments = $arguments",
                "    $link.WorkingDirectory = " + quote(repoRoot.toString()),
                "    $link.WindowStyle = 7",
                "    $link.Description = 'CAT-Java - Commander Automation Terminal, in its own window'",
                "    $link.IconLocation = " + quote(icon + ",0"),
                "    $link.Save()",
                "    \"MADE`t$place\"",
                "  } catch {",
                "    \"FAILED`t$place`t$($_.Exception.Message)\"",
                "  }",
                "}");
    }

    /** The PowerShell command line for {@link #script}: no profile, no prompts, the script encoded. */
    public static List<String> command(String script) {
        String encoded = Base64.getEncoder().encodeToString(
                ("$ProgressPreference = 'SilentlyContinue'; " + script).getBytes(StandardCharsets.UTF_16LE));
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded);
    }

    /**
     * Adds the shortcuts: checks {@code run.cmd} is there, copies the icon to {@code catJavaDir}, runs the script.
     * Never throws.
     */
    public static Outcome add(Path repoRoot, Path catJavaDir, Runner runner) {
        if (!Files.isRegularFile(repoRoot.resolve("run.cmd"))) {
            return new Outcome(false, List.of("No run.cmd in " + repoRoot + ": start CAT-Java from its checkout to "
                    + "add the shortcut."));
        }
        Path icon = catJavaDir.resolve(ICON_NAME);
        try {
            writeIcon(icon);
        } catch (IOException e) {
            return new Outcome(false, List.of("Could not write the icon " + icon + ": " + e.getMessage()));
        }
        Ran ran = runner.run(command(script(repoRoot, icon)), TIMEOUT);
        return outcome(ran);
    }

    /** What the script's output says. */
    static Outcome outcome(Ran ran) {
        List<String> lines = new ArrayList<>();
        boolean ok = ran.exitCode() == 0;
        int made = 0;
        for (String line : ran.stdout().split("\\R")) {
            String[] parts = line.strip().split("\t", 3);
            if (parts.length >= 2 && parts[0].equals("MADE")) {
                lines.add("CAT-Java shortcut: " + parts[1]);
                made++;
            } else if (parts.length >= 2 && parts[0].equals("FAILED")) {
                lines.add("Could not add the CAT-Java shortcut to " + parts[1]
                        + (parts.length > 2 ? ": " + parts[2] : ""));
                ok = false;
            }
        }
        if (ran.exitCode() != 0) {
            String why = ran.stderr().strip();
            lines.add("Adding the CAT-Java shortcut failed" + (why.isEmpty() ? " (exit " + ran.exitCode() + ")"
                    : ": " + why));
        } else if (made == 0 && ok) {
            lines.add("No Desktop or Start menu folder to add the CAT-Java shortcut to.");
            ok = false;
        }
        return new Outcome(ok, lines);
    }

    private static void writeIcon(Path icon) throws IOException {
        Files.createDirectories(icon.getParent());
        try (InputStream in = Shortcuts.class.getResourceAsStream(ICON_RESOURCE)) {
            if (in == null) {
                throw new IOException("no " + ICON_RESOURCE + " in the jar");
            }
            Files.copy(in, icon, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** A PowerShell single-quoted string: a quote inside is doubled. */
    static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** {@code target}, the folder holding both {@code cat-java.jar} and {@code classes}. */
    private static boolean isTarget(Path dir) {
        Path name = dir.getFileName();
        return name != null && name.toString().equals("target");
    }
}
