package com.verifone.cat.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CAT-Java shortcut, as text: the script, its command line and what its output means. Nothing here runs
 * PowerShell or writes a shortcut; the runner is a fake and every folder is a temp folder.
 */
class ShortcutsTest {

    @TempDir
    Path tmp;

    @Test
    void theRepositoryIsTheFolderAboveTarget() {
        Path user = Path.of("C:\\elsewhere");
        assertEquals(Path.of("C:\\gitrepos\\CAT-Java"),
                Shortcuts.repoRoot(Path.of("C:\\gitrepos\\CAT-Java\\target\\cat-java.jar"), user));
        assertEquals(Path.of("C:\\gitrepos\\CAT-Java"),
                Shortcuts.repoRoot(Path.of("C:\\gitrepos\\CAT-Java\\target\\classes"), user));
        assertEquals(user, Shortcuts.repoRoot(Path.of("C:\\lib\\cat-java.jar"), user));
        assertEquals(user, Shortcuts.repoRoot(null, user));
    }

    @Test
    void theScriptPortsThePythonsStep7bUnderItsOwnName() {
        String script = Shortcuts.script(Path.of("C:\\gitrepos\\CAT-Java"), Path.of("C:\\Users\\op\\.cat\\java\\cat.ico"));

        assertTrue(script.contains("Join-Path $env:SystemRoot 'System32\\conhost.exe'"), script);
        assertTrue(script.contains("Join-Path $env:SystemRoot 'System32\\cmd.exe'"), script);
        assertTrue(script.contains("$arguments = '--headless \"' + $cmd + '\" /c \"' + "
                + "'C:\\gitrepos\\CAT-Java\\run.cmd' + '\" web --splash'"), script);
        assertTrue(script.contains("[Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs')"),
                script);
        assertTrue(script.contains("(Join-Path $place 'CAT-Java.lnk')"), script);
        assertFalse(script.contains("'CAT.lnk'"), "never the Python CAT's shortcut: " + script);
        assertTrue(script.contains("$link.WorkingDirectory = 'C:\\gitrepos\\CAT-Java'"), script);
        assertTrue(script.contains("$link.WindowStyle = 7"), script);
        assertTrue(script.contains("$link.IconLocation = 'C:\\Users\\op\\.cat\\java\\cat.ico,0'"), script);
    }

    @Test
    void aQuoteInAPathIsDoubledForPowerShell() {
        String script = Shortcuts.script(Path.of("C:\\o'brien\\CAT-Java"), Path.of("C:\\o'brien\\cat.ico"));

        assertTrue(script.contains("$link.WorkingDirectory = 'C:\\o''brien\\CAT-Java'"), script);
    }

    @Test
    void theCommandEncodesTheScriptSoItsQuotesSurvive() {
        List<String> command = Shortcuts.command("\"MADE`t$place\"");

        assertEquals(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand"), command.subList(0, 6));
        String decoded = new String(Base64.getDecoder().decode(command.get(6)), StandardCharsets.UTF_16LE);
        assertTrue(decoded.endsWith("\"MADE`t$place\""), decoded);
    }

    @Test
    void addingWritesTheIconUnderCatJavaAndRunsTheScriptOnce() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Files.writeString(repo.resolve("run.cmd"), "@echo off\n");
        Path catJava = tmp.resolve("home").resolve(".cat").resolve("java");
        List<List<String>> ran = new ArrayList<>();

        Shortcuts.Outcome outcome = Shortcuts.add(repo, catJava, (argv, timeout) -> {
            ran.add(argv);
            return new Shortcuts.Ran(0, "MADE\tC:\\Users\\op\\Desktop\r\nMADE\tC:\\Users\\op\\Start Menu\\Programs\r\n", "");
        });

        assertTrue(outcome.ok(), outcome.summary());
        assertEquals(List.of("CAT-Java shortcut: C:\\Users\\op\\Desktop",
                "CAT-Java shortcut: C:\\Users\\op\\Start Menu\\Programs"), outcome.lines());
        assertEquals(1, ran.size());
        Path icon = catJava.resolve("cat.ico");
        try (var in = Shortcuts.class.getResourceAsStream("/public/icons/cat.ico")) {
            assertArrayEquals(in.readAllBytes(), Files.readAllBytes(icon));
        }
    }

    @Test
    void withNoRunCmdNothingIsRun() {
        List<List<String>> ran = new ArrayList<>();

        Shortcuts.Outcome outcome = Shortcuts.add(tmp, tmp.resolve("cat"), (argv, timeout) -> {
            ran.add(argv);
            return new Shortcuts.Ran(0, "", "");
        });

        assertFalse(outcome.ok());
        assertTrue(outcome.summary().startsWith("No run.cmd in "), outcome.summary());
        assertTrue(ran.isEmpty());
        assertFalse(Files.exists(tmp.resolve("cat")));
    }

    @Test
    void aPlaceThatFailsOrAScriptThatFailsIsReported() {
        Shortcuts.Outcome partly = Shortcuts.outcome(new Shortcuts.Ran(0,
                "MADE\tD:\\Desktop\nFAILED\tD:\\Programs\tAccess is denied\n", ""));
        assertFalse(partly.ok());
        assertEquals(List.of("CAT-Java shortcut: D:\\Desktop",
                "Could not add the CAT-Java shortcut to D:\\Programs: Access is denied"), partly.lines());

        Shortcuts.Outcome failed = Shortcuts.outcome(new Shortcuts.Ran(1, "", "powershell not found"));
        assertFalse(failed.ok());
        assertEquals(List.of("Adding the CAT-Java shortcut failed: powershell not found"), failed.lines());

        Shortcuts.Outcome none = Shortcuts.outcome(new Shortcuts.Ran(0, "", ""));
        assertFalse(none.ok());
    }
}
