package com.verifone.cat.authoring;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.authoring.Check.Analysis;
import com.verifone.cat.collect.Selection;
import com.verifone.cat.recorder.Recording;
import com.verifone.cat.recorder.RecordingException;
import com.verifone.cat.validate.PyRepr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * An Authoring session: the cases an author is writing, in order, as {@code .feature} drafts. Port of
 * {@code cat.authoring.session}.
 *
 * <p>One session is one directory: {@code session.json} (the cases in order, the story and spec tickets, the baselines'
 * approval, and the last {@link Replay}), each case's {@code case-<id>.feature} draft, its recording
 * ({@code case-<id>.ndjson}, when it was recorded: {@link #adoptRecording}), the ingested {@code spec-<KEY>.md}, and
 * {@code drafts/} (what the drafting model was asked and answered). Its baselines sit in {@code baselines/<id>/}, as
 * {@code CASE-CATReceipt.txt} and {@code CASE-Tlog.xml}, unapproved until {@link #approveBaselines}.
 *
 * <p><b>Where a case comes from does not matter after it exists.</b> A recording becomes a draft ({@link Recorded}), a
 * model's answer becomes a draft ({@link Draft}), a person types one; from then on the draft is edited, checked and
 * replayed the same way.
 *
 * <p><b>Numbering.</b> A case is {@code PETROCORE-<n>} where {@code n} is its place in the session, and {@link #rendered}
 * writes that tag, so the checker's tag and file-name rules hold whatever the author typed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: a session is files; nothing here reaches a rig or Jira.
 */
public final class Session {

    /** The session file's name in a session directory. */
    public static final String SESSION_FILE = "session.json";

    /** The format tag {@code session.json} carries. */
    public static final String FORMAT = "cat-authoring-session";

    /** The format version this reader knows. */
    public static final int VERSION = 1;

    /** A baseline's file name in the session store: {@code CASE} stands for {@code PETROCORE-<n>}. */
    static final String BASE = "CASE";

    private static final Pattern TICKET = Pattern.compile("^PETROCORE-[1-9]\\d*$");
    private static final Pattern KEY_TAG = Pattern.compile("@PETROCORE-\\d+");
    private static final Pattern SCENARIO_NAME = Pattern.compile("^(\\s*Scenario(?: Outline)?:).*$", Pattern.MULTILINE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    private static final DateTimeFormatter LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** A baseline capture entry: a case in the captured run, and its number there. */
    public record CaptureEntry(String caseId, int number) {}

    private final Path directory;
    private String createdAt;
    private String ticket;
    private String specTicket;
    private final List<Case> cases;
    private final Map<String, Map<String, Object>> baselines;
    private Replay replay;

    /** A stored baseline as its {@code -CATReceipt.txt}-style tail and its path. */
    public record Tail(String tail, Path path) {}

    private Session(Path directory, String createdAt, String ticket, String specTicket, List<Case> cases,
            Map<String, Map<String, Object>> baselines, Replay replay) {
        this.directory = directory;
        this.createdAt = createdAt;
        this.ticket = ticket;
        this.specTicket = specTicket;
        this.cases = cases;
        this.baselines = baselines;
        this.replay = replay;
    }

    // -------------------------------------------------------------- files

    /** The root sessions live under: {@code CAT_AUTHORING_DIR} from {@code env}, else {@code fallback}. */
    public static Path authoringRoot(Map<String, String> env, Path fallback) {
        String value = env.get("CAT_AUTHORING_DIR");
        return value == null || value.isEmpty() ? fallback : Path.of(value);
    }

    /** A PETROCORE key, normalised; empty clears it. */
    public static String storyTicket(String text) {
        String key = text.strip().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        if (!TICKET.matcher(key).matches()) {
            throw new SessionException(PyRepr.reprString(text.strip()) + " is not a ticket like PETROCORE-12345");
        }
        return key;
    }

    /** {@code text} with its case-key tag set to {@code @<key>}: replaced when present, else added before the Feature line. */
    public static String withKey(String text, String key) {
        Matcher found = KEY_TAG.matcher(text);
        if (found.find()) {
            return text.substring(0, found.start()) + "@" + key + text.substring(found.end());
        }
        List<String> lines = splitKeepingEnds(text);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("Feature:")) {
                lines.add(i, "@" + key + "\n");
                return String.join("", lines);
            }
        }
        return "@" + key + "\n" + text;
    }

    /** A new written case: the opening every charity case has, and nothing guessed. */
    static String skeleton(String title, String feature) {
        return "@PETROCORE-1\nFeature: " + feature + "\n\n  Scenario: " + title + "\n    Given the register is idle\n";
    }

    private static String titleOf(String text) {
        String title = String.join(" ", text.strip().split("\\s+"));
        if (title.isEmpty()) {
            throw new SessionException("a case needs a name");
        }
        if (title.contains(":") || text.contains("\n")) {
            throw new SessionException("a case name is one line without a colon");
        }
        return title;
    }

    private static List<String> splitKeepingEnds(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** A new session under {@code root}, in a folder named by the local time, saved at once. */
    public static Session create(Path root, Instant now) {
        String base = now.atZone(ZoneId.systemDefault()).format(LOCAL_STAMP);
        Path directory = root.resolve(base);
        int n = 1;
        while (Files.exists(directory)) {
            n++;
            directory = root.resolve(base + "-" + n);
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Session session = new Session(directory, isoMillis(now), null, null, new ArrayList<>(),
                new LinkedHashMap<>(), null);
        session.save();
        return session;
    }

    /** {@link #create(Path, Instant)} at the current time. */
    public static Session create(Path root) {
        return create(root, Instant.now());
    }

    /** The session {@code directory} holds.
     *
     * @throws SessionException when the file is missing, unreadable, not this format, or another version
     */
    public static Session load(Path directory) {
        Path path = directory.resolve(SESSION_FILE);
        JsonNode data;
        try {
            data = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new SessionException(path + ": " + e.getMessage(), e);
        }
        if (data == null || !data.isObject() || !FORMAT.equals(data.path("format").asText(null))) {
            throw new SessionException(path + ": not a " + FORMAT + " file");
        }
        JsonNode version = data.path("version");
        if (!version.isInt() || version.asInt() != VERSION) {
            throw new SessionException(path + ": version " + version.asText() + "; this reader knows 1");
        }
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : data.path("cases")) {
            if (!node.hasNonNull("id") || !node.hasNonNull("title")) {
                throw new SessionException(path + ": a case needs an id and a title");
            }
            String source = node.hasNonNull("source") ? node.get("source").asText() : "written";
            String recording = node.hasNonNull("recording") ? node.get("recording").asText() : null;
            cases.add(new Case(node.get("id").asText(), node.get("title").asText(), source, recording));
        }
        Map<String, Map<String, Object>> baselines = new LinkedHashMap<>();
        JsonNode raw = data.path("baselines");
        if (raw.isObject()) {
            raw.fields().forEachRemaining(entry -> {
                Map<String, Object> record = new LinkedHashMap<>();
                entry.getValue().fields().forEachRemaining(field -> record.put(field.getKey(), toJava(field.getValue())));
                baselines.put(entry.getKey(), record);
            });
        }
        Replay replay;
        try {
            replay = data.hasNonNull("replay") ? Replay.fromJson(data.get("replay")) : null;
        } catch (SessionException e) {
            throw new SessionException(path + ": " + e.getMessage(), e);
        }
        return new Session(directory, data.path("created_at").asText(""), nullableText(data, "story_ticket"),
                nullableText(data, "spec_ticket"), cases, baselines, replay);
    }

    /** The newest readable session under {@code root}, or null. */
    public static Session latest(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> listed = Files.list(root)) {
            listed.filter(d -> Files.isRegularFile(d.resolve(SESSION_FILE))).forEach(dirs::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        dirs.sort(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed());
        for (Path directory : dirs) {
            try {
                return load(directory);
            } catch (SessionException e) {
                continue;
            }
        }
        return null;
    }

    /** The session folder's name. */
    public String slug() {
        return directory.getFileName().toString();
    }

    public Path directory() {
        return directory;
    }

    public String createdAt() {
        return createdAt;
    }

    public String ticket() {
        return ticket;
    }

    public String specTicket() {
        return specTicket;
    }

    /** The cases, in order. Changing the list changes the session. */
    public List<Case> cases() {
        return cases;
    }

    /** The last replay (or capture) the session launched, or null when there is none. */
    public Replay replay() {
        return replay;
    }

    /** Records the replay a run made (or null to clear it), and saves. */
    public void replay(Replay value) {
        replay = value;
        save();
    }

    /** Saves {@code session.json} atomically. Every mutation calls it before returning. */
    public void save() {
        ObjectNode data = JSON.createObjectNode();
        data.put("format", FORMAT);
        data.put("version", VERSION);
        data.put("created_at", createdAt);
        putNullable(data, "story_ticket", ticket);
        putNullable(data, "spec_ticket", specTicket);
        var caseNodes = data.putArray("cases");
        for (Case c : cases) {
            ObjectNode node = caseNodes.addObject();
            node.put("id", c.id());
            node.put("title", c.title());
            node.put("source", c.source());
            putNullable(node, "recording", c.recording());
        }
        ObjectNode baselineNodes = data.putObject("baselines");
        for (Map.Entry<String, Map<String, Object>> entry : baselines.entrySet()) {
            ObjectNode record = baselineNodes.putObject(entry.getKey());
            for (Map.Entry<String, Object> field : entry.getValue().entrySet()) {
                record.set(field.getKey(), JSON.valueToTree(field.getValue()));
            }
        }
        if (replay == null) {
            data.putNull("replay");
        } else {
            data.set("replay", JSON.valueToTree(replay.toJson()));
        }
        try {
            String text = JSON.writer(new DefaultPrettyPrinter()).writeValueAsString(data) + "\n";
            writeAtomic(directory.resolve(SESSION_FILE), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The draft's path. */
    public Path featurePath(Case c) {
        return directory.resolve(c.feature());
    }

    /** The case's recording: its {@code recording} file, else {@code <id>.ndjson}. */
    public Path recordingPath(Case c) {
        return directory.resolve(c.recording() != null ? c.recording() : c.id() + ".ndjson");
    }

    /**
     * Makes a finished recording the case's draft ({@link Recorded#recordingFeature}): the case becomes {@code recorded}.
     *
     * @throws IOException        when the recording cannot be read
     * @throws com.verifone.cat.recorder.RecordingException when it is not a recording
     */
    public void adoptRecording(Case c, String filename) throws IOException {
        Recording recording = Recording.read(directory.resolve(filename));
        c.recording(filename);
        c.source("recorded");
        String text = Recorded.recordingFeature(recording, c.title(), featureName(), "PETROCORE-" + number(c));
        writeText(c, text);
        save();
    }

    /** The case's baseline folder: {@code baselines/<id>}. */
    public Path baselinesDir(Case c) {
        return directory.resolve("baselines").resolve(c.id());
    }

    /** The spec file's path, or null when no spec was ingested. */
    public Path specPath() {
        return specTicket == null ? null : directory.resolve("spec-" + specTicket + ".md");
    }

    // -------------------------------------------------------------- the drafts

    /** The {@code Feature:} line a new case gets: the story's, once there is one. */
    public String featureName() {
        return ticket != null ? ticket + " authored cases" : "Authored cases";
    }

    /** The draft's text as written, or the empty string when its file is missing. */
    public String text(Case c) {
        try {
            return Files.readString(featurePath(c), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The case's place in the session, from 1. */
    public int number(Case c) {
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i) == c) {
                return i + 1;
            }
        }
        throw new SessionException(c.title() + " is not in this session");
    }

    /** The draft as it replays: tagged with its placeholder key (D25). */
    public String rendered(Case c) {
        return withKey(text(c), "PETROCORE-" + number(c));
    }

    /** Writes the draft, with a trailing newline. */
    public void writeText(Case c, String text) {
        writeAtomic(featurePath(c), text.endsWith("\n") ? text : text + "\n");
    }

    // -------------------------------------------------------------- edits

    /** Sets the story ticket (empty clears it). */
    public String setTicket(String text) {
        ticket = storyTicket(text);
        save();
        return ticket;
    }

    /** Sets the spec ticket, or clears it with null. */
    public void setSpecTicket(String key) {
        specTicket = key;
        save();
    }

    /** Adds a case at {@code index} (clamped), with {@code text} as its draft or a skeleton when null. */
    public Case insert(int index, String title, String source, String text) {
        String name = titleOf(title);
        Case c = new Case("case-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), name, source, null);
        writeText(c, text != null ? text : skeleton(name, featureName()));
        cases.add(Math.max(0, Math.min(index, cases.size())), c);
        save();
        return c;
    }

    /** Adds a written case at {@code index}, with a skeleton draft. */
    public Case insert(int index, String title) {
        return insert(index, title, "written", null);
    }

    /** A new name for the case at {@code index}, written into its {@code Scenario:} line too. */
    public Case rename(int index, String title) {
        Case c = cases.get(index);
        c.title(titleOf(title));
        String text = text(c);
        Matcher found = SCENARIO_NAME.matcher(text);
        if (found.find()) {
            writeText(c, text.substring(0, found.start()) + found.group(1) + " " + c.title() + text.substring(found.end()));
        }
        save();
        return c;
    }

    /** Moves the case at {@code index} by {@code delta}; past either end, nothing moves. Returns its new index. */
    public int move(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= cases.size() || target < 0 || target >= cases.size()) {
            return index;
        }
        Case moved = cases.remove(index);
        cases.add(target, moved);
        save();
        return target;
    }

    /** Drops the case at {@code index} from the list; its files stay on disk. */
    public Case remove(int index) {
        Case c = cases.remove(index);
        save();
        return c;
    }

    // -------------------------------------------------------------- review

    /** The case as the replay gate and the publisher will see it. */
    public CaseReview review(Case c) {
        int number = number(c);
        String text = rendered(c);
        Analysis analysis = Check.analyse(text, "PETROCORE-" + number + "-TestCase.feature");
        List<Path> files = baselineFiles(c);
        Map<String, Object> record = baselines.getOrDefault(c.id(), Map.of());
        boolean approved = Boolean.TRUE.equals(record.get("approved"));
        Object capturedIn = record.get("captured_in");
        Recording recording = null;
        String recordingError = null;
        if (c.recording() != null && !c.recording().isEmpty()) {
            try {
                recording = Recording.read(recordingPath(c));
            } catch (IOException | RecordingException e) {
                recordingError = e instanceof IOException && e.getMessage() != null
                        ? recordingPath(c) + ": " + e.getMessage() : e.getMessage();
            }
        }
        return new CaseReview(c, number, text, analysis, files, approved,
                capturedIn == null ? "" : capturedIn.toString(), recording, recordingError);
    }

    /** What stops the session being replayed: every case's blockers, or {@code no case yet}. */
    public List<String> replayBlockers() {
        List<String> out = new ArrayList<>();
        if (cases.isEmpty()) {
            out.add("no case yet");
        }
        for (Case c : cases) {
            out.addAll(review(c).replayBlockers());
        }
        return out;
    }

    /** What stops the session being published: every case's publish blockers and the story ticket (D17). */
    public List<String> publishBlockers() {
        List<String> out = new ArrayList<>();
        if (cases.isEmpty()) {
            out.add("no case yet");
        }
        for (Case c : cases) {
            out.addAll(review(c).publishBlockers());
        }
        if (ticket == null) {
            out.add("no story ticket (D17)");
        }
        return out;
    }

    // -------------------------------------------------------------- baselines

    /** The case's baseline files, by name. */
    public List<Path> baselineFiles(Case c) {
        Path folder = baselinesDir(c);
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (DirectoryStream<Path> listed = Files.newDirectoryStream(folder)) {
            for (Path p : listed) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()));
        return out;
    }

    /** Each stored baseline as ({@code -CATReceipt.txt}-style tail, path). */
    public List<Tail> baselineTails(Case c) {
        List<Tail> out = new ArrayList<>();
        for (Path path : baselineFiles(c)) {
            out.add(new Tail(path.getFileName().toString().substring(BASE.length()), path));
        }
        return out;
    }

    /**
     * Copies what capture run {@code replay} wrote beside each case into the session, unapproved; {@code root} is the
     * folder its {@link Replay#directory} is under. A case whose capture wrote nothing keeps whatever it had.
     *
     * @return how many files were taken
     */
    public int importCapture(Path root, Replay replay) {
        List<CaptureEntry> entries = new ArrayList<>();
        for (Replay.ReplayCase entry : replay.cases()) {
            entries.add(new CaptureEntry(entry.caseId(), entry.number()));
        }
        return importCapture(root, replay.directory(), replay.runId(), entries);
    }

    /**
     * Copies what a capture run wrote beside each case into the session, unapproved. {@code relativeDirectory} is the
     * captured run's folder under {@code repoRoot}; a case whose capture wrote nothing keeps whatever it had.
     *
     * @return how many files were taken
     */
    public int importCapture(Path repoRoot, String relativeDirectory, String runId, List<CaptureEntry> entries) {
        Path directory = repoRoot.resolve(relativeDirectory);
        int taken = 0;
        for (CaptureEntry entry : entries) {
            Case c = cases.stream().filter(x -> x.id().equals(entry.caseId())).findFirst().orElse(null);
            if (c == null) {
                continue;
            }
            String key = "PETROCORE-" + entry.number();
            List<Path> found = capturedBaselines(directory, key);
            if (found.isEmpty()) {
                continue;
            }
            Path target = baselinesDir(c);
            deleteTree(target);
            try {
                Files.createDirectories(target);
                for (Path path : found) {
                    String name = path.getFileName().toString();
                    Files.copy(path, target.resolve(BASE + name.substring(key.length())),
                            StandardCopyOption.REPLACE_EXISTING);
                    taken++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("approved", false);
            record.put("captured_in", runId);
            baselines.put(c.id(), record);
        }
        save();
        return taken;
    }

    private static List<Path> capturedBaselines(Path directory, String key) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (DirectoryStream<Path> listed = Files.newDirectoryStream(directory)) {
            for (Path p : listed) {
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && name.regionMatches(true, 0, key + "-", 0, key.length() + 1)
                        && Selection.isBaseline(name, key)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()));
        return out;
    }

    /**
     * Makes the case's captured baselines its reference. Returns how many.
     *
     * @throws SessionException when there is nothing captured to approve
     */
    public int approveBaselines(Case c) {
        List<Path> files = baselineFiles(c);
        if (files.isEmpty()) {
            throw new SessionException(c.title() + ": no captured baseline to approve — run ◉ Capture");
        }
        baselines.computeIfAbsent(c.id(), k -> new LinkedHashMap<>()).put("approved", true);
        save();
        return files.size();
    }

    /** The SHA-256 of what a replay runs for this case: its rendered draft, and its approved baselines. */
    public String digest(Case c) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(rendered(c).getBytes(StandardCharsets.UTF_8));
            if (Boolean.TRUE.equals(baselines.getOrDefault(c.id(), Map.of()).get("approved"))) {
                for (Path path : baselineFiles(c)) {
                    String tail = path.getFileName().toString().substring(BASE.length());
                    sha.update(tail.getBytes(StandardCharsets.UTF_8));
                    sha.update(Files.readAllBytes(path));
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -------------------------------------------------------------- helpers

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code text} to {@code path} through a sibling {@code .tmp} file, then moves it into place. */
    static void writeAtomic(Path path, String text) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String isoMillis(Instant moment) {
        return ISO_MILLIS.format(moment.atZone(ZoneOffset.UTC));
    }

    private static String nullableText(JsonNode data, String field) {
        return data.hasNonNull(field) ? data.get(field).asText() : null;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static Object toJava(JsonNode node) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.isNull() ? null : node.asText();
    }
}
