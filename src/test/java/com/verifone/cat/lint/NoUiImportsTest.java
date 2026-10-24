package com.verifone.cat.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The Java counterpart of the Python CAT's no-ui-imports gate (DESIGN.md 4.2 rule 3 property 2;
 * {@code tools/lint/no_ui_imports.py}): the engine and harness code stays headless, and the UI is a client of it.
 *
 * <p>Here the UI is the web layer. Three rules, over the code (comments and strings are not code):
 * <ul>
 *   <li>a Swing, AWT or JavaFX type is not used anywhere in {@code src/main/java};</li>
 *   <li>the web server ({@code io.javalin}, {@code org.eclipse.jetty}) is used only in {@code com.verifone.cat.web};</li>
 *   <li>{@code com.verifone.cat.web} is used only inside that package and by {@code Main}, the one CLI entry point
 *       that starts it.</li>
 * </ul>
 * Like the Python gate, there is no allowlist: a case that wants one is a missing route, not an exemption.
 */
class NoUiImportsTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final String WEB_PREFIX = "src/main/java/com/verifone/cat/web/";
    private static final String CLI_ENTRY = "src/main/java/com/verifone/cat/Main.java";

    private static final Pattern TOOLKIT = Pattern.compile("\\b(?:java\\.awt|javax\\.swing|javafx)\\b");
    private static final Pattern WEB_SERVER = Pattern.compile("\\b(?:io\\.javalin|org\\.eclipse\\.jetty)\\b");
    private static final Pattern WEB_PACKAGE = Pattern.compile("\\bcom\\.verifone\\.cat\\.web\\b");

    @Test
    void theUiStaysInTheWebLayer() throws IOException {
        List<Path> files = LintFiles.files(MAIN, name -> name.endsWith(".java"), skip -> false);
        assertFalse(files.isEmpty(), "no sources under " + MAIN.toAbsolutePath() + ": the gate would pass on nothing");

        List<String> found = new ArrayList<>();
        for (Path file : files) {
            found.addAll(violationsIn(LintFiles.rel(file), Files.readString(file)));
        }
        assertTrue(found.isEmpty(), "UI code outside the web layer (the engines stay headless):\n  "
                + String.join("\n  ", found));
    }

    /** Each UI use in {@code source} that its path may not make, as {@code path:line what}. */
    static List<String> violationsIn(String path, String source) {
        JavaSource java = JavaSource.of(source);
        boolean web = path.startsWith(WEB_PREFIX);
        List<String> found = new ArrayList<>();
        report(found, path, java, TOOLKIT, "a UI toolkit (Swing, AWT or JavaFX) is not allowed in main code");
        if (!web) {
            report(found, path, java, WEB_SERVER, "the web server is used only in com.verifone.cat.web");
        }
        if (!web && !path.equals(CLI_ENTRY)) {
            report(found, path, java, WEB_PACKAGE,
                    "com.verifone.cat.web is reached only from inside it and from Main, the CLI entry point");
        }
        return found;
    }

    private static void report(List<String> found, String path, JavaSource java, Pattern pattern, String why) {
        Matcher m = pattern.matcher(java.code);
        while (m.find()) {
            found.add(path + ":" + java.line(m.start()) + " " + m.group() + ": " + why);
        }
    }

    @Test
    void theGateCatchesAUiImportOutsideTheWebLayer() {
        String source = """
                package com.verifone.cat.rig;

                import io.javalin.Javalin;
                import com.verifone.cat.web.WebApp;
                // import javax.swing.JFrame;
                class X {
                    String s = "io.javalin.Javalin";
                    java.awt.Frame f;
                }
                """;

        List<String> found = violationsIn("src/main/java/com/verifone/cat/rig/X.java", source);

        assertEquals(3, found.size(), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.startsWith("src/main/java/com/verifone/cat/rig/X.java:3 io.javalin")),
                found.toString());
        assertTrue(found.stream().anyMatch(f -> f.contains("X.java:4 com.verifone.cat.web")), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.contains("X.java:8 java.awt")), found.toString());
    }

    @Test
    void theWebLayerAndMainMayUseTheirOwnUi() {
        String web = "package com.verifone.cat.web;\nimport io.javalin.Javalin;\n";
        String main = "package com.verifone.cat;\nimport com.verifone.cat.web.WebApp;\n";

        assertEquals(List.of(), violationsIn(WEB_PREFIX + "WebApp.java", web));
        assertEquals(List.of(), violationsIn(CLI_ENTRY, main));
        assertTrue(violationsIn("src/main/java/com/verifone/cat/launch/LaunchCommand.java", main).size() == 1);
    }
}
