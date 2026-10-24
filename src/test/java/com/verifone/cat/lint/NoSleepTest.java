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
 * The Java counterpart of the Python CAT's no-sleep gate (DESIGN.md 4.2 rule 2; {@code tools/lint/no_sleep.py}): no
 * {@code Thread.sleep} and no {@code TimeUnit.*.sleep} in the harness. A wait is a bounded park against a deadline
 * ({@code RigLease}, {@code upgrade.Bounded}), never a guess about the clock.
 *
 * <p>Scope is {@code src/main/java}. Comments and string literals are not calls, so Javadoc that names the banned
 * call is not flagged.
 */
class NoSleepTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final Pattern SLEEP = Pattern.compile(
            "\\bThread\\s*(?:\\.|::)\\s*sleep\\b|\\bTimeUnit\\s*\\.\\s*\\w+\\s*\\.\\s*sleep\\b");

    @Test
    void noMainSourceSleeps() throws IOException {
        List<Path> files = LintFiles.files(MAIN, name -> name.endsWith(".java"), skip -> false);
        assertFalse(files.isEmpty(), "no sources under " + MAIN.toAbsolutePath() + ": the gate would pass on nothing");

        List<String> found = new ArrayList<>();
        for (Path file : files) {
            found.addAll(sleepsIn(LintFiles.rel(file), Files.readString(file)));
        }
        assertTrue(found.isEmpty(),
                "sleep in main source: wait on a deadline with a bounded park instead (see RigLease):\n  "
                        + String.join("\n  ", found));
    }

    /** Each call found in {@code source}, as {@code path:line call}. */
    static List<String> sleepsIn(String path, String source) {
        JavaSource java = JavaSource.of(source);
        List<String> found = new ArrayList<>();
        Matcher m = SLEEP.matcher(java.code);
        while (m.find()) {
            found.add(path + ":" + java.line(m.start()) + " " + m.group().replaceAll("\\s+", ""));
        }
        return found;
    }

    @Test
    void aCallIsCaughtButACommentOrAStringIsNot() {
        String source = """
                class X {
                    // Thread.sleep(1) in a line comment
                    /** TimeUnit.SECONDS.sleep(1) in Javadoc */
                    String s = "Thread.sleep(1) in a string";
                    void f() throws Exception {
                        Thread.sleep(1);
                        TimeUnit.MILLISECONDS.sleep(2);
                        Runnable r = Thread::sleep;
                    }
                }
                """;

        List<String> found = sleepsIn("X.java", source);

        assertEquals(List.of("X.java:6 Thread.sleep", "X.java:7 TimeUnit.MILLISECONDS.sleep",
                "X.java:8 Thread::sleep"), found);
    }
}
