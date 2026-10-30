package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * The shipped {@code agent.py}, written out where the staged Python can run it.
 *
 * <p>The Python CAT runs {@code petrotest/devices/vats/agent.py} straight from its package directory
 * ({@code client.AGENT = Path(__file__).with_name("agent.py")}), under the staged connector's Python 3.9, as a separate
 * process: {@code python.exe agent.py --sdk <vats_runner> --km4-port <port>}, working directory the connector's
 * {@code app/}. CAT-Java does not rewrite it: the file is shipped verbatim as the class-path resource
 * {@value #RESOURCE} and copied to a folder of CAT-Java's own ({@code ~/.cat/java/vats/} in production) before each
 * start, rewritten only when its bytes differ, so a newer jar's agent replaces an older one and a running agent's file
 * is never touched needlessly.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class AgentScript {

    /** The class-path resource: the Python CAT's {@code agent.py}, byte for byte. */
    public static final String RESOURCE = "/vats/agent.py";

    /** The file name it is written under; the agent reads nothing of its own name. */
    public static final String FILE_NAME = "agent.py";

    private AgentScript() {}

    /** The shipped script's bytes. */
    public static byte[] bytes() {
        try (InputStream in = AgentScript.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the VATS agent " + RESOURCE + " is not on the class path");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + RESOURCE, e);
        }
    }

    /**
     * Writes the shipped script into {@code dir} (created if absent) unless an identical copy is there, and returns
     * its path.
     */
    public static Path extract(Path dir) {
        byte[] script = bytes();
        Path target = dir.resolve(FILE_NAME);
        try {
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), script)) {
                return target;
            }
            Files.createDirectories(dir);
            Path staging = dir.resolve(FILE_NAME + ".tmp");
            Files.write(staging, script);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("writing the VATS agent to " + target, e);
        }
    }
}
