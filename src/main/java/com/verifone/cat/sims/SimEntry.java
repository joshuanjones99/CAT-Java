package com.verifone.cat.sims;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One simulator's row in the registry: how CAT starts it, and how CAT knows it is up.
 *
 * <p>The Python CAT keeps these rows in {@code cat/sims/registry.yaml} and reads them with
 * {@code cat.sims.config.SimEntry}. The Java port has no YAML resource for them (this package
 * may not add one), so {@link Registry} builds the rows in code. Field meanings are the
 * Python's; only the names are camelCase.
 *
 * <p>The {@code artifactory:} source and {@code match_base:} flag are kept in {@link SimSources}, a table beside
 * this record. Every other registry field is here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param name the row's name, as the Simulators picker and System Checks show it
 * @param runner how the process is launched
 * @param cwd working directory relative to the tools root; empty for the tools root itself
 * @param argv arguments after the executable; placeholders are substituted at launch
 * @param exe for {@link Runner#EXE}, the executable (a placeholder path)
 * @param env extra environment variables, values with placeholders
 * @param readyTcp the TCP port a serving simulator accepts on; 0 for none
 * @param readyClicked the row is ready only once its Swing buttons are pressed
 * @param click Swing buttons to press through the Access Bridge, in order
 * @param log a file under {@code cwd} tailed as this simulator's log; empty for none
 * @param windowed the row raises a Swing frame that steals focus
 * @param match a substring identifying a running instance; empty to use the argv default
 * @param fallbackJdk the JDK to relaunch on when {@code fallbackWhen} appears; empty for none
 * @param fallbackJaxb the JAXB module directory for that relaunch
 * @param fallbackWhen a line of output that triggers the one relaunch
 * @param adoptOnlyMatch adopt a listener on {@code readyTcp} only if its command line matches
 * @param replaceStale a running but not listening process is killed and CAT starts its own
 * @param stagedOnly the row is staged but never launched by CAT
 * @param capkFrom the jar this row's {@code -jar} is built from, with CAPK expiries re-dated
 *     ({@link Capk}), before each launch; empty for none
 * @param olderBaseForm this row's form for a Commander on base 56 or earlier, or null
 * @param wellFormed files under {@code cwd} that must be well-formed XML before a run (System Checks, required)
 * @param tls Petro's tank level simulator files ({@link PetroTls}), or null for a row without one
 * @param fepOnly the host of one secondary FEP only: never on the picker, and checked or started only when the run's
 *     Secondary FEP names it
 * @param supersededBy another row's FEP simulator that serves this row's service when its staged build can
 *     ({@link Fepsim#supersede}), or null
 */
public record SimEntry(
        String name,
        Runner runner,
        String cwd,
        List<String> argv,
        String exe,
        Map<String, String> env,
        int readyTcp,
        boolean readyClicked,
        List<String> click,
        String log,
        boolean windowed,
        String match,
        String fallbackJdk,
        String fallbackJaxb,
        String fallbackWhen,
        boolean adoptOnlyMatch,
        boolean replaceStale,
        boolean stagedOnly,
        String capkFrom,
        SimEntry olderBaseForm,
        List<String> wellFormed,
        Tls tls,
        boolean fepOnly,
        SupersededBy supersededBy) {

    /**
     * A row's {@code tls:} block: the two {@code .properties} files under its {@code cwd} that
     * {@link PetroTls#configure} sets before each launch.
     *
     * @param simulators the file whose {@code Simulators:} list gains or loses {@code TLS}
     * @param port the file whose {@code SerialPort} is the rig's {@code controller.tls_port}
     */
    public record Tls(String simulators, String port) {
        public Tls {
            Objects.requireNonNull(simulators, "simulators");
            Objects.requireNonNull(port, "port");
        }
    }

    /**
     * A row's {@code superseded_by:} block: {@code simulator}'s FEP simulator serves this row's service from its own
     * {@code instance} when that simulator's staged jar is FEPSIM generation {@code fepsim} or later.
     *
     * @param simulator the row whose suite runs the instance
     * @param fepsim the lowest FEPSIM generation that can
     * @param instance the FEPSIM instance id
     * @param legacy the instance's legacy profile ({@code BUYPASS}), or ""
     * @param readyTcp the port the instance listens on
     * @param needsTcp other controller ports the listener's answers depend on (its suite's WireMock)
     */
    public record SupersededBy(String simulator, int fepsim, String instance, String legacy, int readyTcp,
            List<Integer> needsTcp) {
        public SupersededBy {
            Objects.requireNonNull(simulator, "simulator");
            Objects.requireNonNull(instance, "instance");
            legacy = legacy == null ? "" : legacy;
            needsTcp = List.copyOf(needsTcp == null ? List.of() : needsTcp);
        }
    }

    public SimEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(runner, "runner");
        cwd = orEmpty(cwd);
        argv = List.copyOf(argv);
        exe = orEmpty(exe);
        env = Map.copyOf(env);
        click = List.copyOf(click);
        log = orEmpty(log);
        match = orEmpty(match);
        fallbackJdk = orEmpty(fallbackJdk);
        fallbackJaxb = orEmpty(fallbackJaxb);
        fallbackWhen = orEmpty(fallbackWhen);
        capkFrom = orEmpty(capkFrom);
        wellFormed = List.copyOf(wellFormed == null ? List.of() : wellFormed);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** True when the row has a TCP readiness probe. */
    public boolean hasTcpReady() {
        return readyTcp > 0;
    }

    /** True when starting the row needs Swing buttons pressed, which this port cannot do. */
    public boolean needsClicks() {
        return !click.isEmpty() || readyClicked;
    }

    /** This row with no readiness probe: matched by command line alone (the Python's {@code replace(entry, ready={})}). */
    public SimEntry withoutReady() {
        return new SimEntry(name, runner, cwd, argv, exe, env, 0, false, click, log, windowed, match, fallbackJdk,
                fallbackJaxb, fallbackWhen, adoptOnlyMatch, replaceStale, stagedOnly, capkFrom, olderBaseForm,
                wellFormed, tls, fepOnly, supersededBy);
    }

    /** This row with {@code first} put before its argv (the Python's {@code replace(entry, argv=[...])}). */
    public SimEntry withArgvFirst(String first) {
        List<String> longer = new ArrayList<>();
        longer.add(first);
        longer.addAll(argv);
        return new SimEntry(name, runner, cwd, longer, exe, env, readyTcp, readyClicked, click, log, windowed, match,
                fallbackJdk, fallbackJaxb, fallbackWhen, adoptOnlyMatch, replaceStale, stagedOnly, capkFrom,
                olderBaseForm, wellFormed, tls, fepOnly, supersededBy);
    }

    /** A javaw row, in the tools root's Java set. */
    public static Builder javaw(String name) {
        return new Builder(name, Runner.JAVAW);
    }

    /** An exe row: the executable is the row's own. */
    public static Builder exeRow(String name) {
        return new Builder(name, Runner.EXE);
    }

    /** Builds a {@link SimEntry}, one field at a time, so the registry reads as the YAML does. */
    public static final class Builder {
        private final String name;
        private final Runner runner;
        private String cwd = "";
        private final List<String> argv = new ArrayList<>();
        private String exe = "";
        private final Map<String, String> env = new java.util.LinkedHashMap<>();
        private int readyTcp;
        private boolean readyClicked;
        private final List<String> click = new ArrayList<>();
        private String log = "";
        private boolean windowed;
        private String match = "";
        private String fallbackJdk = "";
        private String fallbackJaxb = "";
        private String fallbackWhen = "";
        private boolean adoptOnlyMatch;
        private boolean replaceStale;
        private boolean stagedOnly;
        private String capkFrom = "";
        private SimEntry olderBaseForm;
        private final List<String> wellFormed = new ArrayList<>();
        private Tls tls;
        private boolean fepOnly;
        private SupersededBy supersededBy;

        private Builder(String name, Runner runner) {
            this.name = name;
            this.runner = runner;
        }

        public Builder cwd(String value) {
            this.cwd = value;
            return this;
        }

        public Builder argv(String... values) {
            argv.addAll(List.of(values));
            return this;
        }

        public Builder exe(String path) {
            this.exe = path;
            return this;
        }

        public Builder env(String key, String value) {
            env.put(key, value);
            return this;
        }

        public Builder tcp(int port) {
            this.readyTcp = port;
            return this;
        }

        public Builder clicked() {
            this.readyClicked = true;
            return this;
        }

        public Builder click(String... buttons) {
            click.addAll(List.of(buttons));
            return this;
        }

        public Builder log(String path) {
            this.log = path;
            return this;
        }

        public Builder windowed() {
            this.windowed = true;
            return this;
        }

        public Builder match(String token) {
            this.match = token;
            return this;
        }

        public Builder fallback(String jdk, String jaxb, String when) {
            this.fallbackJdk = jdk;
            this.fallbackJaxb = jaxb;
            this.fallbackWhen = when;
            return this;
        }

        public Builder adoptOnlyMatch() {
            this.adoptOnlyMatch = true;
            return this;
        }

        public Builder replaceStale() {
            this.replaceStale = true;
            return this;
        }

        public Builder stagedOnly() {
            this.stagedOnly = true;
            return this;
        }

        public Builder capkFrom(String jar) {
            this.capkFrom = jar;
            return this;
        }

        /** This row's form on a Commander on base 56 or earlier. */
        public Builder olderBase(SimEntry form) {
            this.olderBaseForm = form;
            return this;
        }

        /** Files under {@code cwd} that must parse as XML before a run. */
        public Builder wellFormed(String... files) {
            wellFormed.addAll(List.of(files));
            return this;
        }

        /** The tank level simulator's two files under {@code cwd}. */
        public Builder tls(String simulators, String port) {
            this.tls = new Tls(simulators, port);
            return this;
        }

        public Builder fepOnly() {
            this.fepOnly = true;
            return this;
        }

        /** {@code superseded_by:}: served by {@code simulator}'s FEP simulator from generation {@code fepsim} on. */
        public Builder supersededBy(String simulator, int fepsim, String instance, String legacy, int readyTcp,
                Integer... needsTcp) {
            this.supersededBy = new SupersededBy(simulator, fepsim, instance, legacy, readyTcp, List.of(needsTcp));
            return this;
        }

        public SimEntry build() {
            return new SimEntry(name, runner, cwd, argv, exe, env, readyTcp, readyClicked,
                    click, log, windowed, match, fallbackJdk, fallbackJaxb, fallbackWhen,
                    adoptOnlyMatch, replaceStale, stagedOnly, capkFrom, olderBaseForm,
                    wellFormed, tls, fepOnly, supersededBy);
        }
    }
}
