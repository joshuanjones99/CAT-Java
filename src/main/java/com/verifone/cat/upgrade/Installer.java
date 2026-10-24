package com.verifone.cat.upgrade;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

/**
 * A running PSI installer: {@code Petro_Suite_Installer.jar -q -addr=<host>}, which never exits by itself
 * once its breadcrumb has landed (it shows a dialog and beeps), so CAT closes it.
 *
 * <p>A port of the Python {@code subprocess.Popen} handle the new install holds. {@link #SYSTEM} starts a
 * real child with its output in the given log file and its input already closed, so it holds no pipe of
 * CAT's.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface Installer {

    /** Starts an installer. */
    @FunctionalInterface
    interface Launcher {
        Installer start(List<String> argv, Path workingDirectory, Path logFile) throws IOException;
    }

    /** The real launcher: one child process, output to {@code logFile}. */
    Launcher SYSTEM = (argv, cwd, logFile) -> {
        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .redirectErrorStream(true);
        Process process = builder.start();
        process.getOutputStream().close();
        return new ProcessInstaller(process);
    };

    /** The exit code, if the installer has exited. */
    OptionalInt exitCode();

    /** Asks the installer to stop. */
    void terminate();

    /** Stops the installer without asking. */
    void kill();

    /** Waits up to {@code timeout} for the installer to exit; false if it has not. */
    boolean awaitExit(Duration timeout) throws InterruptedException;
}
