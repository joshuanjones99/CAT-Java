package com.verifone.cat.recorder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.authoring.Recorded.PhrasedStep;

/**
 * {@code cat-java generate <recording> --out <dir> [--name test_...] [--story PETROCORE-#] [--key PETROCORE-# |
 * --placeholder N] [--title ...]}: turn a recording into an authored case. The Python's {@code petrotest generate}
 * (P11.4), writing a {@code .feature} where the Python writes a pytest module: the decision is on {@link Generate}.
 *
 * <p>Exit codes, as the Python's: 0 written and every step phrased; 1 written, but some taps could not become a library
 * phrase (the checker refuses those lines until they are resolved by hand); 2 a usage error, an unreadable recording, or
 * a refusal that wrote nothing.
 */
public final class GenerateCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java generate <recording> --out <dir> [--name test_...] [--story PETROCORE-#]",
            "                         [--key PETROCORE-# | --placeholder N] [--title ...]",
            "",
            "Turn a petrotest-recording file into an authored case, PETROCORE-<n>-TestCase.feature.",
            "  --out          directory the case is written to",
            "  --name         the case's name (test_...); names the file with neither --key nor --placeholder",
            "  --story        the story ticket's PETROCORE-# (D17); names the Feature line",
            "  --key          the case's own PETROCORE-# from Xray; names the file",
            "  --placeholder  before Xray: the case's place in its session, 1, 2, 3...; names the file (D25)",
            "  --title        the scenario's name");

    /** Runs with the standard streams. */
    public int run(String[] argv) {
        return run(argv, System.out, System.err);
    }

    public int run(String[] argv, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "generate".equals(args.get(0))) {
            args.remove(0);
        }
        String recordingPath = null;
        String outDir = null;
        String name = "test_recorded_case";
        String story = null;
        String key = null;
        Integer placeholder = null;
        String title = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-h") || arg.equals("--help")) {
                out.println(USAGE);
                return EXIT_OK;
            }
            if (arg.startsWith("--")) {
                if (i + 1 >= args.size()) {
                    err.println(arg + " needs a value");
                    err.println(USAGE);
                    return EXIT_USAGE;
                }
                String value = args.get(++i);
                switch (arg) {
                    case "--out" -> outDir = value;
                    case "--name" -> name = value;
                    case "--story" -> story = value;
                    case "--key" -> key = value;
                    case "--placeholder" -> {
                        try {
                            placeholder = Integer.parseInt(value.strip());
                        } catch (NumberFormatException e) {
                            err.println("--placeholder: invalid int value: '" + value + "'");
                            return EXIT_USAGE;
                        }
                    }
                    case "--title" -> title = value;
                    default -> {
                        err.println("unknown argument: " + arg);
                        err.println(USAGE);
                        return EXIT_USAGE;
                    }
                }
            } else if (recordingPath == null) {
                recordingPath = arg;
            } else {
                err.println("unexpected argument: " + arg);
                err.println(USAGE);
                return EXIT_USAGE;
            }
        }
        if (recordingPath == null || outDir == null) {
            err.println(recordingPath == null ? "a recording file is required" : "--out is required");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        if (key != null && placeholder != null) {
            err.println("argument --placeholder: not allowed with argument --key");
            return EXIT_USAGE;
        }
        Recording recording;
        Generate.Generated result;
        try {
            recording = Recording.read(Path.of(recordingPath));
            result = Generate.generate(recording, Path.of(outDir), name, story, key, placeholder, title);
        } catch (IOException | RecordingException | Generate.GenerateException e) {
            err.println(e instanceof IOException ? recordingPath + ": " + e.getMessage() : e.getMessage());
            return EXIT_USAGE;
        }
        out.println("wrote " + result.caseFile() + " (" + result.steps().size() + " step(s))");
        for (Path path : result.baselines()) {
            out.println("wrote " + path);
        }
        if (recording.footer() == null) {
            err.println("warning: the recording has no footer; it was cut off");
        }
        List<PhrasedStep> failing = result.failing();
        if (!failing.isEmpty()) {
            List<Integer> seqs = new ArrayList<>();
            failing.forEach(step -> seqs.addAll(step.step().seqs()));
            err.println(failing.size() + " step(s) not generated (taps " + seqs + "); the case fails the checker until "
                    + "they are resolved by hand");
            return EXIT_FAILED;
        }
        return EXIT_OK;
    }
}
