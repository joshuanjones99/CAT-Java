package com.verifone.cat.launch;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.runconfig.SavedChoices;

/**
 * The defaults {@code launch} takes from the Config tab's last saved choices, as the Python's
 * {@code tools/rig/run_plan._config} takes them from {@code ~/.cat/last-config.json}.
 *
 * <p>Two files are read, never written: CAT-Java's own ({@link SavedChoices#defaultPath}, i.e.
 * {@code ~/.cat/java/last-config.json} or {@code $CAT_SAVED_CONFIG}) and, as a read-only fallback, the
 * Python CAT's {@code ~/.cat/last-config.json} (HANDOFF §2: CAT-Java may read the Python's {@code ~/.cat},
 * never write it). They are merged key by key: a key the Java file holds wins, a key only the Python file
 * holds fills in. An explicit flag wins over both; the caller applies that.
 *
 * <p>Keys read (the Python CAT's): {@code config-upgrade}, {@code config-dist}, {@code config-exec},
 * {@code config-tp-branch}, {@code config-cc-onboard}, {@code config-cc-lab}, {@code config-cc-email},
 * {@code config-cc-service-id}, {@code config-cc-password}, {@code config-coverage}, {@code config-xray},
 * {@code config-cmd-branch}/{@code config-cmd-build}, {@code config-fnd-branch}/{@code config-fnd-build} and
 * {@code config-sim-branch}/{@code config-sim-build}.
 *
 * <p>The Commander Connect password is never printed: {@link #toString} names only the keys present. When
 * no saved file holds it, {@link #commanderConnect} reads {@value CommanderConnect#PASSWORD_ENV}, the variable
 * the Python's step 4 hands its child process (and the Config tab's "leave empty to read it from" rule).
 *
 * <p>Deviation: the Python's {@code run_plan} ignores the saved {@code config-xray} ({@code xray_report=not
 * args.no_xray}); this reads it (on when absent), as the wave-15 brief asks, so {@code --no-xray} or a saved
 * {@code false} turns reporting off.
 */
public final class LastConfig {

    public static final String UPGRADE = "config-upgrade";
    public static final String DIST = "config-dist";
    public static final String EXEC = "config-exec";
    public static final String TP_BRANCH = "config-tp-branch";
    public static final String CC_ONBOARD = "config-cc-onboard";
    public static final String CC_LAB = "config-cc-lab";
    public static final String CC_EMAIL = "config-cc-email";
    public static final String CC_SERVICE_ID = "config-cc-service-id";
    public static final String CC_PASSWORD = "config-cc-password";
    public static final String COVERAGE = "config-coverage";
    public static final String XRAY = "config-xray";
    public static final String CMD_BRANCH = "config-cmd-branch";
    public static final String CMD_BUILD = "config-cmd-build";
    public static final String FND_BRANCH = "config-fnd-branch";
    public static final String FND_BUILD = "config-fnd-build";
    public static final String SIM_BRANCH = "config-sim-branch";
    public static final String SIM_BUILD = "config-sim-build";

    /** The Python CAT's saved-choices file name under {@code ~/.cat}. */
    public static final String PYTHON_FILE = "last-config.json";

    private final Map<String, Object> saved;

    private LastConfig(Map<String, Object> saved) {
        this.saved = Collections.unmodifiableMap(new LinkedHashMap<>(saved));
    }

    /** No saved choices: every default is the Python {@code LaunchConfig}'s. */
    public static LastConfig none() {
        return new LastConfig(Map.of());
    }

    /** From explicit values, for tests and callers that already hold the map. */
    public static LastConfig of(Map<String, ?> saved) {
        return new LastConfig(new LinkedHashMap<>(saved));
    }

    /**
     * The Java file's keys over the Python file's. Either path may be null or absent; a file that will not
     * read as a JSON object is no choices, as {@link SavedChoices#read} has it.
     */
    public static LastConfig load(Path javaFile, Path pythonFile) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (pythonFile != null) {
            merged.putAll(SavedChoices.read(pythonFile));
        }
        if (javaFile != null) {
            merged.putAll(SavedChoices.read(javaFile));
        }
        return new LastConfig(merged);
    }

    /** The real files: {@link SavedChoices#defaultPath(Map, String)} and {@code <home>/.cat/last-config.json}. */
    public static LastConfig fromHome(Map<String, String> env, String userHome) {
        return load(SavedChoices.defaultPath(env, userHome), Path.of(userHome, ".cat", PYTHON_FILE));
    }

    /** The saved {@code config-upgrade}, or empty. */
    public String upgrade() {
        return text(UPGRADE);
    }

    /** The saved {@code config-dist}, or empty. */
    public String dist() {
        return text(DIST);
    }

    /** The saved {@code config-exec}, or empty. */
    public String exec() {
        return text(EXEC);
    }

    /** The saved {@code config-tp-branch}, or empty. */
    public String testPlanBranch() {
        return text(TP_BRANCH);
    }

    public boolean ccOnboard() {
        return flag(CC_ONBOARD, false);
    }

    public boolean coverage() {
        return flag(COVERAGE, false);
    }

    /** The saved Xray switch; on when nothing is saved, as the Config tab's default. */
    public boolean xray() {
        return flag(XRAY, true);
    }

    public String commanderBranch() {
        return text(CMD_BRANCH);
    }

    public String commanderBuild() {
        return text(CMD_BUILD);
    }

    public String foundationBranch() {
        return text(FND_BRANCH);
    }

    public String foundationBuild() {
        return text(FND_BUILD);
    }

    public String simBranch() {
        return text(SIM_BRANCH);
    }

    public String simBuild() {
        return text(SIM_BUILD);
    }

    /**
     * The Commander Connect settings: lab (default {@code Prod}), email, service id, and the password from the
     * saved file or, when it holds none, {@code env}'s {@value CommanderConnect#PASSWORD_ENV}.
     */
    public CommanderConnect commanderConnect(Map<String, String> env) {
        String password = text(CC_PASSWORD);
        if (password.isEmpty() && env != null) {
            password = env.getOrDefault(CommanderConnect.PASSWORD_ENV, "");
        }
        String lab = text(CC_LAB);
        return new CommanderConnect(lab.isEmpty() ? "Prod" : lab, text(CC_EMAIL), text(CC_SERVICE_ID), password);
    }

    /**
     * The Downloads section the way {@code run_plan._config} resolves it: a Commander branch given on the
     * command line ({@code flagBranch}) takes the flag's build and no foundation; with none given, the saved
     * Commander branch, build and foundation. The simulator build is the flag's, else the saved one, per field.
     * {@link LaunchCommand} sets {@link LaunchConfig}'s build fields from it.
     */
    public Downloads downloads(String flagBranch, String flagBuild, String flagSimBranch, String flagSimBuild) {
        String branch = blankToEmpty(flagBranch);
        String build = blankToEmpty(flagBuild);
        String fndBranch = "";
        String fndBuild = "";
        if (branch.isEmpty()) {
            branch = commanderBranch();
            build = commanderBuild();
            fndBranch = foundationBranch();
            fndBuild = foundationBuild();
        }
        String simBranch = blankToEmpty(flagSimBranch);
        String simBuild = blankToEmpty(flagSimBuild);
        return new Downloads(branch, build, fndBranch, fndBuild,
                simBranch.isEmpty() ? simBranch() : simBranch,
                simBuild.isEmpty() ? simBuild() : simBuild);
    }

    /** The Config tab's Downloads section as {@link #downloads} resolves it. Empty strings mean none. */
    public record Downloads(String commanderBranch, String commanderBuild, String foundationBranch,
            String foundationBuild, String simBranch, String simBuild) {
    }

    /** The value at {@code key} as text; empty when absent, null or not a string or number. */
    String text(String key) {
        Object value = saved.get(key);
        if (value instanceof String s) {
            return s.strip();
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        return "";
    }

    /** The value at {@code key} as a switch; {@code absent} when it is not a boolean. */
    boolean flag(String key, boolean absent) {
        Object value = saved.get(key);
        return value instanceof Boolean b ? b : absent;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    /** The keys present only: never a value, so never the Commander Connect password. */
    @Override
    public String toString() {
        return "LastConfig" + saved.keySet();
    }
}
