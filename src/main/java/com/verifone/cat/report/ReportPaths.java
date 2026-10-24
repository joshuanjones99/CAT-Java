package com.verifone.cat.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a run's evidence lands: the results root, a run's folder, and each case's folder.
 *
 * <p>The Python CAT's {@code petrotest.report.paths}. Its root is outside the repository
 * because run output is not repository content: a run's {@code events.ndjson} is almost
 * all log lines, and a repository-relative {@code artifacts/} once grew to hundreds of
 * megabytes. The default is {@code C:\CICD\Results\CAT}, and {@code CAT_RESULTS_DIR}
 * moves it.
 *
 * <p>Every resolver takes its environment as a {@link Map} rather than reading
 * {@link System#getenv()}, so tests can point a whole run at a {@code @TempDir} and never
 * touch the real results root. The caller passes {@code System.getenv()} in production.
 *
 * <p>Nothing here zips or strips a run folder (CLAUDE.md, "Run output is kept whole and
 * unzipped"). {@code run.ended} marks a finished run, and that is written elsewhere.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no rig run has written output with this port yet.
 */
public final class ReportPaths {

    /** The environment variable that overrides the results root. */
    public static final String RESULTS_DIR_ENV = "CAT_RESULTS_DIR";

    /** The environment variable that names the run; set once per process, shared by every writer. */
    public static final String RUN_ID_ENV = "PETROTEST_RUN_ID";

    /** A run's event stream, {@code <root>/<run-id>/events.ndjson}. */
    public static final String EVENTS_FILE = "events.ndjson";

    /** The documented default root. A path, not an address. */
    public static final Path DEFAULT_RESULTS_ROOT = Path.of("C:/CICD/Results/CAT");

    /**
     * The longest case folder name. Windows refuses a path over 260 characters; the
     * Python CAT's run 804b6c3e389b had a 249-character folder that failed the run.
     */
    public static final int CASE_FOLDER_MAX = 100;

    /**
     * A case's Jira key in its test file's name: {@code PETROCORE-61334-TestCase.feature},
     * or the older {@code test_petrocore_61334.py}.
     */
    private static final Pattern CASE_FILE =
            Pattern.compile("(?:PETROCORE-(\\d+)-TestCase|test_petrocore_(\\d+))\\.\\w+$");

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private ReportPaths() {}

    /**
     * {@code CAT_RESULTS_DIR} from {@code env}, else {@link #DEFAULT_RESULTS_ROOT}.
     *
     * <p>A blank value counts as unset, as it does in the Python CAT.
     */
    public static Path resultsRoot(Map<String, String> env) {
        String configured = env.getOrDefault(RESULTS_DIR_ENV, "").strip();
        return configured.isEmpty() ? DEFAULT_RESULTS_ROOT : Path.of(configured);
    }

    /** {@code <root>/<run-id>/}. */
    public static Path runFolder(Path root, String runId) {
        return root.resolve(runId);
    }

    /**
     * {@code PETROTEST_RUN_ID} from {@code env} if it is set; otherwise a UTC timestamp
     * (for example {@code 20261024T153000Z}), which is also stored in {@code env} so every
     * later caller in the process agrees on it.
     *
     * <p>Setting it before the first writer runs matters because the run's worker
     * processes inherit the environment, and two timestamps would split one run into two
     * folders.
     */
    public static String sessionRunId(Map<String, String> env, Instant now) {
        String existing = env.get(RUN_ID_ENV);
        if (existing != null) {
            return existing;
        }
        String created = RUN_ID_FORMAT.format(now);
        env.put(RUN_ID_ENV, created);
        return created;
    }

    /**
     * The case key for a test's node id, {@code PETROCORE-61334}, or {@code ""} for a test
     * that is not a case. Only the file part of the node id is read.
     */
    public static String caseKey(String nodeid) {
        String path = nodeid.split("::", 2)[0].replace('\\', '/');
        Matcher match = CASE_FILE.matcher(path);
        if (!match.find()) {
            return "";
        }
        String number = match.group(1) != null ? match.group(1) : match.group(2);
        return "PETROCORE-" + number;
    }

    /**
     * Keeps only what a Windows folder name keeps: letters, digits, {@code -}, {@code .} and
     * {@code _}. Everything else, {@code /}, {@code ::} and {@code []} included, becomes
     * {@code _}, and leading and trailing underscores go.
     */
    static String safe(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            boolean keep = Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '_';
            out.appendCodePoint(keep ? c : '_');
        });
        return stripEnds(out.toString(), "_");
    }

    /**
     * {@code name}, or its head with a hash of {@code nodeid} appended when it is over
     * {@link #CASE_FOLDER_MAX}, so two long names stay distinct.
     */
    static String bounded(String name, String nodeid) {
        if (name.length() <= CASE_FOLDER_MAX) {
            return name;
        }
        String head = stripTrailing(name.substring(0, CASE_FOLDER_MAX - 11), "_.-");
        return head + "-" + sha1Prefix(nodeid, 10);
    }

    private static String stripEnds(String text, String chars) {
        int start = 0;
        int end = text.length();
        while (start < end && chars.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        return stripTrailing(text.substring(start), chars);
    }

    private static String stripTrailing(String text, String chars) {
        int end = text.length();
        while (end > 0 && chars.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String sha1Prefix(String text, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is required of every Java platform; this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Gives each test node id its folder name, once per process, as the Python CAT's
     * {@code _FOLDERS} does. The first test to take a name keeps it; a second test that
     * would take the same name gets its test name added.
     *
     * <p>Not thread-safe by design: one instance per run's recorder, like the Python one.
     */
    public static final class CaseFolders {

        private final Map<String, String> byNodeid = new HashMap<>();

        /**
         * The folder name for {@code nodeid}: {@code PETROCORE-61334}, or with a
         * parameter {@code PETROCORE-61338-buypass}.
         */
        public String name(String nodeid) {
            String known = byNodeid.get(nodeid);
            if (known != null) {
                return known;
            }
            String key = caseKey(nodeid);
            String name;
            if (key.isEmpty()) {
                name = safeOrUnknown(nodeid);
            } else {
                String param = parameter(nodeid);
                name = param.isEmpty() ? key : key + "-" + safe(param);
                if (byNodeid.containsValue(name)) {
                    String[] parts = nodeid.split("::", 2);
                    name = key + "-" + safe(parts.length > 1 ? parts[1] : nodeid);
                }
            }
            name = bounded(name, nodeid);
            byNodeid.put(nodeid, name);
            return name;
        }

        private static String safeOrUnknown(String nodeid) {
            String safe = safe(nodeid);
            return safe.isEmpty() ? "unknown-test" : safe;
        }

        /** The text inside the last {@code [...]} of a parametrised node id, or "". */
        private static String parameter(String nodeid) {
            if (!nodeid.endsWith("]") || !nodeid.contains("[")) {
                return "";
            }
            String inside = nodeid.substring(nodeid.lastIndexOf('[') + 1);
            return stripTrailing(inside, "]");
        }
    }
}
