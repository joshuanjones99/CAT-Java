package com.verifone.cat.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.build.StoredArtifactoryCredentials;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.gitauth.GitAuth;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.ToolsRoot;

/**
 * The standard workstation checks, in the order the page shows them.
 *
 * <p>Ported from the Python CAT's {@code cat.setup.items}, checks and repairs. Where the Python checks something
 * only Python has (uv, the venv, pyjab, pre-commit hooks, Python 3.12), the Java check is the JDK, the built
 * {@code target\} and the Playwright Chromium that {@code --fake-pos} needs (each detect-only, {@code manual}).
 * The automatable repairs are the Python's: {@code corpus_root} defaults {@code PETROAPPS_ROOT},
 * {@code artifacts_writable} creates the results folder, {@code cashier_password} defaults the lab's till password,
 * {@code java_access_bridge} runs {@code jabswitch -enable}, and {@code ssh_key} points
 * {@code PETROTEST_COMMANDER_SSH_KEY} at a key JSch loads (a {@code .ppk} included: see {@link #sshKey()}). The two
 * sign-ins take their values on the Setup page ({@link SetupItem#fields()}) and are checked live.
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

    /** How long {@code jabswitch -enable} may take, as the Python's. */
    private static final Duration JABSWITCH_TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern JAVA_VERSION = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?");

    /** A URL that carries a password inline: {@code https://user:secret@host}. */
    private static final Pattern INLINE_PASSWORD =
            Pattern.compile("https?://[^/\\s:@]+:[^/\\s@]+@", Pattern.CASE_INSENSITIVE);

    private SetupItems() {}

    /** The standard checks, in registration order, with the real live sign-ins. Each call returns fresh items. */
    public static List<SetupItem> standard() {
        return standard(LiveSignIns.REAL);
    }

    /** The standard checks with {@code live} as the two sign-ins' {@code check_live()} (tests pass a fake). */
    public static List<SetupItem> standard(LiveSignIns live) {
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
        items.add(jiraApiToken().withLiveCheck(live::jira));
        items.add(verifoneLogin().withLiveCheck(live::verifone));
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
                "Sign in on this page (Verifone sign-in): once the Verifone login works, CAT downloads Java 17 "
                        + "(v1.2 into the tools root's Java17, v1.1 into Java17v1.1; C:\\CICD\\Tools by default) from "
                        + "Artifactory by itself. Or install a Java 17 JDK (Temurin 17.0.5 is what this lab uses) into "
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
        return new SetupItem(TOOLS_ROOT, "Tools folder", true, true, false,
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
                }).withRepair(SetupItems::defaultPetroappsRoot);
    }

    /** The Python's {@code CorpusRootSet.apply}: export {@code PETROAPPS_ROOT} to the default checkout, if it exists. */
    static Result defaultPetroappsRoot(SetupEnv env) {
        if (env.varExists("PETROAPPS_ROOT")) {
            return Result.unrepairable(CORPUS_ROOT, "PETROAPPS_ROOT='" + env.vars().get("PETROAPPS_ROOT")
                    + "' does not exist");
        }
        Path checkout = Path.of(Collect.DEFAULT_PETROAPPS_ROOT);
        if (!env.isDirectory(checkout)) {
            return Result.unrepairable(CORPUS_ROOT, "default petroapps checkout not found: " + checkout
                    + "; set PETROAPPS_ROOT to your checkout");
        }
        env.setEnvVar("PETROAPPS_ROOT", checkout.toString());
        return Result.passed(CORPUS_ROOT, "PETROAPPS_ROOT defaulted to " + checkout);
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
                }).withRepair(SetupItems::createResultsFolder);
    }

    /** The Python's {@code ArtifactsWritable.apply}: create the folder and prove it takes a file. */
    static Result createResultsFolder(SetupEnv env) throws IOException {
        Path root = ReportPaths.resultsRoot(env.vars());
        env.mkdir(root);
        Path probe = root.resolve(".cat_test");
        env.touch(probe);
        try {
            env.delete(probe);
        } catch (IOException e) {
            // The folder took the file; leaving the probe behind is harmless.
        }
        return Result.passed(ARTIFACTS_WRITABLE, "results folder created: " + root);
    }

    static SetupItem rigsDir() {
        return new SetupItem(RIGS_DIR, "Rig files folder", true, false, false,
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
        return new SetupItem(CAT_JAVA_BUILD, "CAT-Java built", true, true, false,
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
        return new SetupItem(PLAYWRIGHT_INSTALLED, "Playwright Chromium (for --fake-pos)", true, false, false,
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

    /**
     * The Commander SSH key. The Python's {@code SshKeyValid}, with one deviation: JSch (mwiede 0.2.20) reads an
     * unencrypted PuTTY {@code .ppk} itself (checked 2026-10-29 with a generated PPK v2 RSA key: {@code KeyPair.load}
     * and {@code addIdentity} both take it), so {@code rig/ppk.py}'s conversion is not ported. A {@code .ppk} JSch
     * loads passes once {@code PETROTEST_COMMANDER_SSH_KEY} names it; the repair sets the variable to it, as the
     * Python's repair points it at the converted {@code .pem}. Contents are never reported.
     */
    static SetupItem sshKey() {
        return new SetupItem(SSH_KEY, "Commander SSH key", false, false, false,
                "Set PETROTEST_COMMANDER_SSH_KEY to the Commander key (OpenSSH PEM, RSA PEM, or an unencrypted PuTTY "
                        + ".ppk, which JSch reads as it is). A passphrase-protected key must be converted by hand in "
                        + "PuTTYgen.",
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
                        String why = ppkProblem(key);
                        if (!why.isEmpty()) {
                            return Result.failed(SSH_KEY, "SSH key is PuTTY .ppk format and JSch cannot load it ("
                                    + why + "); convert it by hand in PuTTYgen");
                        }
                        if (configured == null) {
                            return Result.failed(SSH_KEY, "SSH key is PuTTY .ppk format, which JSch reads, but "
                                    + SSH_KEY_ENV + " is not set; setup repair sets it to " + key);
                        }
                        return Result.passed(SSH_KEY, "SSH key is PuTTY .ppk format; JSch reads it as it is");
                    }
                    return Result.failed(SSH_KEY, "SSH key format not recognized: "
                            + first.substring(0, Math.min(40, first.length())));
                }).withRepair(SetupItems::pointAtSshKey);
    }

    /** The repair: point {@code PETROTEST_COMMANDER_SSH_KEY} at a key JSch loads. Never converts or rewrites the key. */
    static Result pointAtSshKey(SetupEnv env) {
        String configured = env.var(SSH_KEY_ENV);
        Path key = configured != null ? Path.of(configured) : toolsRoot(env).resolve(SSH_KEY_FILE);
        if (!env.exists(key)) {
            return Result.unrepairable(SSH_KEY, "SSH key file not found: " + key);
        }
        String first = firstLine(env, key);
        if (first == null) {
            return Result.unrepairable(SSH_KEY, "SSH key file not readable: " + key);
        }
        boolean pem = first.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
                || first.startsWith("-----BEGIN RSA PRIVATE KEY-----");
        if (!pem && !isPpk(first)) {
            return Result.unrepairable(SSH_KEY, "SSH key format not recognized: "
                    + first.substring(0, Math.min(40, first.length())));
        }
        if (!pem) {
            String why = ppkProblem(key);
            if (!why.isEmpty()) {
                return Result.unrepairable(SSH_KEY, "cannot load " + key + " with JSch: " + why
                        + "; convert it by hand in PuTTYgen");
            }
        }
        env.setEnvVar(SSH_KEY_ENV, key.toString());
        return Result.passed(SSH_KEY, SSH_KEY_ENV + " set to " + key + (pem ? "" : " (JSch reads the .ppk as it is)"));
    }

    /** Why JSch cannot use the {@code .ppk} at {@code key}, or "" when it loads unencrypted. Reads the file only. */
    static String ppkProblem(Path key) {
        try {
            com.jcraft.jsch.KeyPair pair = com.jcraft.jsch.KeyPair.load(new com.jcraft.jsch.JSch(), key.toString());
            try {
                return pair.isEncrypted() ? "it is passphrase-protected" : "";
            } finally {
                pair.dispose();
            }
        } catch (com.jcraft.jsch.JSchException | RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /** Either header a {@code .ppk} file ships with. The contents are never reported, only the header kind. */
    static boolean isPpk(String firstLine) {
        return firstLine.startsWith("PuTTY-User-Key-File-")
                || firstLine.startsWith("---- BEGIN SSH2 ENCRYPTED PRIVATE KEY ----");
    }

    /**
     * The lab's standing till password, the Python CAT's {@code DEFAULT_CASHIER_PASSWORD}: a fixed fact about this
     * lab's rig recorded by the rig owner ({@code rigs/lab.yaml}: "ID 1 / password 123", 2026-07-29; the setup default
     * 2026-09-10), not a per-workstation secret. The repair exports it only when the variable is not set at all.
     */
    static final String DEFAULT_CASHIER_PASSWORD = "123";

    static SetupItem cashierPassword() {
        return new SetupItem(CASHIER_PASSWORD, "Cashier password", false, false, false,
                "Set PETROTEST_CASHIER_PASSWORD in your environment to the till password your rig file names, or "
                        + "let setup default it to the lab's standing till password. CAT never shows its value.",
                env -> env.var(CASHIER_PASSWORD_ENV) != null
                        ? Result.passed(CASHIER_PASSWORD, CASHIER_PASSWORD_ENV + " is set (value not shown)")
                        : Result.failed(CASHIER_PASSWORD, CASHIER_PASSWORD_ENV + " not set"))
                .withRepair(env -> {
                    if (env.var(CASHIER_PASSWORD_ENV) != null) {
                        return Result.passed(CASHIER_PASSWORD, CASHIER_PASSWORD_ENV + " is set");
                    }
                    env.setEnvVar(CASHIER_PASSWORD_ENV, DEFAULT_CASHIER_PASSWORD);
                    return Result.passed(CASHIER_PASSWORD,
                            CASHIER_PASSWORD_ENV + " defaulted to the lab's standing till password");
                });
    }

    static SetupItem javaAccessBridge() {
        return new SetupItem(JAVA_ACCESS_BRIDGE, "Java Access Bridge enabled", false, false, false,
                "Run jabswitch -enable from the Java 17 bin folder (<tools root>\\Java17\\...\\bin), for this Windows user.",
                env -> {
                    Path file = env.home().resolve(ACCESS_BRIDGE_FILE);
                    if (accessBridgeEnabled(env, file)) {
                        return Result.passed(JAVA_ACCESS_BRIDGE, "enabled (" + file + ")");
                    }
                    return Result.failed(JAVA_ACCESS_BRIDGE, "Java Access Bridge not enabled for this user "
                            + "(no assistive_technologies in " + file + "); run `jabswitch -enable`");
                }).withRepair(SetupItems::enableAccessBridge);
    }

    /** The Python's {@code AccessBridgeEnabled.apply}: the resolved JDK's own {@code jabswitch -enable}. Idempotent. */
    static Result enableAccessBridge(SetupEnv env) throws IOException {
        Path javaw = javaw(env);
        if (javaw == null) {
            return Result.failed(JAVA_ACCESS_BRIDGE, "no JDK with bin\\javaw.exe under the tools root");
        }
        Path jabswitch = javaw.resolveSibling("jabswitch.exe");
        if (!Files.isRegularFile(jabswitch)) {
            return Result.failed(JAVA_ACCESS_BRIDGE, jabswitch + " is not present in the resolved JDK");
        }
        SetupEnv.CommandResult run = env.run(List.of(jabswitch.toString(), "-enable"), JABSWITCH_TIMEOUT);
        if (run.exitCode() != 0) {
            return Result.failed(JAVA_ACCESS_BRIDGE, "`jabswitch -enable` failed: " + run.stderr().strip());
        }
        return Result.passed(JAVA_ACCESS_BRIDGE, "`jabswitch -enable` completed");
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
                "Sign in on this page (Verifone sign-in): once the Verifone login works, CAT downloads the "
                        + "simulators from Artifactory's latest master build into the tools root by itself. Or stage "
                        + "PATS's simulators under the tools root (CAT_TOOLS_ROOT) by hand, or point CAT_TOOLS_ROOT at "
                        + "where they are.",
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

    /** The Atlassian card's four inputs, in the Python's order and words. */
    static final List<ManualField> JIRA_FIELDS = List.of(
            new ManualField("email", "Atlassian account email", false),
            new ManualField("jira.token", "API token (id.atlassian.com/manage-profile/security/api-tokens)", true),
            new ManualField("xray.client.id", "Xray client ID (Xray > API Keys)", false),
            new ManualField("xray.client.secret", "Xray client secret", true));

    static SetupItem jiraApiToken() {
        return new SetupItem(JIRA_API_TOKEN, "Atlassian sign-in (Jira, Confluence, Xray)", true, false, true,
                "Enter the Atlassian email and API token and the Xray client ID and secret on this page "
                        + "(Save and sign in). Tokens: id.atlassian.com/manage-profile/security/api-tokens; "
                        + "the Xray pair: Xray > API Keys.",
                env -> {
                    Path file = env.logins().jiraFile();
                    if (!env.exists(file)) {
                        return Result.failed(JIRA_API_TOKEN, file + " not found");
                    }
                    List<String> missing = missingJiraKeys(env, file);
                    if (!missing.isEmpty()) {
                        return Result.failed(JIRA_API_TOKEN, file + " is missing " + String.join(", ", missing));
                    }
                    return Result.passed(JIRA_API_TOKEN, file + " names " + String.join(", ", JIRA_KEYS)
                            + " (values not shown)");
                }).withFields(JIRA_FIELDS, SetupItems::saveJira, SetupItems::savedJira);
    }

    static List<String> missingJiraKeys(SetupEnv env, Path file) throws IOException {
        Map<String, String> values = JiraFile.read(env.exists(file) ? env.read(file) : "");
        List<String> missing = new ArrayList<>();
        for (String key : JIRA_KEYS) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** The Python's {@code JiraApiToken.saved_values}: what the file in use holds for each key; empty with no file. */
    static Map<String, String> savedJira(SetupEnv env) throws IOException {
        Path file = env.logins().jiraFile();
        if (!env.exists(file)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        Path shared = env.logins().sharedJiraFile();
        if (!file.equals(shared) && env.exists(shared)) {
            values.putAll(JiraFile.read(env.read(shared)));
        }
        JiraFile.read(env.read(file)).forEach((key, value) -> {
            if (!value.isBlank()) {
                values.put(key, value);
            }
        });
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : JIRA_KEYS) {
            out.put(key, values.getOrDefault(key, "").strip());
        }
        return out;
    }

    /**
     * The Python's {@code JiraApiToken.apply_manual}: the four keys into CAT-Java's own file, merging. A blank value
     * keeps the one already saved (in CAT-Java's file, else the shared one), every other line of CAT-Java's file is
     * kept, and nothing is written when a key would end blank.
     */
    static Result saveJira(SetupEnv env, Map<String, String> values) throws IOException {
        Path own = env.logins().ownJiraFile();
        Path shared = env.logins().sharedJiraFile();
        String existing = env.exists(own) ? env.read(own) : "";
        Map<String, String> saved = new LinkedHashMap<>(JiraFile.read(env.exists(shared) ? env.read(shared) : ""));
        JiraFile.read(existing).forEach((key, value) -> {
            if (!value.isBlank()) {
                saved.put(key, value);
            }
        });
        Map<String, String> merged = new LinkedHashMap<>();
        List<String> blank = new ArrayList<>();
        for (String key : JIRA_KEYS) {
            String typed = values.getOrDefault(key, "").strip();
            String value = typed.isEmpty() ? saved.getOrDefault(key, "").strip() : typed;
            merged.put(key, value);
            if (value.isEmpty()) {
                blank.add(key);
            }
        }
        if (!blank.isEmpty()) {
            return Result.failed(JIRA_API_TOKEN, String.join(", ", blank) + " cannot be blank");
        }
        env.writeFile(own, JiraFile.merge(existing, JIRA_KEYS, merged));
        return Result.passed(JIRA_API_TOKEN, "wrote " + own);
    }

    /** The Verifone card's two inputs, in the Python's words. */
    static final List<ManualField> VERIFONE_FIELDS = List.of(
            new ManualField("username", "Verifone username, not your email (Bitbucket and Artifactory)", false),
            new ManualField("password", "Verifone password", true));

    /**
     * The Verifone login, stored for Artifactory and for Bitbucket: the Python's {@code VerifoneLogin}. Detecting it
     * reads both stores locally ({@link SavedLogins#artifactory()}, {@code git credential fill}) and never prompts or
     * touches the network. An inline password in petroapps' {@code .git/config} is caught from the file too, when git
     * gives no answer (a deviation: the Python asks git only).
     */
    static SetupItem verifoneLogin() {
        return new SetupItem(VERIFONE_LOGIN, "Verifone sign-in (Artifactory, Bitbucket)", true, false, true,
                "Enter the Verifone username (not your email) and password on this page (Save and sign in): CAT "
                        + "saves them for Artifactory and for petroapps' Bitbucket origin, removing any password "
                        + "inline in that origin.",
                env -> {
                    List<String> missing = new ArrayList<>();
                    if (env.logins().artifactory().isEmpty()) {
                        missing.add("Artifactory (none saved for this Windows account)");
                    }
                    Path petroapps = petroappsRoot(env);
                    Optional<GitAuth.Remote> remote = env.git().origin(petroapps);
                    boolean inline = remote.map(GitAuth.Remote::embedsPassword).orElse(false)
                            || remote.isEmpty() && inlinePassword(env, petroapps);
                    if (inline) {
                        missing.add("Bitbucket (petroapps' origin URL carries a password inline)");
                    } else if (remote.isPresent() && remote.get().isHttp() && !env.git().stored(petroapps)) {
                        missing.add("Bitbucket (no stored login for " + remote.get().host() + ")");
                    }
                    if (!missing.isEmpty()) {
                        return Result.failed(VERIFONE_LOGIN, "not signed in to " + String.join("; ", missing));
                    }
                    return Result.passed(VERIFONE_LOGIN, "Artifactory and Bitbucket logins stored");
                }).withFields(VERIFONE_FIELDS, SetupItems::saveVerifone, SetupItems::savedVerifone);
    }

    private static boolean inlinePassword(SetupEnv env, Path petroapps) throws IOException {
        Path config = petroapps.resolve(".git").resolve("config");
        return env.exists(config) && INLINE_PASSWORD.matcher(env.read(config)).find();
    }

    /** The Python's {@code VerifoneLogin.saved_values}: the Artifactory login saved for this account; empty when none. */
    static Map<String, String> savedVerifone(SetupEnv env) {
        return env.logins().artifactory()
                .map(login -> Map.of("username", login.user(), "password", login.password()))
                .orElse(Map.of());
    }

    /** The Python's {@code VerifoneLogin.apply_manual}: save the login for Artifactory and petroapps' Bitbucket origin. */
    static Result saveVerifone(SetupEnv env, Map<String, String> values) {
        String username = StoredArtifactoryCredentials.normaliseUsername(values.getOrDefault("username", ""));
        String password = values.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) {
            return Result.failed(VERIFONE_LOGIN, "username and password are both required");
        }
        try {
            env.logins().saveArtifactory(username, password);
        } catch (IOException | RuntimeException e) {
            return Result.error(VERIFONE_LOGIN, "saving the Artifactory login failed: " + e.getMessage());
        }
        List<String> stored = new ArrayList<>(List.of("Artifactory"));
        Path petroapps = petroappsRoot(env);
        try {
            if (env.git().origin(petroapps).isPresent()) {
                String why = env.git().store(petroapps, username, password);
                if (!why.isEmpty()) {
                    return Result.failed(VERIFONE_LOGIN, "Artifactory saved; Bitbucket not: " + why);
                }
                stored.add("Bitbucket");
            }
        } catch (GitAuth.GitError e) {
            return Result.failed(VERIFONE_LOGIN, "Artifactory saved; Bitbucket not: " + e.getMessage());
        }
        return Result.passed(VERIFONE_LOGIN, "saved for " + String.join(" and ", stored));
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
