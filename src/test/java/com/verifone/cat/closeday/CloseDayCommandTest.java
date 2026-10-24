package com.verifone.cat.closeday;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.closeday.CloseDayFakes.Access;
import com.verifone.cat.closeday.CloseDayFakes.Box;
import com.verifone.cat.closeday.CloseDayFakes.Capture;
import com.verifone.cat.devices.commander.CommanderFault;

/**
 * The {@code close-day} command line: its usage errors, and the exit code the logged outcome gives. The close
 * itself runs against the in-memory Commander through the command's factory, so no Commander is reached.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class CloseDayCommandTest {

    @TempDir
    Path dir;

    /** A rigs folder holding the test rig {@code bench}, whose hosts never resolve. */
    private Path rigs() throws IOException {
        Path rigs = Files.createDirectories(dir.resolve("rigs"));
        try (InputStream in = CloseDayCommandTest.class.getResourceAsStream("/launch/rigs/bench.yaml")) {
            if (in == null) {
                throw new IOException("missing test resource launch/rigs/bench.yaml");
            }
            Files.write(rigs.resolve("bench.yaml"), in.readAllBytes());
        }
        return rigs;
    }

    private Map<String, String> env() throws IOException {
        return Map.of("PETROTEST_RIGS_DIR", rigs().toString());
    }

    /** A command whose close runs against {@code box}; the factory receives the command's own streams. */
    private CloseDayCommand commandOver(Box box) {
        return new CloseDayCommand((rig, env, out, err) -> {
            Access access = new Access(box);
            return new CloseDay(access, RegisterPass.NOT_PORTED, dir.resolve("base"), dir.resolve("results"),
                    CloseDayFakes.fast(), out, err);
        });
    }

    private static Box logWith(List<String> before, List<String> after) {
        return new Box(before, after);
    }

    @Test
    void noRigIsAUsageError() throws IOException {
        Capture out = new Capture();
        Capture err = new Capture();

        int code = commandOver(logWith(List.of(), List.of()))
                .run(new String[] {"close-day"}, env(), out.stream, err.stream);

        assertEquals(CloseDayCommand.EXIT_USAGE, code);
        assertTrue(err.text().contains("--rig is required"), err.text());
    }

    @Test
    void anUnknownRigIsAUsageError() throws IOException {
        Capture err = new Capture();

        int code = commandOver(logWith(List.of(), List.of()))
                .run(new String[] {"close-day", "--rig", "nosuchrig"}, env(), new Capture().stream, err.stream);

        assertEquals(CloseDayCommand.EXIT_USAGE, code);
        assertTrue(err.text().contains("cannot load rig nosuchrig"), err.text());
    }

    @Test
    void aFlagWithoutItsValueIsAUsageError() throws IOException {
        Capture err = new Capture();

        int code = commandOver(logWith(List.of(), List.of()))
                .run(new String[] {"--rig"}, env(), new Capture().stream, err.stream);

        assertEquals(CloseDayCommand.EXIT_USAGE, code);
    }

    @Test
    void aCompletedCloseExitsZero() throws IOException {
        Box box = logWith(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.ACKED, CloseDayFakes.IDLE));

        int code = commandOver(box)
                .run(new String[] {"close-day", "--rig", "bench"}, env(), new Capture().stream, new Capture().stream);

        assertEquals(CloseDayCommand.EXIT_OK, code);
        assertTrue(box.calls.contains("cclosepdnow"), box.calls.toString());
    }

    @Test
    void aCancelledCloseExitsOne() throws IOException {
        Box box = logWith(List.of(CloseDayFakes.UNRELATED), List.of(CloseDayFakes.LOCKING, CloseDayFakes.CANCELED));

        int code = commandOver(box)
                .run(new String[] {"--rig", "bench"}, env(), new Capture().stream, new Capture().stream);

        assertEquals(CloseDayCommand.EXIT_FAILED, code);
    }

    @Test
    void aRefusalOnAForcedAeodExitsFour() throws IOException {
        Box box = logWith(List.of(CloseDayFakes.UNRELATED), List.of());
        CloseDayCommand command = new CloseDayCommand((rig, env, out, err) -> {
            Access access = new Access(box);
            access.cgi.refinteg = "<reportConfig><aeod><forceAEOD>true</forceAEOD></aeod></reportConfig>";
            access.cgi.closeActions.add(() -> {
                throw new CommanderFault("CGIPortal.InternalError");
            });
            return new CloseDay(access, RegisterPass.NOT_PORTED, dir.resolve("base"), dir.resolve("results"),
                    CloseDayFakes.fast(), out, err);
        });
        Capture err = new Capture();

        int code = command.run(new String[] {"--rig", "bench"}, env(), new Capture().stream, err.stream);

        assertEquals(CloseDay.Outcome.FORCED_AEOD.exitCode(), code);
        assertTrue(err.text().contains("forces automatic end of day"), err.text());
    }

    @Test
    void theHelpTextExitsZero() throws IOException {
        Capture out = new Capture();

        int code = commandOver(logWith(List.of(), List.of()))
                .run(new String[] {"close-day", "--help"}, env(), out.stream, new Capture().stream);

        assertEquals(CloseDayCommand.EXIT_OK, code);
        assertTrue(out.text().contains("usage: cat-java close-day --rig"), out.text());
    }
}
