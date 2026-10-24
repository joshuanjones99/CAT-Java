package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The real runner, on a child JVM that does nothing but print its version. Nothing is installed. */
class JarsTest {

    private static String java() {
        return System.getProperty("java.home") + "\\bin\\java.exe";
    }

    @Test
    @Timeout(120)
    void aChildRunsToItsEndAndItsOutputIsRead() throws IOException {
        var result = Jars.SYSTEM.run(List.of(java(), "-version"), Duration.ofSeconds(90));

        assertEquals(0, result.exitCode());
        assertFalse(result.stderr().isBlank(), "java -version prints to stderr");
    }

    @Test
    @Timeout(120)
    void aChildThatOutlastsItsTimeoutIsKilledAndTheFailureSaysSo() {
        var ex = assertThrows(IOException.class, () -> Jars.SYSTEM.run(
                List.of(java(), "-version"), Duration.ofMillis(1)));

        assertTrue(ex.getMessage().startsWith("timed out"), ex.getMessage());
    }
}
