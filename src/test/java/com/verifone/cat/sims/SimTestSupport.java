package com.verifone.cat.sims;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Shared helpers for the sims tests. Every child process a test starts is one of these, the
 * test JDK's own {@code java} running {@link FakeSimulator}. Nothing here names a real simulator.
 *
 * <p>Public, so the web tests share these helpers instead of carrying copies.
 */
public final class SimTestSupport {

    private SimTestSupport() {
    }

    /** The test JVM's own {@code java} executable: the only program a test launches. */
    public static Optional<Path> testJava() {
        return ProcessHandle.current().info().command().map(Path::of);
    }

    /** The JDK home of the test JVM, the parent of its {@code bin}. */
    public static Optional<Path> testJdkHome() {
        return testJava().map(exe -> exe.getParent().getParent());
    }

    /** {@code target/test-classes}, where {@link FakeSimulator} was compiled. */
    public static Path testClasses() {
        try {
            return Path.of(FakeSimulator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The argv that runs {@link FakeSimulator} in {@code mode}, after the executable. */
    public static List<String> fakeArgs(int port, String token, String mode) {
        return List.of("-cp", testClasses().toString(), FakeSimulator.class.getName(),
                String.valueOf(port), token, mode);
    }

    /** A port nothing is listening on right now. The test binds it a moment later. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
