package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The two files CAT-Java ships rather than rewrites, {@code agent.py} and the BTT menu agent's
 * {@code MenuAgent.java}, are the Python CAT's own, line endings aside. Read from the Python checkout when it is on this
 * machine (read only), as {@code LocatorsParityTest} reads its catalogue; skipped where it is not.
 */
class ShippedResourcesTest {

    private static final Path PYTHON_VATS = Path.of("C:\\gitrepos\\CAT\\src\\petrotest\\devices\\vats");

    private static String normalised(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void theShippedAgentIsThePythonCatsAgent() throws IOException {
        Path python = PYTHON_VATS.resolve("agent.py");
        Assumptions.assumeTrue(Files.isRegularFile(python), "the Python CAT is not checked out here");
        assertEquals(normalised(Files.readAllBytes(python)), normalised(AgentScript.bytes()));
    }

    @Test
    void theShippedMenuAgentIsThePythonCatsMenuAgent() throws IOException {
        Path python = PYTHON_VATS.resolve("btt_agent").resolve("MenuAgent.java");
        Assumptions.assumeTrue(Files.isRegularFile(python), "the Python CAT is not checked out here");
        assertEquals(normalised(Files.readAllBytes(python)), normalised(BttMenuAgent.source()));
    }
}
