package com.verifone.cat.setup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.ToolsRoot;

/**
 * The standard workstation checks, in the order the page shows them.
 *
 * <p>Ported from the Python CAT's {@code cat.setup.items} (the checks, not the repairs). Where the
 * Python checks something only Python has (uv, the venv, pyjab, pre-commit hooks, Python 3.12),
 * the Java check is the JDK, the built {@code target\} and the Playwright Chromium that
 * {@code --fake-pos} needs. Where the Python repaired a thing, the item now says what the operator
 * runs. Nothing here writes a file, sets a variable or starts an installer.
 *
 * <p>The paths the checks read come from {@link SetupEnv}: {@code CAT_TOOLS_ROOT},
 * {@code PETROAPPS_ROOT}, {@code CAT_RESULTS_DIR}, {@code PETROTEST_RIGS_DIR}, the home directory
 * and the repository root. A test passes a temporary directory for each, so no check reads
 * {@code C:\CICD} or the real home.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class SetupItems {

    /** The name {@link SetupItem#name()} gives each check; also the key of its result. */
    public static final String JAVA_RUNTIME = "java_runtime";
    public static final String TOOLS_ROOT = "tools_root";
    public static final String CORPUS_ROOT = "corpus_root";
    public static final String ARTIFACTS_WRITABLE = "artifacts_writable";
    public static final String RIGS_DIR = "rigs_dir";
    public static final String CAT_JAVA_BUILD = "cat_java_build";
    public static final String PLAYWRIGHT_INSTALLED = "playwright_installed";
    public static final String SSH_KEY = "ssh_key";
    public static final String CASHIER_PASSWORD = "cashier_password";
    public static final String JAVA_ACCESS_BRIDGE = "java_access_bridge";
    public static final String SIMULATORS_STAGED = "simulators_staged";
    public static final String JIRA_API_TOKEN = "jira_api_token";
    public static final String VERIFONE_LOGIN = "verifone_login";

    /** The JDK's folder under the tools root, as {@code cat.sims.config.javaw} names it. */
    public static final String JAVA_DIR = "Java17";

    /** The JDK major version CAT-Java is built and run on (the Maven enforcer refuses any other). */
    public static final int JAVA_MAJOR = 17;

    /** The PATS automation key, under the tools root (the Python's {@code DEFAULT_SSH_KEY_PATH}). */
    public static final String SSH_KEY_FILE = "development-no-pw.ppk";

    /** The Chromium build Playwright for Java 1.49.0 installs (HANDOFF.md §6, item 12). */
    public static final String CHROMIUM_DIR = "chromium-1148";

    /** The environment variable that moves the Playwright browsers folder. */
    public static final String PLAYWRIGHT_BROWSERS_ENV = "PLAYWRIGHT_BROWSERS_PATH";

    /** The Commander SSH key variable the Python CAT reads. */
    public static final String SSH_KEY_ENV = "PETROTEST_COMMANDER_SSH_KEY";

    /** The cashier password variable the Python CAT reads. Its value is never shown. */
    public static final String CASHIER_PASSWORD_ENV = "PETROTEST_CASHIER_PASSWORD";

    /** The Atlassian credentials file, in the home directory, as the Python CAT names it. */
    public static final String JIRA_CREDENTIALS_FILE = "jira_api_token.properties";

    /** The Jira keys the credentials file must name, with the Xray pair. */
    public static final List<String> JIRA_KEYS =
            List.of("email", "jira.token", "xray.client.id", "xray.client.secret");

    /** The Artifactory login variables (the Verifone login is one login for Artifactory and Bitbucket). */
    public static final String ARTIFACTORY_USER_ENV = "ARTIFACTORY_USER";
    public static final String ARTIFACTORY_PASSWORD_ENV = "ARTIFACTORY_PASSWORD";

    /** The per-user file the Java Access Bridge switch writes. */
    public static final String ACCESS_BRIDGE_FILE = ".accessibility.properties";

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern JAVA_VERSION = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?");

    /** A URL that carries a password inline: {@code https://user:secret@host}. */
    private static final Pattern INLINE_PASSWORD =
            Pattern.compile("https?://[^/\\s:@]+:[^/\\s@]+@", Pattern.CASE_INSENSITIVE);

    private SetupItems() {}

    /** The standard checks, in registration order. Each call returns fresh items. */
    public static List<SetupItem> standard() {
        List<SetupItem> items = new ArrayList<>();
        items.add(javaRuntime());
        items.add(toolsRoot());
        items.add(corpusRoot());
        items.add(artifactsWritable());
        items.add(rigsDir());
        items.add(catJavaBuild());
        items.add(playwrightInstalled());
        items.add(sshKey());
        items.add(cashierPassword());
        items.add(javaAccessBridge());
        items.add(simulatorsStaged());
        items.add(jiraApiToken());
        items.add(verifoneLogin());
        return items;
    }

    // ---- paths the checks read ------------------------------------------------------------

    /** {@code CAT_TOOLS_ROOT}, else {@code C:\CICD\Tools}. */
    public static Path toolsRoot(SetupEnv env) {
        return ToolsRoot.resolve(env.var(ToolsRoot.ENV), null);
    }

    /** {@code PETROAPPS_ROOT}, else the default petroapps checkout. */
    public static Path petroappsRoot(SetupEnv env) {
        String configured = env.var("PETROAPPS_ROOT");
        return Path.of(configured == null ? Collect.DEFAULT_PETROAPPS_ROOT : configured);
    }

    /** The JDK's {@code javaw.exe}: {@code Java17\bin} or the highest {@code Java17\jdk*\bin}, or null. */
    public static Path javaw(SetupEnv env) throws IOException {
        Path set = toolsRoot(env).resolve(JAVA_DIR);
        Path direct = set.resolve("bin").resolve("javaw.exe");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        if (!Files.isDirectory(set)) {
            return null;
        }
        Path best = null;
        try (var children = Files.list(set)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                Path exe = child.resolve("bin").resolve("javaw.exe");
                if (name.startsWith("jdk") && Files.isRegularFile(exe)) {
                    best = exe;
                }
            }
        }
        return best;
    }

    /** The major version in {@code java -version} output: 17 from {@code "17.0.5"}, 8 from {@code "1.8.0_181"}. */
    public static Integer javaMajor(String versionOutput) {
        Matcher match = JAVA_VERSION.matcher(versionOutput);
        if (!match.find()) {
            return null;
        }
        int major = Integer.parseInt(match.group(1));
        if (major == 1 && match.group(2) != null) {
            major = Integer.parseInt(match.group(2));
        }
        return major;
    }

    // ---- the checks -----------------------------------------------------------------------

    static SetupItem javaRuntime() {
        return new SetupItem(JAVA_RUNTIME, "Java 17", true, true, false,
                "Install a Java 17 JDK (Temurin 17.0.5 is what this lab uses) into "
                        + "<tools root>\\Java17\\jdk-17.0.5+8, or point CAT_TOOLS_ROOT at the tools root that holds it.",
                env -> {
                    Path javaw = javaw(env);
                    if (javaw == null) {
                        return Result.failed(JAVA_RUNTIME, "no JDK with bin\\javaw.exe under "
                                + toolsRoot(env).resolve(JAVA_DIR));
                    }
                    Path java = javaw.resolveSibling("java.exe");
                    SetupEnv.CommandResult run = env.run(List.of(java.toString(), "-version"), PROBE_TIMEOUT);
                    if (run.exitCode() != 0) {
                        return Result.failed(JAVA_RUNTIME, "`" + java + " -version` failed: " + run.stderr().strip());
                    }
                    Integer major = javaMajor(run.stdout() + run.stderr());
                    if (major == null) {
                        return Result.failed(JAVA_RUNTIME, "cannot read a version from `" + java + " -version`");
                    }
                    if (major != JAVA_MAJOR) {
                        return Result.failed(JAVA_RUNTIME, "Java " + major + " at " + java
                                + " is not Java " + JAVA_MAJOR + " (CAT-Java is built and run on it)");
                    }
                    return Result.passed(JAVA_RUNTIME, "Java " + major + ": " + java);
                });
    }

    static SetupItem toolsRoot() {
        return new SetupItem(TOOLS_ROOT, "Tools folder", false, true, false,
                "Stage the tools (JDK, simulators, pos-shim dependencies) in C:\\CICD\\Tools, or set "
                        + "CAT_TOOLS_ROOT to the folder that holds them.",
                env -> {
                    Path root = toolsRoot(env);
                    return env.isDirectory(root)
                            ? Result.passed(TOOLS_ROOT, "tools root: " + root)
                            : Result.failed(TOOLS_ROOT, "tools root not found: " + root);
                });
    }

    static SetupItem corpusRoot() {
        return new SetupItem(CORPUS_ROOT, "petroapps checkout", false, false, false,
                "Clone petroapps to C:\\gitrepos\\petroapps, or set PETROAPPS_ROOT to your checkout.",
                env -> {
                    Path root = petroappsRoot(env);
                    return env.isDirectory(root)
                            ? Result.passed(CORPUS_ROOT, "PETROAPPS_ROOT resolves to " + root)
                            : Result.failed(CORPUS_ROOT, "petroapps checkout not found: " + root);
                });
    }

    static SetupItem artifactsWritable() {
        return new SetupItem(ARTIFACTS_WRITABLE, "Results folder writable", false, true, false,
                "Make the results folder writable, or set CAT_RESULTS_DIR to a folder you can write.",
                env -> {
                    Path root = ReportPaths.resultsRoot(env.vars());
                    if (!env.isWritable(root)) {
                        return Result.failed(ARTIFACTS_WRITABLE, "results folder not writable: " + root);
                    }
                    return env.exists(root)
                            ? Result.passed(ARTIFACTS_WRITABLE, "results folder writable: " + root)
                            : Result.passed(ARTIFACTS_WRITABLE, "results folder will be created: " + root);
                });
    }

    static SetupItem rigsDir() {
        return new SetupItem(RIGS_DIR, "Rig files folder", false, false, false,
                "Set PETROTEST_RIGS_DIR to the folder that holds your rig files (lab.yaml and the rest).",
                env -> {
                    Path dir = RigLoader.rigsDir(env.vars());
                    return env.isDirectory(dir)
                            ? Result.passed(RIGS_DIR, "rig files: " + dir)
                            : Result.failed(RIGS_DIR, "rigs folder not found: " + dir
                                    + " (set PETROTEST_RIGS_DIR)");
                });
    }

    static SetupItem catJavaBuild() {
        return new SetupItem(CAT_JAVA_BUILD, "CAT-Java built", false, true, false,
                "Run build.cmd from PowerShell, with the full path to the worktree's build.cmd.",
                env -> {
                    Path jar = env.repoRoot().resolve("target").resolve("cat-java.jar");
                    Path lib = env.repoRoot().resolve("target").resolve("lib");
                    if (!env.exists(jar) || !env.isDirectory(lib)) {
                        return Result.failed(CAT_JAVA_BUILD, "not built: target\\cat-java.jar or target\\lib "
                                + "is missing under " + env.repoRoot());
                    }
                    return Result.passed(CAT_JAVA_BUILD, "target\\cat-java.jar and target\\lib present");
                });
    }

    static SetupItem playwrightInstalled() {
        return new SetupItem(PLAYWRIGHT_INSTALLED, "Playwright Chromium (for --fake-pos)", false, false, false,
                "Run: java -cp \"target\\lib\\*\" com.microsoft.playwright.CLI install chromium",
                env -> {
                    Path browsers = playwrightBrowsers(env);
                    Path chromium = browsers.resolve(CHROMIUM_DIR);
                    return env.isDirectory(chromium)
                            ? Result.passed(PLAYWRIGHT_INSTALLED, "Chromium present: " + chromium)
                            : Result.failed(PLAYWRIGHT_INSTALLED, "Chromium not in " + browsers
                                    + " (--fake-pos needs it; the real rig does not)");
                });
    }

    static Path playwrightBrowsers(SetupEnv env) {
        String configured = env.var(PLAYWRIGHT_BROWSERS_ENV);
        if (configured != null) {
            return Path.of(configured);
        }
        String localAppData = env.var("LOCALAPPDATA");
        Path local = localAppData == null ? env.home().resolve("AppData").resolve("Local") : Path.of(localAppData);
        return local.resolve("ms-playwright");
    }

    static SetupItem sshKey() {
        return new SetupItem(SSH_KEY, "Commander SSH key", true, false, false,
                "Convert the PuTTY .ppk key to OpenSSH PEM (tools\\rig\\ppk_to_pem.py in the Python CAT), then "
                        + "set PETROTEST_COMMANDER_SSH_KEY to the .pem file.",
                env -> {
                    String configured = env.var(SSH_KEY_ENV);
                    Path key = configured != null ? Path.of(configured)
                            : toolsRoot(env).resolve(SSH_KEY_FILE);
                    if (!env.exists(key)) {
                        return Result.failed(SSH_KEY, "SSH key file not found: " + key);
                    }
                    String first = firstLine(env, key);
                    if (first == null) {
                        return Result.failed(SSH_KEY, "SSH key file not readable: " + key);
                    }
                    if (first.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) {
                        return Result.passed(SSH_KEY, "SSH key is OpenSSH PEM format");
                    }
                    if (first.startsWith("-----BEGIN RSA PRIVATE KEY-----")) {
                        return Result.passed(SSH_KEY, "SSH key is RSA PEM format");
                    }
                    if (isPpk(first)) {
                        return Result.failed(SSH_KEY, "SSH key is PuTTY .ppk format; it must be converted to PEM");
                    }
                    return Result.failed(SSH_KEY, "SSH key format not recognized: " + first.substring(0, Math.min(40, first.length())));
                });
    }

    /** Either header a {@code .ppk} file ships with. The contents are never reported, only the header kind. */
    static boolean isPpk(String firstLine) {
        return firstLine.startsWith("PuTTY-User-Key-File-")
                || firstLine.startsWith("---- BEGIN SSH2 ENCRYPTED PRIVATE KEY ----");
    }

    static SetupItem cashierPassword() {
        return new SetupItem(CASHIER_PASSWORD, "Cashier password", true, false, false,
                "Set PETROTEST_CASHIER_PASSWORD in your environment to the till password your rig file names. "
                        + "CAT never shows or writes it.",
                env -> env.var(CASHIER_PASSWORD_ENV) != null
                        ? Result.passed(CASHIER_PASSWORD, CASHIER_PASSWORD_ENV + " is set (value not shown)")
                        : Result.failed(CASHIER_PASSWORD, CASHIER_PASSWORD_ENV + " not set"));
    }

    static SetupItem javaAccessBridge() {
        return new SetupItem(JAVA_ACCESS_BRIDGE, "Java Access Bridge enabled", true, false, false,
                "Run jabswitch -enable from the Java 17 bin folder (<tools root>\\Java17\\...\\bin), for this Windows user.",
                env -> {
                    Path file = env.home().resolve(ACCESS_BRIDGE_FILE);
                    if (accessBridgeEnabled(env, file)) {
                        return Result.passed(JAVA_ACCESS_BRIDGE, "enabled (" + file + ")");
                    }
                    return Result.failed(JAVA_ACCESS_BRIDGE, "Java Access Bridge not enabled for this user "
                            + "(no assistive_technologies in " + file + ")");
                });
    }

    /** True when the per-user file names an {@code assistive_technologies} property: the file is the switch. */
    static boolean accessBridgeEnabled(SetupEnv env, Path file) throws IOException {
        if (!env.exists(file)) {
            return false;
        }
        for (String line : env.read(file).split("\\R")) {
            String stripped = line.strip();
            if (!stripped.startsWith("#") && stripped.startsWith("assistive_technologies")) {
                return true;
            }
        }
        return false;
    }

    static SetupItem simulatorsStaged() {
        return new SetupItem(SIMULATORS_STAGED, "Simulators staged", true, false, false,
                "Stage PATS's simulators under the tools root (CAT_TOOLS_ROOT), or point CAT_TOOLS_ROOT at where "
                        + "they are. CAT does not install them.",
                env -> {
                    Path root = toolsRoot(env);
                    Map<String, String> missing = new LinkedHashMap<>();
                    Set<String> seen = new HashSet<>();
                    List<SimEntry> rows = Registry.all();
                    for (SimEntry row : rows) {
                        String folder = row.cwd();
                        if (folder == null || folder.isBlank() || !seen.add(folder)) {
                            continue;
                        }
                        Path dir = root.resolve(folder);
                        if (!env.isDirectory(dir)) {
                            missing.put(row.name(), dir.toString());
                        }
                    }
                    if (!missing.isEmpty()) {
                        StringBuilder listed = new StringBuilder();
                        missing.forEach((name, path) -> {
                            if (!listed.isEmpty()) {
                                listed.append("; ");
                            }
                            listed.append(name).append(" (").append(path).append(')');
                        });
                        return Result.failed(SIMULATORS_STAGED, missing.size() + " not staged: " + listed);
                    }
                    return Result.passed(SIMULATORS_STAGED, "all " + rows.size() + " simulator rows staged under " + root);
                });
    }

    static SetupItem jiraApiToken() {
        return new SetupItem(JIRA_API_TOKEN, "Atlassian sign-in (Jira, Confluence, Xray)", true, false, true,
                "Create the file named in the detail, with email=, jira.token=, xray.client.id= and "
                        + "xray.client.secret=. Tokens: id.atlassian.com/manage-profile/security/api-tokens; "
                        + "the Xray pair: Xray > API Keys.",
                env -> {
                    Path file = env.home().resolve(JIRA_CREDENTIALS_FILE);
                    if (!env.exists(file)) {
                        return Result.failed(JIRA_API_TOKEN, file + " not found");
                    }
                    Properties values = new Properties();
                    try (BufferedReader reader = new BufferedReader(new StringReader(env.read(file)))) {
                        values.load(reader);
                    }
                    List<String> missing = new ArrayList<>();
                    for (String key : JIRA_KEYS) {
                        String value = values.getProperty(key);
                        if (value == null || value.isBlank()) {
                            missing.add(key);
                        }
                    }
                    if (!missing.isEmpty()) {
                        return Result.failed(JIRA_API_TOKEN, file + " is missing " + String.join(", ", missing));
                    }
                    return Result.passed(JIRA_API_TOKEN, file + " names " + String.join(", ", JIRA_KEYS)
                            + " (values not shown)");
                });
    }

    static SetupItem verifoneLogin() {
        return new SetupItem(VERIFONE_LOGIN, "Verifone sign-in (Artifactory, Bitbucket)", true, false, true,
                "Set ARTIFACTORY_USER and ARTIFACTORY_PASSWORD in your environment (the Verifone username, not "
                        + "your email). If petroapps' origin URL carries a password, remove it: run "
                        + "git -C <petroapps> remote set-url origin <url without user:password@>.",
                env -> {
                    List<String> problems = new ArrayList<>();
                    if (env.var(ARTIFACTORY_USER_ENV) == null || env.var(ARTIFACTORY_PASSWORD_ENV) == null) {
                        problems.add("Artifactory login not set (" + ARTIFACTORY_USER_ENV + " and "
                                + ARTIFACTORY_PASSWORD_ENV + ")");
                    }
                    Path config = petroappsRoot(env).resolve(".git").resolve("config");
                    if (env.exists(config) && INLINE_PASSWORD.matcher(env.read(config)).find()) {
                        problems.add("petroapps' origin URL carries a password inline");
                    }
                    if (!problems.isEmpty()) {
                        return Result.failed(VERIFONE_LOGIN, "not signed in: " + String.join("; ", problems));
                    }
                    return Result.passed(VERIFONE_LOGIN, "Artifactory login set; no password inline in petroapps' origin "
                            + "(values not shown)");
                });
    }

    private static String firstLine(SetupEnv env, Path file) {
        try {
            String text = env.read(file);
            int at = text.indexOf('\n');
            return (at < 0 ? text : text.substring(0, at)).strip();
        } catch (IOException e) {
            return null;
        }
    }
}
