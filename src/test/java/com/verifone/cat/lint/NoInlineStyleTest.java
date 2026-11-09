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
 * The web pages' design gate (DESIGN.md, "Rules"): every colour is a token in {@code public/cat.css}, and a page
 * styles itself with classes, never with an inline {@code style} attribute. One stylesheet is what keeps every page
 * on one look and makes the dark appearance work; a literal colour in a page is the colour that stays light in dark
 * mode.
 *
 * <p>The pages build their HTML in Java strings, so this reads the raw source of {@code com.verifone.cat.web}
 * (strings included, unlike the other gates). An HTML character reference ({@code &#9888;}) is not a colour.
 */
class NoInlineStyleTest {

    private static final Path WEB = Path.of("src", "main", "java", "com", "verifone", "cat", "web");

    private static final Pattern STYLE_ATTRIBUTE = Pattern.compile("\\sstyle\\s*=\\s*\\\\?[\"']");
    private static final Pattern HEX_COLOUR = Pattern.compile("(?<![&\\w])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\\b");
    private static final Pattern COLOUR_FUNCTION = Pattern.compile("\\b(?:rgba?|hsla?)\\(");

    @Test
    void pagesUseTheStylesheetsTokensAndClasses() throws IOException {
        List<Path> files = LintFiles.files(WEB, name -> name.endsWith(".java"), skip -> false);
        assertFalse(files.isEmpty(), "no sources under " + WEB.toAbsolutePath() + ": the gate would pass on nothing");

        List<String> found = new ArrayList<>();
        for (Path file : files) {
            found.addAll(violationsIn(LintFiles.rel(file), Files.readString(file)));
        }
        assertTrue(found.isEmpty(), "page styling outside cat.css (use a class and a --cat-* token):\n  "
                + String.join("\n  ", found));
    }

    static List<String> violationsIn(String path, String source) {
        List<String> found = new ArrayList<>();
        report(found, path, source, STYLE_ATTRIBUTE, "an inline style attribute");
        report(found, path, source, HEX_COLOUR, "a literal colour");
        report(found, path, source, COLOUR_FUNCTION, "a literal colour");
        return found;
    }

    private static void report(List<String> found, String path, String source, Pattern pattern, String what) {
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            int line = 1;
            for (int i = 0; i < m.start(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            found.add(path + ":" + line + " " + m.group().trim() + ": " + what);
        }
    }

    @Test
    void theGateCatchesInlineStylesAndColours() {
        String source = """
                class X {
                    String a = "<p class=\\"muted\\" style=\\"padding: 0\\">";
                    String b = ".x { color: #c6263e; background: rgba(0, 0, 0, .4); }";
                    String c = "<span>&#9888; warning</span> <a href=\\"#main\\">";
                }
                """;

        List<String> found = violationsIn("Web.java", source);

        assertEquals(3, found.size(), found.toString());
        assertTrue(found.get(0).startsWith("Web.java:2 style="), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.startsWith("Web.java:3 #c6263e")), found.toString());
        assertTrue(found.stream().anyMatch(f -> f.startsWith("Web.java:3 rgba(")), found.toString());
    }
}
