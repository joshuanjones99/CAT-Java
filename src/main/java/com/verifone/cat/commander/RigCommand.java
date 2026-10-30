package com.verifone.cat.commander;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * The command line every {@code petrotest <command> --rig R ...} of step 4 shares, as {@code argparse} gives it in the
 * Python's {@code cli.py}: {@code --flag value} or {@code --flag=value}, switches, repeatable flags, an optional
 * positional choice, {@code -h}/{@code --help}, then the rig loaded ({@code _load_rig_or_exit}) and the body run with
 * its lines printed.
 *
 * <p>Exit codes, as the Python's: 0 done, 1 failed, 2 usage or a rig that does not load.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the bodies reach the rig; this class only parses and prints.
 */
public final class RigCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    /**
     * What a command accepts.
     *
     * @param name       the command word, for messages
     * @param usage      its usage line
     * @param valued     flags that take a value (besides {@code --rig}), each with its default (null for none)
     * @param required   valued flags that must be given
     * @param switches   flags without a value
     * @param repeatable valued flags that may be given more than once
     * @param choices    the positional action's choices; empty for a command with none
     */
    public record Spec(String name, String usage, Map<String, String> valued, Set<String> required,
            Set<String> switches, Set<String> repeatable, List<String> choices) {

        public Spec {
            valued = valued == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(valued));
            required = required == null ? Set.of() : Set.copyOf(required);
            switches = switches == null ? Set.of() : Set.copyOf(switches);
            repeatable = repeatable == null ? Set.of() : Set.copyOf(repeatable);
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        /** A spec with only {@code --rig}. */
        public static Spec rigOnly(String name) {
            return new Spec(name, "usage: cat-java " + name + " --rig <name|file.yaml>", Map.of(), Set.of(),
                    Set.of(), Set.of(), List.of());
        }
    }

    /** The parsed command line. */
    public record Args(String rig, String action, Map<String, String> values, Set<String> switches,
            Map<String, List<String>> repeated) {

        /** A valued flag's value, or its default. */
        public String value(String flag) {
            return values.get(flag);
        }

        public boolean has(String flag) {
            return switches.contains(flag);
        }

        /** A repeatable flag's values, in order; empty when none was given. */
        public List<String> all(String flag) {
            return repeated.getOrDefault(flag, List.of());
        }
    }

    /** A command's body: the loaded rig, the arguments, the environment, and where its lines go. */
    @FunctionalInterface
    public interface Body {
        /** @return the exit code */
        int run(Rig rig, Args args, Map<String, String> env, Say say);
    }

    /** Loads a rig by name or path; production is {@link RigLoader#load} over {@code $PETROTEST_RIGS_DIR}. */
    @FunctionalInterface
    public interface Loader {
        Rig load(String rig, Map<String, String> env);

        Loader RIGS = (rig, env) -> RigLoader.load(rig, RigLoader.rigsDir(env));
    }

    private RigCommand() {}

    /** {@code true} as {@link #EXIT_OK}, {@code false} as {@link #EXIT_FAILED}: the Python's {@code _exit_code}. */
    public static int exit(boolean ok) {
        return ok ? EXIT_OK : EXIT_FAILED;
    }

    /** Parses {@code argv} against {@code spec}, loads the rig, runs {@code body}. */
    public static int run(Spec spec, String[] argv, Map<String, String> env, PrintStream out, PrintStream err,
            Loader loader, Body body) {
        Objects.requireNonNull(spec, "spec");
        Map<String, String> environ = env == null ? Map.of() : env;
        Args args;
        try {
            args = parse(spec, argv);
        } catch (Usage e) {
            if (e.getMessage().isEmpty()) {
                out.println(spec.usage());
                return EXIT_OK;
            }
            err.println(spec.name() + ": " + e.getMessage());
            err.println(spec.usage());
            return EXIT_USAGE;
        }
        Rig rig;
        try {
            rig = loader.load(args.rig(), environ);
        } catch (RuntimeException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        return body.run(rig, args, environ, Say.to(out, err));
    }

    /** A usage error; an empty message is {@code --help}. */
    static final class Usage extends RuntimeException {
        Usage(String message) {
            super(message);
        }
    }

    static Args parse(Spec spec, String[] argv) {
        String rig = null;
        String action = null;
        Map<String, String> values = new LinkedHashMap<>(spec.valued());
        Set<String> switches = new LinkedHashSet<>();
        Map<String, List<String>> repeated = new LinkedHashMap<>();
        Set<String> given = new LinkedHashSet<>();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                throw new Usage("");
            }
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (spec.switches().contains(name) && value == null) {
                switches.add(name);
                continue;
            }
            boolean isRig = name.equals("--rig");
            if (isRig || spec.valued().containsKey(name) || spec.repeatable().contains(name)) {
                if (value == null) {
                    if (i + 1 >= argv.length) {
                        throw new Usage(name + " needs a value");
                    }
                    value = argv[++i];
                }
                given.add(name);
                if (isRig) {
                    rig = value;
                } else if (spec.repeatable().contains(name)) {
                    repeated.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                } else {
                    values.put(name, value);
                }
                continue;
            }
            if (!arg.startsWith("-") && !spec.choices().isEmpty() && action == null) {
                if (!spec.choices().contains(arg)) {
                    throw new Usage("argument action: invalid choice: '" + arg + "' (choose from "
                            + String.join(", ", spec.choices()) + ")");
                }
                action = arg;
                continue;
            }
            throw new Usage("unrecognized arguments: " + arg);
        }
        if (!spec.choices().isEmpty() && action == null) {
            throw new Usage("the following arguments are required: action");
        }
        if (rig == null || rig.isBlank()) {
            throw new Usage("the following arguments are required: --rig");
        }
        for (String flag : spec.required()) {
            if (!given.contains(flag)) {
                throw new Usage("the following arguments are required: " + flag);
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        repeated.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new Args(rig, action, java.util.Collections.unmodifiableMap(values), Set.copyOf(switches),
                java.util.Collections.unmodifiableMap(frozen));
    }
}
