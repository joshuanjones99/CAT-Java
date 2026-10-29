package com.verifone.cat.recorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.verifone.cat.pos.driver.RegisterEndpoint;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.validate.PyRepr;

/**
 * {@code cat-java record (--rig <name> [--register <id>] | --cdp <url> [--fake-pos]) --out <file.ndjson>}: watch a
 * register's POS page over CDP and write each tap to a recording file. The Python's {@code petrotest record}
 * (CAT_RESULTS_AND_GENERATE.md P11.2), observe-only.
 *
 * <p>A line (or end of input) on stdin stops the recording, as the Python's does; Ctrl+C ends it with the footer's reason
 * {@code interrupted} and every tap written so far kept. {@code --fake-pos} (or its old name {@code --pos-double}) says
 * in the file that {@code --cdp} is the fake POS's Chromium, so no baseline is ever taken from it. The register's CDP
 * endpoint comes from the rig file and is never written to the recording.
 *
 * <p>Exit codes, as the Python's: 0 recorded; 1 the recorder could not attach (the empty file is removed) or was
 * interrupted; 2 a usage error, an existing output file, or an unknown register.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): it has watched a local Chromium page only.
 */
public final class RecordCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java record (--rig <name> [--register <id>] | --cdp <url> [--fake-pos]) --out <file.ndjson>",
            "",
            "Watch a register's POS page over CDP and write each tap to a recording file; press Enter to stop.",
            "  --rig       rig name; the register's CDP endpoint comes from its file",
            "  --cdp       a CDP endpoint to attach to instead, e.g. from a tunnel",
            "  --register  register id under --rig; default first",
            "  --out       the recording to write (.ndjson); new file",
            "  --fake-pos  --cdp is the fake POS's Chromium: say so in the file, so no baseline is taken",
            "              (--pos-double, its name before 2026-10-08, still works)");

    /** Records one session; production is {@link Capture#record}, a test passes its own. */
    @FunctionalInterface
    public interface Recorder {
        int record(String cdpUrl, Writer stream, Capture.Source source, java.util.function.BooleanSupplier stop,
                java.util.function.BooleanSupplier interrupted, double connectTimeoutMs,
                java.util.function.Consumer<String> announce);
    }

    private final Recorder recorder;

    public RecordCommand() {
        this(Capture::record);
    }

    public RecordCommand(Recorder recorder) {
        this.recorder = recorder;
    }

    /** Runs with the process environment and standard streams. */
    public int run(String[] argv) {
        return run(argv, System.getenv(), System.in, System.out, System.err, true);
    }

    /**
     * Runs the command.
     *
     * @param hookInterrupt whether to install a shutdown hook that ends the recording as {@code interrupted} on Ctrl+C
     */
    public int run(String[] argv, Map<String, String> env, InputStream in, PrintStream out, PrintStream err,
            boolean hookInterrupt) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "record".equals(args.get(0))) {
            args.remove(0);
        }
        String rigName = null;
        String cdp = null;
        String register = null;
        String outPath = null;
        boolean fakePos = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--rig", "--cdp", "--register", "--out" -> {
                    if (i + 1 >= args.size()) {
                        err.println(arg + " needs a value");
                        err.println(USAGE);
                        return EXIT_USAGE;
                    }
                    String value = args.get(++i);
                    switch (arg) {
                        case "--rig" -> rigName = value;
                        case "--cdp" -> cdp = value;
                        case "--register" -> register = value;
                        default -> outPath = value;
                    }
                }
                case "--fake-pos", "--pos-double" -> fakePos = true;
                case "-h", "--help" -> {
                    out.println(USAGE);
                    return EXIT_OK;
                }
                default -> {
                    err.println("unknown argument: " + arg);
                    err.println(USAGE);
                    return EXIT_USAGE;
                }
            }
        }
        if ((rigName == null) == (cdp == null)) {
            err.println("one of --rig or --cdp is required, and not both");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        if (outPath == null) {
            err.println("--out is required");
            err.println(USAGE);
            return EXIT_USAGE;
        }

        String endpoint;
        Capture.Source source;
        Timeouts timeouts;
        if (rigName != null) {
            Rig rig;
            try {
                rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
            } catch (RuntimeException e) {
                err.println(e.getMessage());
                return EXIT_USAGE;
            }
            Rig.Register found = null;
            for (Rig.Register r : rig.registers()) {
                if (register == null || register.equals(r.id())) {
                    found = r;
                    break;
                }
            }
            if (found == null) {
                err.println("rig " + rigName + " has no register " + PyRepr.repr(register));
                return EXIT_USAGE;
            }
            endpoint = RegisterEndpoint.of(found).cdpUrl();
            source = new Capture.Source("rig", rigName, found.id());
            timeouts = Timeouts.of(rig.timeouts());
        } else {
            endpoint = cdp;
            source = new Capture.Source(fakePos ? "fake_pos" : "cdp", null, null);
            timeouts = Timeouts.defaults();
        }
        Path file = Path.of(outPath);
        if (Files.exists(file)) {
            err.println(file + " already exists; pick a new file");
            return EXIT_USAGE;
        }

        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread reader = new Thread(() -> waitForEnter(in, stop), "cat-record-stdin");
        reader.setDaemon(true);
        Thread hook = new Thread(() -> {
            interrupted.set(true);
            try {
                finished.await(10, TimeUnit.SECONDS); // let the footer be written
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "cat-record-interrupt");
        int taps;
        try {
            if (file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            if (hookInterrupt) {
                Runtime.getRuntime().addShutdownHook(hook);
            }
            reader.start();
            try (Writer stream = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                taps = recorder.record(endpoint, stream, source, stop::get, interrupted::get,
                        (double) timeouts.defaultWait().toMillis(), line -> {
                            out.println(line);
                            out.flush();
                        });
            }
        } catch (Capture.RecorderException e) {
            err.println(e.getMessage());
            removeIfEmpty(file);
            return EXIT_FAILED;
        } catch (IOException e) {
            err.println(file + ": " + e.getMessage());
            removeIfEmpty(file);
            return EXIT_FAILED;
        } finally {
            finished.countDown();
            if (hookInterrupt) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // the JVM is already shutting down: the hook is running
                }
            }
        }
        if (interrupted.get()) {
            err.println("interrupted; " + file + " holds every tap written so far");
            return EXIT_FAILED;
        }
        out.println("recorded " + taps + " tap(s) to " + file);
        return EXIT_OK;
    }

    /** A line, or end of input, on stdin stops the recording. */
    private static void waitForEnter(InputStream in, AtomicBoolean stop) {
        try {
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException e) {
            return; // no usable stdin: Ctrl+C or a closed browser ends it
        }
        stop.set(true);
    }

    private static void removeIfEmpty(Path file) {
        try {
            if (Files.isRegularFile(file) && Files.size(file) == 0) {
                Files.delete(file); // nothing was recorded; leave no empty file behind
            }
        } catch (IOException e) {
            // best effort
        }
    }
}
