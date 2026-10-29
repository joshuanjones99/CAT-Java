package com.verifone.cat.authoring;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.authoring.Check.Analysis;
import com.verifone.cat.authoring.Check.Diagnostic;
import com.verifone.cat.provision.ProvisionPaths;

/**
 * {@code cat-java steps list [--format text|markdown|json] [--out <file>]} and {@code cat-java steps check [paths...]
 * [--json]}: the phrases an authored {@code .feature} case may use, and the check of cases against them. The Python's
 * {@code petrotest steps} (DESIGN.md 5.6).
 *
 * <p>{@code list} prints the catalogue ({@link Catalogue}); {@code --out} writes it as UTF-8 with LF endings instead of
 * the console (a shell redirect on Windows would write the console's code page). {@code check} runs {@link Check} over
 * {@code .feature} files or directories, by default the Python CAT checkout's {@code tests/suites}
 * ({@code CAT_PYTHON_ROOT}, the suites CAT-Java runs), and exits 1 on any error, 2 when a path does not exist.
 */
public final class StepsCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java steps list [--format text|markdown|json] [--out <file>]",
            "       cat-java steps check [paths...] [--json]",
            "",
            "  list   print the step catalogue (default text); --out writes it to a file, as UTF-8",
            "  check  check .feature files or directories against the library; exit 1 on any error",
            "         (default: the Python CAT's tests/suites)");

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "steps".equals(args.get(0))) {
            args.remove(0);
        }
        if (args.isEmpty()) {
            err.println("steps needs an action: list or check");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        String action = args.remove(0);
        return switch (action) {
            case "list" -> list(args, out, err);
            case "check" -> check(args, env, out, err);
            case "-h", "--help" -> {
                out.println(USAGE);
                yield EXIT_OK;
            }
            default -> {
                err.println("unknown action: " + action + " (list or check)");
                err.println(USAGE);
                yield EXIT_USAGE;
            }
        };
    }

    private static int list(List<String> args, PrintStream out, PrintStream err) {
        String format = "text";
        String outFile = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--format") || arg.equals("--out")) {
                if (i + 1 >= args.size()) {
                    err.println(arg + " needs a value");
                    return EXIT_USAGE;
                }
                String value = args.get(++i);
                if (arg.equals("--format")) {
                    if (!List.of("text", "markdown", "json").contains(value)) {
                        err.println("--format: invalid choice: '" + value + "' (choose from 'text', 'markdown', 'json')");
                        return EXIT_USAGE;
                    }
                    format = value;
                } else {
                    outFile = value;
                }
            } else {
                err.println("unknown argument: " + arg);
                err.println(USAGE);
                return EXIT_USAGE;
            }
        }
        String rendered = switch (format) {
            case "markdown" -> Catalogue.renderMarkdown();
            case "json" -> Catalogue.renderJson();
            default -> Catalogue.renderText();
        };
        if (outFile != null) {
            try {
                Files.writeString(Path.of(outFile), rendered, StandardCharsets.UTF_8);
            } catch (IOException e) {
                err.println(outFile + ": " + e.getMessage());
                return EXIT_USAGE;
            }
        } else {
            out.print(rendered);
            out.flush();
        }
        return EXIT_OK;
    }

    private static int check(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        boolean json = false;
        List<Path> paths = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--json")) {
                json = true;
            } else if (arg.startsWith("--")) {
                err.println("unknown argument: " + arg);
                err.println(USAGE);
                return EXIT_USAGE;
            } else {
                paths.add(Path.of(arg));
            }
        }
        if (paths.isEmpty()) {
            paths.add(ProvisionPaths.pythonRoot(env).resolve("tests").resolve("suites"));
        }
        List<String> missing = new ArrayList<>();
        for (Path path : paths) {
            if (!Files.exists(path)) {
                missing.add(path.toString());
            }
        }
        if (!missing.isEmpty()) {
            err.println("no such file or directory: " + String.join(", ", missing));
            return EXIT_USAGE;
        }
        Map<Path, Analysis> results;
        try {
            results = Check.checkFiles(paths);
        } catch (IOException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        int errors = 0;
        int warnings = 0;
        for (Analysis analysis : results.values()) {
            errors += analysis.errors().size();
            warnings += analysis.warnings().size();
        }
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<Path, Analysis> entry : results.entrySet()) {
                List<Object> found = new ArrayList<>();
                for (Diagnostic d : entry.getValue().diagnostics()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("line", d.line());
                    item.put("severity", d.severity());
                    item.put("code", d.code());
                    item.put("message", d.message());
                    found.add(item);
                }
                payload.put(entry.getKey().toString(), found);
            }
            out.println(PyJson.indented(payload, 2, true));
        } else {
            for (Map.Entry<Path, Analysis> entry : results.entrySet()) {
                for (Diagnostic d : entry.getValue().diagnostics()) {
                    out.println(d.render(entry.getKey().toString()));
                }
            }
            out.println(results.size() + " file(s): " + errors + " error(s), " + warnings + " warning(s)");
        }
        return errors > 0 ? EXIT_FAILED : EXIT_OK;
    }
}
