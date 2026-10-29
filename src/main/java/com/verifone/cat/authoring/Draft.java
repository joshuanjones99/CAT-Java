package com.verifone.cat.authoring;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.authoring.Check.Analysis;
import com.verifone.cat.authoring.Check.Diagnostic;
import com.verifone.cat.validate.PyRepr;

/**
 * Draft authored cases from an ingested ticket with Claude, at authoring time only. Port of {@code cat.authoring.draft}
 * (DESIGN.md 5.6).
 *
 * <p>Draft sends Claude the session's spec, the step catalogue ({@link Catalogue#renderJson}), the shared rules
 * ({@code drafting.md}, the Python's own file) and the cases already in the session, and asks for cases written only in
 * catalogue phrases. CAT then assembles each case's {@code .feature}, runs {@link Check} over it, and, for up to
 * {@link #REPAIR_ROUNDS} more rounds, hands every error back with the case it belongs to. What comes back is a set of
 * drafts a person reads, edits, replays and approves; nothing a model wrote reaches a rig without that replay.
 *
 * <p><b>How Claude is reached.</b> Through the operator's own Claude Code login, as {@code claude -p} with
 * {@code --tools ""} (no tool at all), {@code --json-schema} ({@link #ANSWER_SCHEMA}, validated by Claude Code), and
 * {@code --no-session-persistence}, in a fresh empty working directory outside any repository. <b>Never at run time.</b>
 * Every request, answer and cost is kept under the session's {@code drafts/}.
 *
 * <p>Deviations: {@code $CAT_CLAUDE} naming a {@code .jar} runs it with this JVM (the Python runs a {@code .py} with its
 * own interpreter). On Windows a {@code .cmd}/{@code .bat} (npm's {@code claude.cmd}) is run through {@code cmd.exe /d /c},
 * as {@code CreateProcess} does for the Python, and each argument is escaped as the Python's {@code list2cmdline} escapes
 * it (an empty argument is {@code ""}, an embedded quote {@code \"}), because Java refuses an embedded quote in an argument
 * to a batch file. The empty working directory is made under {@code ~/.cat/java/tmp} (CAT-Java writes only under
 * {@code ~/.cat/java}), not the system temp folder.
 *
 * <p>UNVERIFIED: the drafting loop has run against a fake {@code claude} process in the unit tests; the real CLI has not
 * been run from Java.
 */
public final class Draft {

    /** After the first answer, this many more rounds of "here is what the checker said". */
    public static final int REPAIR_ROUNDS = 2;

    /** A draft request that runs longer than this is stopped. */
    public static final Duration TIMEOUT = Duration.ofSeconds(600);

    /** What Claude must answer, validated by Claude Code before CAT sees it. */
    public static final Map<String, Object> ANSWER_SCHEMA = schema();

    private static final List<String> KEYWORDS = List.of("Given ", "When ", "Then ", "And ", "But ");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RULES = "/com/verifone/cat/authoring/drafting.md";

    private Draft() {}

    /** Claude could not be asked, or its answer could not be read. Nothing was added. */
    public static final class DraftException extends RuntimeException {
        public DraftException(String message) {
            super(message);
        }

        public DraftException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One answer: what Claude said, what it cost, and the models it used. */
    public record ClaudeAnswer(Map<String, Object> answer, double costUsd, String model) {}

    /** One drafted case: its title, its {@code .feature} text, and what the checker said. */
    public record Drafted(String title, String text, Analysis analysis, String covers, List<String> assumptions) {}

    /** The draft's outcome. */
    public static final class DraftResult {
        public List<Drafted> cases = new ArrayList<>();
        public List<Map<String, Object>> notDrafted = new ArrayList<>();
        public String notes = "";
        public int rounds;
        public double costUsd;
        public String model = "";
        public Path transcript;

        /** The cases the checker passed. */
        public List<Drafted> clean() {
            return cases.stream().filter(d -> d.analysis().ok()).toList();
        }
    }

    /** Asks Claude one prompt. Production is {@link #runClaude(String, String, Double, Duration)}. */
    @FunctionalInterface
    public interface Asker {
        ClaudeAnswer ask(String prompt);
    }

    // ------------------------------------------------------------------ the schema and the rules

    private static Map<String, Object> schema() {
        Map<String, Object> strings = obj("type", "array", "items", obj("type", "string"));
        Map<String, Object> examples = obj(
                "type", "object",
                "properties", obj(
                        "header", obj("type", "array", "items", obj("type", "string")),
                        "rows", obj("type", "array", "items", obj("type", "array", "items", obj("type", "string")))),
                "required", List.of("header", "rows"));
        Map<String, Object> caseItem = obj(
                "type", "object",
                "properties", obj(
                        "title", obj("type", "string"),
                        "covers", obj("type", "string"),
                        "steps", strings,
                        "examples", examples,
                        "assumptions", obj("type", "array", "items", obj("type", "string"))),
                "required", List.of("title", "steps"));
        Map<String, Object> notDrafted = obj(
                "type", "object",
                "properties", obj(
                        "title", obj("type", "string"),
                        "missing_phrases", obj("type", "array", "items", obj("type", "string")),
                        "why", obj("type", "string")),
                "required", List.of("title", "missing_phrases"));
        return java.util.Collections.unmodifiableMap(obj(
                "type", "object",
                "properties", obj(
                        "cases", obj("type", "array", "items", caseItem),
                        "not_drafted", obj("type", "array", "items", notDrafted),
                        "notes", obj("type", "string")),
                "required", List.of("cases", "not_drafted")));
    }

    private static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** The shared drafting rules: the Python's {@code petrotest/authoring/drafting.md}, shipped as a resource. */
    public static String rules() {
        try (InputStream in = Draft.class.getResourceAsStream(RULES)) {
            if (in == null) {
                throw new IllegalStateException("no drafting rules at " + RULES);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ running claude

    /**
     * How to start Claude Code: {@code $CAT_CLAUDE}, else {@code claude} on {@code PATH}. A {@code .jar} runs with this
     * JVM.
     *
     * @throws DraftException when neither is there
     */
    public static List<String> claudeCommand(Map<String, String> env) {
        String named = env.get("CAT_CLAUDE");
        String found = named != null && !named.isEmpty() ? named : which("claude", env);
        if (found == null || found.isEmpty()) {
            throw new DraftException("Claude Code is not installed on this machine (no `claude` on PATH): install it "
                    + "and sign in with your Claude Enterprise account, or set CAT_CLAUDE");
        }
        if (found.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return List.of(javaBin(), "-jar", found);
        }
        return List.of(found);
    }

    /** {@code shutil.which}: the first {@code PATH} entry holding {@code name} (with a {@code PATHEXT} on Windows). */
    static String which(String name, Map<String, String> env) {
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        boolean windows = isWindows();
        List<String> extensions = new ArrayList<>();
        if (windows) {
            String pathext = env.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
            for (String ext : pathext.split(";")) {
                if (!ext.isBlank()) {
                    extensions.add(ext.strip());
                }
            }
        } else {
            extensions.add("");
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : extensions) {
                Path candidate;
                try {
                    candidate = Path.of(dir.strip(), name + ext);
                } catch (RuntimeException e) {
                    continue;
                }
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /** The {@code claude -p} command line for {@code command}. */
    public static List<String> claudeArgv(List<String> command, String model, Double budgetUsd) {
        List<String> argv = new ArrayList<>(command);
        argv.addAll(List.of("-p", "--output-format", "json", "--tools", "", "--no-session-persistence",
                "--json-schema", PyJson.compact(ANSWER_SCHEMA)));
        if (model != null && !model.isEmpty()) {
            argv.addAll(List.of("--model", model));
        }
        if (budgetUsd != null && budgetUsd != 0) {
            argv.addAll(List.of("--max-budget-usd", g(budgetUsd)));
        }
        return argv;
    }

    /** One {@code claude -p} request with the process environment: {@code $CAT_CLAUDE} or {@code claude} on PATH. */
    public static ClaudeAnswer runClaude(String prompt, String model, Double budgetUsd, Duration timeout) {
        Map<String, String> env = System.getenv();
        Path tmp = Path.of(System.getProperty("user.home"), ".cat", "java", "tmp");
        return runClaude(claudeCommand(env), prompt, model, budgetUsd, timeout, tmp);
    }

    /**
     * One {@code claude -p} request, the prompt on stdin, in an empty directory of its own under {@code tmpRoot}.
     *
     * @throws DraftException when it cannot start, does not answer in time, or answers with an error or no JSON
     */
    public static ClaudeAnswer runClaude(List<String> command, String prompt, String model, Double budgetUsd,
            Duration timeout, Path tmpRoot) {
        List<String> argv = processArgv(claudeArgv(command, model, budgetUsd));
        Path cwd;
        try {
            Files.createDirectories(tmpRoot);
            cwd = Files.createTempDirectory(tmpRoot, "cat-draft-");
        } catch (IOException e) {
            throw new DraftException("could not make a working directory for Claude Code: " + e.getMessage(), e);
        }
        byte[] stdout;
        byte[] stderr;
        int code;
        try {
            Process process;
            try {
                process = new ProcessBuilder(argv).directory(cwd.toFile()).start();
            } catch (IOException e) {
                throw new DraftException("could not start Claude Code: " + e.getMessage(), e);
            }
            CompletableFuture<byte[]> out = drain(process.getInputStream());
            CompletableFuture<byte[]> err = drain(process.getErrorStream());
            try (OutputStream in = process.getOutputStream()) {
                in.write(prompt.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // it stopped reading: its output says why
            }
            boolean done;
            try {
                done = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new DraftException("interrupted while Claude was drafting", e);
            }
            if (!done) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new DraftException("Claude did not answer within " + g(timeout.toMillis() / 1000.0) + "s");
            }
            code = process.exitValue();
            stdout = await(out);
            stderr = await(err);
        } finally {
            deleteTree(cwd);
        }
        return parseEnvelope(new String(stdout, StandardCharsets.UTF_8), new String(stderr, StandardCharsets.UTF_8),
                code);
    }

    /** Claude Code's JSON envelope as an answer. */
    @SuppressWarnings("unchecked")
    static ClaudeAnswer parseEnvelope(String stdout, String stderr, int code) {
        Map<String, Object> envelope;
        try {
            Object parsed = JSON.readValue(stdout, Object.class);
            if (!(parsed instanceof Map<?, ?>)) {
                throw new DraftException("Claude Code exited " + code + ": " + lastLine(stderr, stdout));
            }
            envelope = (Map<String, Object>) parsed;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new DraftException("Claude Code exited " + code + ": " + lastLine(stderr, stdout), e);
        }
        Object subtype = envelope.get("subtype");
        if (truthy(envelope.get("is_error")) || !(subtype == null || "success".equals(subtype))) {
            Object result = envelope.get("result");
            String reason = truthy(result) ? PyRepr.str(result) : truthy(subtype) ? PyRepr.str(subtype) : "an error";
            throw new DraftException("Claude Code reported " + head(reason, 300));
        }
        Object answer = envelope.get("structured_output");
        if (!(answer instanceof Map<?, ?>)) {
            Object result = envelope.get("result");
            try {
                answer = JSON.readValue(unfence(result == null ? "" : PyRepr.str(result)), Object.class);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new DraftException("Claude's answer was not the JSON the schema asks for", e);
            }
            if (!(answer instanceof Map<?, ?>)) {
                throw new DraftException("Claude's answer was not the JSON the schema asks for");
            }
        }
        List<String> models = new ArrayList<>();
        if (envelope.get("modelUsage") instanceof Map<?, ?> usage) {
            usage.keySet().forEach(k -> models.add(String.valueOf(k)));
        }
        Object cost = envelope.get("total_cost_usd");
        double usd = cost instanceof Number n ? n.doubleValue() : 0.0;
        return new ClaudeAnswer((Map<String, Object>) answer, usd, String.join(", ", models));
    }

    private static String lastLine(String stderr, String stdout) {
        String text = (stderr == null || stderr.isEmpty() ? stdout : stderr).strip();
        List<String> lines = text.lines().toList();
        return lines.isEmpty() || text.isEmpty() ? "no output" : head(lines.get(lines.size() - 1), 300);
    }

    static String unfence(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            text = newline >= 0 ? text.substring(newline + 1) : "";
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        return text;
    }

    /** The argv the process is started with: the batch-file and quote handling described on the class. */
    static List<String> processArgv(List<String> argv) {
        if (!isWindows()) {
            return argv;
        }
        List<String> out = new ArrayList<>();
        String first = argv.get(0).toLowerCase(Locale.ROOT);
        if (first.endsWith(".cmd") || first.endsWith(".bat")) {
            out.addAll(List.of("cmd.exe", "/d", "/c"));
        }
        for (String arg : argv) {
            out.add(windowsArg(arg));
        }
        return out;
    }

    /** {@code list2cmdline}'s escaping of one argument, leaving Java to add quotes around one with a space. */
    static String windowsArg(String arg) {
        if (arg.isEmpty()) {
            return "\"\"";
        }
        if (arg.indexOf('"') < 0) {
            return arg;
        }
        StringBuilder out = new StringBuilder();
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                out.append("\\".repeat(backslashes * 2)).append("\\\"");
                backslashes = 0;
            } else {
                out.append("\\".repeat(backslashes)).append(c);
                backslashes = 0;
            }
        }
        out.append("\\".repeat(backslashes));
        return out.toString();
    }

    // ------------------------------------------------------------------ assembling and prompting

    /** One drafted case as a {@code .feature}: CAT's tag and Feature line, the model's steps. */
    public static String assemble(Map<String, Object> drafted, String feature, String specKey, String key,
            LocalDate today) {
        String rawTitle = PyRepr.str(drafted.getOrDefault("title", ""));
        String title = words(rawTitle.replace(":", " -"));
        if (title.isEmpty()) {
            title = "Drafted case";
        }
        Map<String, Object> examples = asMap(drafted.get("examples"));
        List<String> header = strings(examples.get("header"));
        List<List<String>> rows = new ArrayList<>();
        if (examples.get("rows") instanceof List<?> list) {
            for (Object row : list) {
                rows.add(strings(row));
            }
        }
        boolean outline = !header.isEmpty() && !rows.isEmpty();
        List<String> lines = new ArrayList<>(List.of("@" + key, "Feature: " + feature, ""));
        String source = specKey != null && !specKey.isEmpty() ? "from " + specKey + " " : "";
        lines.add("  # Drafted by Claude " + source + "on " + today + ". Review every step before replaying.");
        if (truthy(drafted.get("covers"))) {
            lines.add("  # Covers: " + words(PyRepr.str(drafted.get("covers"))));
        }
        for (String assumption : strings(drafted.get("assumptions"))) {
            lines.add("  # Assumption: " + words(assumption));
        }
        lines.add("  Scenario" + (outline ? " Outline" : "") + ": " + title);
        List<String> steps = strings(drafted.get("steps"));
        for (int number = 0; number < steps.size(); number++) {
            String step = words(steps.get(number));
            if (step.isEmpty()) {
                continue;
            }
            if (KEYWORDS.stream().noneMatch(step::startsWith)) {
                step = (number == 0 ? "Given " : "And ") + step;
            }
            lines.add("    " + step);
        }
        if (outline) {
            lines.addAll(List.of("", "    Examples:", "      | " + String.join(" | ", header) + " |"));
            for (List<String> row : rows) {
                lines.add("      | " + String.join(" | ", row) + " |");
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /** {@link #assemble(Map, String, String, String, LocalDate)} dated today (UTC). */
    public static String assemble(Map<String, Object> drafted, String feature, String specKey, String key) {
        return assemble(drafted, feature, specKey, key, LocalDate.now(ZoneOffset.UTC));
    }

    static String prompt(String spec, List<String> existing, String specKey) {
        List<String> titles = new ArrayList<>();
        existing.forEach(t -> titles.add("- " + t));
        String listed = titles.isEmpty() ? "(none yet)" : String.join("\n", titles);
        String key = specKey != null && !specKey.isEmpty() ? " (" + specKey + ")" : "";
        return rules() + "\n\n## The step catalogue (JSON)\n\n" + Catalogue.renderJson()
                + "\n\n## Cases already in this session — do not draft these again\n\n" + listed
                + "\n\n## The specification" + key + "\n\n<specification>\n" + spec + "\n</specification>\n\n"
                + "Draft the cases this specification needs, following the rules above. Answer with the\n"
                + "JSON the schema describes: `cases`, `not_drafted`, and `notes` for anything the\n"
                + "author should know.\n";
    }

    /**
     * The checker's findings, with everything the first prompt had: a model fixing a step needs the specification's
     * values as much as it did writing it.
     */
    static String repairPrompt(String spec, String specKey, Map<String, Object> previous,
            Map<String, List<String>> problems) {
        List<String> blocks = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : problems.entrySet()) {
            List<String> found = new ArrayList<>();
            entry.getValue().forEach(p -> found.add("    - " + p));
            blocks.add("- " + entry.getKey() + ":\n" + String.join("\n", found));
        }
        String key = specKey != null && !specKey.isEmpty() ? " (" + specKey + ")" : "";
        return rules() + "\n\n## The step catalogue (JSON)\n\n" + Catalogue.renderJson()
                + "\n\n## The specification" + key + "\n\n<specification>\n" + spec + "\n</specification>\n\n"
                + "## Your previous answer\n\n" + PyJson.indented(previous, 2, true)
                + "\n\n## What CAT's checker said about it\n\n" + String.join("\n", blocks) + "\n\n"
                + "Rewrite the cases the checker refused so every step is a catalogue phrase exactly as\n"
                + "written. A case that cannot be written in catalogue phrases moves to `not_drafted`\n"
                + "with its `missing_phrases`. Keep the cases that passed exactly as they were. Answer\n"
                + "with the full JSON again.\n";
    }

    /**
     * Asks, checks, and repairs up to {@link #REPAIR_ROUNDS} times. Never throws on a bad case.
     *
     * @param ask           who answers; null asks Claude Code ({@code $CAT_AUTHORING_MODEL} names the model)
     * @param transcriptDir where each exchange is kept, or null
     * @param firstNumber   the first drafted case's place in the session
     */
    public static DraftResult draftCases(String spec, String feature, String specKey, List<String> existing, Asker ask,
            Path transcriptDir, int firstNumber, Consumer<String> progress) {
        Asker asker = ask != null ? ask
                : prompt -> runClaude(prompt, System.getenv("CAT_AUTHORING_MODEL"), null, TIMEOUT);
        DraftResult result = new DraftResult();
        List<Map<String, Object>> exchanges = new ArrayList<>();
        String prompt = prompt(spec, existing, specKey);
        Map<String, Object> answer = Map.of();
        for (int round = 0; round <= REPAIR_ROUNDS; round++) {
            progress.accept("asking Claude (round " + (round + 1) + ")…");
            ClaudeAnswer reply = asker.ask(prompt);
            answer = reply.answer();
            result.rounds = round + 1;
            result.costUsd += reply.costUsd();
            result.model = reply.model() != null && !reply.model().isEmpty() ? reply.model() : result.model;
            Map<String, Object> exchange = new LinkedHashMap<>();
            exchange.put("prompt", prompt);
            exchange.put("answer", answer);
            exchange.put("cost_usd", reply.costUsd());
            exchanges.add(exchange);
            result.cases = new ArrayList<>();
            Map<String, List<String>> problems = new LinkedHashMap<>();
            List<Object> cases = answer.get("cases") instanceof List<?> list ? new ArrayList<>(list) : List.of();
            for (int offset = 0; offset < cases.size(); offset++) {
                Map<String, Object> drafted = asMap(cases.get(offset));
                String key = "PETROCORE-" + (firstNumber + offset);
                String text = assemble(drafted, feature, specKey, key);
                Analysis analysis = Check.analyse(text, key + "-TestCase.feature");
                String title = !analysis.scenarios().isEmpty() ? analysis.scenarios().get(0)
                        : PyRepr.str(drafted.getOrDefault("title", ""));
                result.cases.add(new Drafted(title, text, analysis,
                        PyRepr.str(drafted.getOrDefault("covers", "")),
                        strings(drafted.get("assumptions"))));
                if (!analysis.errors().isEmpty()) {
                    List<String> found = new ArrayList<>();
                    for (Diagnostic d : analysis.errors()) {
                        found.add(d.render(""));
                    }
                    problems.put(title, found);
                }
            }
            if (problems.isEmpty()) {
                break;
            }
            progress.accept("the checker refused " + problems.size() + " case(s); asking Claude to fix them…");
            prompt = repairPrompt(spec, specKey, answer, problems);
        }
        result.notDrafted = new ArrayList<>();
        if (answer.get("not_drafted") instanceof List<?> list) {
            list.forEach(item -> result.notDrafted.add(asMap(item)));
        }
        result.notes = truthy(answer.get("notes")) ? PyRepr.str(answer.get("notes")) : "";
        if (transcriptDir != null) {
            try {
                Files.createDirectories(transcriptDir);
                String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
                result.transcript = transcriptDir.resolve("draft-" + stamp + ".json");
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("model", result.model);
                record.put("cost_usd", result.costUsd);
                record.put("exchanges", exchanges);
                Files.writeString(result.transcript, PyJson.indented(record, 2, true), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> out.add(item == null ? "None" : PyRepr.str(item)));
        }
        return out;
    }

    /** {@code " ".join(text.split())}. */
    static String words(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? "" : String.join(" ", stripped.split("\\s+"));
    }

    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static String head(String text, int n) {
        return text.length() <= n ? text : text.substring(0, n);
    }

    /** Python's {@code f"{value:g}"} for the budgets and timeouts this module prints. */
    static String g(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e6) {
            return Long.toString((long) value);
        }
        return new BigDecimal(value).round(new MathContext(6)).stripTrailingZeros().toPlainString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static String javaBin() {
        Path home = Path.of(System.getProperty("java.home"));
        Path bin = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        return bin.toString();
    }

    private static CompletableFuture<byte[]> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream stream = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                stream.transferTo(out);
                return out.toByteArray();
            } catch (IOException e) {
                return new byte[0];
            }
        });
    }

    private static byte[] await(CompletableFuture<byte[]> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (ExecutionException | TimeoutException e) {
            return new byte[0];
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // best effort: an empty directory left under ~/.cat/java/tmp
        }
    }
}
