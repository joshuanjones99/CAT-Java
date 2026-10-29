package com.verifone.cat.launch;

import java.io.IOException;
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
 * this port has. Every line is advisory, as the Python's are (its {@code Check.required} defaults to false), except
 * each {@code well_formed:} file's: a missing runtime shows as a WARN here, and step 3 names the simulator that
 * cannot start.
 *
 * <p>Ported: the tools root, the Java 17 home, its {@code javaw.exe}, the two Java Access Bridge files beside it,
 * each row's working folder, each row's placeholders (every one must resolve), each {@code exe} row's program, each
 * {@code capk_from} row's source jar, and each {@code well_formed:} file (present, not empty, XML that parses:
 * Petro's {@code config/fuelPosTotals.xml} was cut short twice when drive C filled, and the suite then started without
 * complaint while every fuel case failed). That last line is {@link Check#required}: the caller fails System Checks
 * on it. CAT does not repair the file.
 * <p>Not ported: the {@code pyjab} package and the Access Bridge's enabled flag (the setup audit's
 * {@code java_access_bridge} item says that). Those lines do not appear, so nothing here claims them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: it reads the tools folder only; it starts nothing.
 */
final class SimulatorStaging {

    /**
     * One line of the check: the Python's {@code Check}.
     *
     * @param required ends the run in System Checks when not ok; the rest are advisory
     */
    record Check(String name, boolean ok, String detail, boolean required) {

        /** An advisory line. */
        Check(String name, boolean ok, String detail) {
            this(name, ok, detail, false);
        }
    }

    /**
     * The rows System Checks verifies: every row of {@code rows} but a {@code fep_only} one no Secondary FEP of this
     * run needs (the Python's {@code not entry.fep_only or entry.name in wanted}).
     */
    static List<SimEntry> rowsFor(List<SimEntry> rows, java.util.Collection<String> wanted) {
        return rows.stream().filter(row -> !row.fepOnly() || wanted.contains(row.name())).toList();
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
            if (!row.capkFrom().isEmpty()) {
                Path source = cwd.resolve(row.capkFrom());
                checks.add(new Check(row.name() + " CAPK source jar", Files.isRegularFile(source), source.toString()));
            }
            if (Files.isDirectory(cwd)) {
                checks.addAll(wellFormed(row, cwd));
            }
        }
        return checks;
    }

    /** One required line per {@code well_formed:} file: present, not empty, and XML that parses. */
    static List<Check> wellFormed(SimEntry row, Path cwd) {
        List<Check> checks = new ArrayList<>();
        for (String rel : row.wellFormed()) {
            Path path = cwd.resolve(rel);
            String name = row.name() + " " + Path.of(rel).getFileName();
            String problem = parseProblem(path);
            if (problem.isEmpty()) {
                checks.add(new Check(name, true, path.toString(), true));
            } else {
                checks.add(new Check(name, false, path + ": " + problem + "; restore it from a good copy before "
                        + "starting " + row.name(), true));
            }
        }
        return checks;
    }

    /** "" when {@code path} is a non-empty, well-formed XML file; else why not. No DTD or external entity is read. */
    static String parseProblem(Path path) {
        try {
            if (Files.size(path) == 0) {
                return "empty";
            }
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // quiet: the line says it
            builder.parse(path.toFile());
            return "";
        } catch (java.nio.file.NoSuchFileException e) {
            return "no such file";
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException e) {
            return e.getMessage();
        }
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
