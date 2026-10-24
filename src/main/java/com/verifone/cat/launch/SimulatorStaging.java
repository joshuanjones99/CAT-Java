package com.verifone.cat.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.verifone.cat.sims.Runner;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;

/**
 * The simulator staging lines of System Checks: the Python CAT's {@code cat.sims.config.verify}, for the checks
 * this port has. Every line is advisory, as the Python's are (its {@code Check.required} defaults to false): a
 * missing runtime shows as a WARN here, and step 3 names the simulator that cannot start.
 *
 * <p>Ported: the tools root, the Java 17 home, its {@code javaw.exe}, the two Java Access Bridge files beside it,
 * each row's working folder, each row's placeholders (every one must resolve), and each {@code exe} row's program.
 * <p>Not ported: the {@code pyjab} package and the Access Bridge's enabled flag (the setup audit's
 * {@code java_access_bridge} item says that), the {@code well_formed} XML parse, and the CAPK source jar. Those lines
 * do not appear, so nothing here claims them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: it reads the tools folder only; it starts nothing.
 */
final class SimulatorStaging {

    /** One line of the check: the Python's {@code Check}, without the required flag (every line here is advisory). */
    record Check(String name, boolean ok, String detail) {
    }

    /** The Access Bridge files the Python checks under the JDK, relative to its home. */
    static final List<String> JAB_FILES = List.of("bin/jabswitch.exe", "bin/windowsaccessbridge-64.dll");

    private SimulatorStaging() {
    }

    /**
     * The staging lines for {@code rows} under {@code ctx}. Reads the file system only, and never throws for a row:
     * a placeholder that will not resolve is a FAIL line for that row.
     */
    static List<Check> verify(SimContext ctx, List<SimEntry> rows) {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("tools_root", Files.isDirectory(ctx.toolsRoot()), ctx.toolsRoot().toString()));
        Path home = ctx.javaHome();
        checks.add(new Check("java_home", Files.isDirectory(home), home.toString()));
        Optional<Path> javaw = ctx.javawExe();
        checks.add(new Check("javaw.exe", javaw.isPresent(),
                javaw.map(Path::toString).orElse("no jdk* with javaw")));
        Path jdk = javaw.map(exe -> exe.getParent().getParent()).orElse(null);
        for (String rel : JAB_FILES) {
            Path target = jdk == null ? null : jdk.resolve(rel);
            checks.add(new Check(Path.of(rel).getFileName().toString(),
                    target != null && Files.isRegularFile(target),
                    target == null ? "no jdk resolved" : target.toString()));
        }
        for (SimEntry row : rows) {
            Path cwd = ctx.cwdFor(row);
            checks.add(new Check(row.name() + " cwd", Files.isDirectory(cwd), cwd.toString()));
            try {
                for (String text : placeholderTexts(row)) {
                    ctx.substitute(text, row);
                }
            } catch (IllegalArgumentException e) {
                checks.add(new Check(row.name() + " placeholders", false, e.getMessage()));
                continue;
            }
            checks.add(new Check(row.name() + " placeholders", true, "all resolve"));
            if (row.runner() == Runner.EXE) {
                Path target = Path.of(ctx.substitute(row.exe(), row));
                checks.add(new Check(row.name() + " exe", Files.isRegularFile(target), target.toString()));
            }
        }
        return checks;
    }

    /** The strings a row carries that may hold placeholders: its argv, exe, match token and environment values. */
    private static Set<String> placeholderTexts(SimEntry row) {
        Set<String> texts = new LinkedHashSet<>(row.argv());
        texts.add(row.exe() == null ? "" : row.exe());
        texts.add(row.match() == null ? "" : row.match());
        for (Map.Entry<String, String> env : row.env().entrySet()) {
            texts.add(env.getValue());
        }
        return texts;
    }
}
