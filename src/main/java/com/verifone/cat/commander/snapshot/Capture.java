package com.verifone.cat.commander.snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.devices.commander.CgiReplies.CgiFunction;
import com.verifone.cat.rig.RigLease;

/**
 * Viewing the Commander's areas and writing a snapshot of them. A port of {@code commander_snapshot}'s
 * {@code view_area}, {@code fep_views}, {@code snapshot_errors}, {@code capture}, {@code _capture_areas} and
 * {@code folder_digest}.
 *
 * <p>A capture writes into a private generation directory ({@code capture-<id>/}) and validates every requested
 * restorable area before atomically replacing {@code manifest.json}: the replace is the commit point. A failed capture
 * or publication leaves the previous manifest and its files as they were; earlier generations stay on disk for readers
 * that still hold an older manifest.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's capture has run on the lab rig (2026-10-15); its
 * validation and publication changes of 2026-10-19 have not.
 */
public final class Capture {

    /** {@link #snapshotErrors}' reason for a required area the capture did not get. */
    public static final String NOT_CAPTURED = "not captured: ";

    private static final Pattern FAULT = Pattern.compile("<(?:[\\w.-]+:)?Fault\\b");
    private static final Pattern FAULT_CODE = Pattern.compile("<(?:[\\w.-]+:)?faultCode>\\s*(.*?)\\s*</", Pattern.DOTALL);
    private static final Pattern FAULT_STRING =
            Pattern.compile("<(?:[\\w.-]+:)?faultString>\\s*(.*?)\\s*</", Pattern.DOTALL);
    private static final Pattern FEP_DETAIL =
            Pattern.compile("<fepDetail fep=\"([^\"]+)\"[^>]*>(.*?)</fepDetail>", Pattern.DOTALL);
    private static final Pattern ALLOWED_VIEW = Pattern.compile("<allowedCommand name=\"(v[^\"]+)\" type=\"CONFIG_VIEW\"");
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private Capture() {}

    /** Moves the finished manifest over the published one: the commit point. A test passes a failing one. */
    @FunctionalInterface
    interface Publisher {
        void publish(Path pending, Path manifest) throws IOException;

        Publisher ATOMIC = (pending, manifest) -> Files.move(pending, manifest, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** What {@link #capture} needs besides the client and the areas. */
    public static final class Options {
        List<CgiFunction> functions = List.of();
        Map<String, Object> info = Map.of();
        Duration twice;
        boolean overwrite;
        Consumer<String> say = line -> {};
        Pauser pauser = Pauser.REAL;
        Publisher publisher = Publisher.ATOMIC;
        Path repoRoot = Capture.repoRoot();

        public Options functions(List<CgiFunction> value) {
            functions = List.copyOf(value);
            return this;
        }

        /** Recorded in the manifest as it is: commander, suiteinfo, base level, build label, petroapps digest. */
        public Options info(Map<String, Object> value) {
            info = value == null ? Map.of() : new LinkedHashMap<>(value);
            return this;
        }

        /** View every area again this long after the first pass, and record what changed on its own. */
        public Options twice(Duration value) {
            twice = value;
            return this;
        }

        public Options overwrite(boolean value) {
            overwrite = value;
            return this;
        }

        public Options say(Consumer<String> value) {
            say = Objects.requireNonNull(value);
            return this;
        }

        public Options pauser(Pauser value) {
            pauser = Objects.requireNonNull(value);
            return this;
        }

        Options publisher(Publisher value) {
            publisher = Objects.requireNonNull(value);
            return this;
        }

        /** The checkout a snapshot may never be written into; null checks nothing. */
        public Options repoRoot(Path value) {
            repoRoot = value;
            return this;
        }
    }

    // -- viewing --------------------------------------------------------------------------------

    /**
     * {@code v<area>}'s reply as the Commander sent it; a fault or an empty reply raises. Sent through
     * {@code sendCommand} (PATS's attempt loop), not {@code viewConfiguration}, which refuses a reply that is not
     * XML: {@code vPLUs}'s is not, and it is still worth keeping and comparing as text.
     *
     * @throws DeviceError a fault, an empty reply, or no attempt succeeded
     */
    public static String viewArea(CommanderConfig client, String area) {
        String text = client.sendCommand("v" + area, "");
        if (text == null || text.strip().isEmpty()) {
            throw new DeviceError("v" + area + ": empty reply");
        }
        if (FAULT.matcher(text).find()) {
            Matcher code = FAULT_CODE.matcher(text);
            Matcher detail = FAULT_STRING.matcher(text);
            String why = code.find() ? code.group(1) : "(no faultCode)";
            if (detail.find() && !detail.group(1).isEmpty()) {
                String d = detail.group(1);
                why += ": " + d.substring(0, Math.min(200, d.length()));
            }
            throw new DeviceError("v" + area + ": CGILink fault: " + why);
        }
        return text;
    }

    /**
     * Each FEP the Commander has, and the {@code v<x>} views it allows ({@code vfepdetails}'
     * {@code allowedCommand type="CONFIG_VIEW"}); null when the view cannot be read.
     */
    public static Map<String, Set<String>> fepViews(CommanderConfig client) {
        String text;
        try {
            text = client.sendCommand("vfepdetails", "");
        } catch (DeviceError e) {
            return null;
        }
        Map<String, Set<String>> feps = new LinkedHashMap<>();
        Matcher fep = FEP_DETAIL.matcher(text == null ? "" : text);
        while (fep.find()) {
            Set<String> views = new LinkedHashSet<>();
            Matcher view = ALLOWED_VIEW.matcher(fep.group(2));
            while (view.find()) {
                views.add(view.group(1));
            }
            feps.put(fep.group(1), Set.copyOf(views));
        }
        return feps;
    }

    static boolean parses(String text) {
        return XmlTree.parseOrNull(text) != null;
    }

    /** sha256 over a folder's file names and contents (petroapps {@code base/} at capture), in name order. */
    public static String folderDigest(Path folder) {
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (folder != null && Files.isDirectory(folder)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> listing = Files.list(folder)) {
                listing.filter(Files::isRegularFile).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.sort(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .thenComparing(p -> p.getFileName().toString()));
            for (Path file : files) {
                try {
                    sha.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                    sha.update((byte) 0);
                    sha.update(Files.readAllBytes(file));
                    sha.update((byte) 0);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return java.util.HexFormat.of().formatHex(sha.digest());
    }

    // -- validation -----------------------------------------------------------------------------

    /** {@link #snapshotErrors(Path, Map, Collection)} over every area the snapshot names. */
    public static Map<String, String> snapshotErrors(Path directory, Map<String, Object> manifest) {
        return snapshotErrors(directory, manifest, null);
    }

    /**
     * Missing or unreadable required areas, including failures in older snapshots. Excluded areas never prevent
     * restoration. Raw hashes are checked when present; the older normalised hashes cannot be checked after the
     * normalisation rules change.
     *
     * @param only these areas; null for every area the snapshot names
     * @return area (or {@code "snapshot"}) -> what is wrong; empty when it is usable
     */
    public static Map<String, String> snapshotErrors(Path directory, Map<String, Object> manifest,
            Collection<String> only) {
        Object entriesValue = manifest.get("areas");
        Object failedValue = manifest.containsKey("failed") ? manifest.get("failed") : Map.of();
        Object format = manifest.get("format");
        if (!(format instanceof Number number) || number.doubleValue() != Snapshots.MANIFEST_FORMAT
                || !(entriesValue instanceof Map<?, ?> entries) || !(failedValue instanceof Map<?, ?> failed)) {
            return Map.of("snapshot", "invalid snapshot manifest");
        }
        Object requestedValue;
        if (manifest.containsKey("requested_areas")) {
            requestedValue = manifest.get("requested_areas");
        } else {
            List<Object> both = new ArrayList<>(entries.keySet());
            both.addAll(failed.keySet());
            requestedValue = both;
        }
        if (!(requestedValue instanceof List<?> requestedList)
                || !allStrings(requestedList) || !allStrings(entries.keySet()) || !allStrings(failed.keySet())) {
            return Map.of("snapshot", "invalid snapshot areas");
        }
        Set<String> requested = new HashSet<>();
        requestedList.forEach(a -> requested.add((String) a));
        entries.keySet().forEach(a -> requested.add((String) a));
        failed.keySet().forEach(a -> requested.add((String) a));
        Set<String> wanted = new TreeSet<>(Areas.ORDER);
        wanted.addAll(only == null ? requested : only);
        Map<String, String> problems = new LinkedHashMap<>();
        Path root = directory.toAbsolutePath().normalize();
        Object absent = manifest.get("absent");
        for (String area : wanted) {
            if (Areas.exclusion(area) != null || holds(absent, area) || Areas.neverAnswers(area, castKeys(failed))) {
                continue;
            }
            if (failed.containsKey(area) || !entries.containsKey(area)) {
                Object why = failed.get(area);
                problems.put(area, NOT_CAPTURED + (why != null ? why : "missing required area"));
                continue;
            }
            try {
                if (!(entries.get(area) instanceof Map<?, ?> entry) || !(entry.get("file") instanceof String file)) {
                    throw new IllegalArgumentException("'file'");
                }
                Path path = root.resolve(file).normalize();
                if (!path.startsWith(root)) {
                    throw new IllegalArgumentException("area file is outside the snapshot");
                }
                byte[] data = Files.readAllBytes(path);
                Object content = entry.get("content_sha256");
                if (content instanceof String sha && !sha.isEmpty() && !Normalise.sha256Hex(data).equals(sha)) {
                    throw new IllegalArgumentException("area file checksum mismatch");
                }
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
            } catch (IOException | RuntimeException e) {
                problems.put(area, describe(e));
            }
        }
        boolean anyRestorable = requested.stream().anyMatch(a -> Areas.exclusion(a) == null);
        if (!anyRestorable) {
            problems.put("snapshot", "no restorable areas were requested");
        }
        return problems;
    }

    private static String describe(Exception e) {
        if (e instanceof CharacterCodingException) {
            return "area file is not UTF-8: " + e;
        }
        if (e instanceof java.nio.file.NoSuchFileException) {
            return "No such file or directory: " + e.getMessage();
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static boolean allStrings(Collection<?> values) {
        return values.stream().allMatch(String.class::isInstance);
    }

    private static boolean holds(Object container, String area) {
        if (container instanceof Map<?, ?> map) {
            return map.containsKey(area);
        }
        if (container instanceof Collection<?> collection) {
            return collection.contains(area);
        }
        return false;
    }

    private static Map<String, ?> castKeys(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    // -- capture --------------------------------------------------------------------------------

    /**
     * Captures a candidate, then atomically publishes its manifest when usable.
     *
     * <p>Each capture owns immutable files under {@code outDir}; replacing the manifest is the commit point.
     * Failed required captures never replace a working snapshot. {@code twice} records areas that change with
     * nothing done; excluded-area faults remain diagnostic only.
     *
     * @return the manifest captured (as JSON reads it), published or not: {@link Snapshots#loadManifest} equals it
     *         only when it was published
     * @throws FileAlreadyExistsException {@code outDir} holds a snapshot and {@code overwrite} is off
     * @throws IllegalArgumentException   {@code outDir} is inside this repository, or two areas share one file
     */
    public static Map<String, Object> capture(CommanderConfig client, List<String> areaNames, Path outDir,
            Options options) throws IOException {
        if (options.repoRoot != null && outDir.toAbsolutePath().normalize()
                .startsWith(options.repoRoot.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(outDir + " is inside this repository: a snapshot carries site IPs");
        }
        Areas.SiteAreas site = Areas.siteAreas(areaNames, fepViews(client));
        site.dropped().forEach((area, why) -> options.say.accept(area + ": not captured here: " + why));
        List<String> names = site.kept();
        Map<String, String> seen = new LinkedHashMap<>();
        for (String area : names) {
            String key = Areas.areaFile(area).toLowerCase(Locale.ROOT);
            if (seen.containsKey(key)) {
                throw new IllegalArgumentException("areas '" + seen.get(key) + "' and '" + area
                        + "' would share one file here");
            }
            seen.put(key, area);
        }

        Files.createDirectories(outDir);
        try (RigLease lease = new RigLease("capture", outDir, null)) {
            lease.acquire(RigLease.DEFAULT_ACQUIRE_TIMEOUT);
            if (Files.exists(outDir.resolve(Snapshots.MANIFEST)) && !options.overwrite) {
                throw new FileAlreadyExistsException(outDir + " already holds a snapshot; pass overwrite");
            }
            Path candidate = Files.createTempDirectory(outDir, "capture-");
            Map<String, Object> manifest = null;
            try {
                manifest = captureAreas(client, names, candidate, options);
                Map<String, String> problems = snapshotErrors(candidate, manifest);
                if (!problems.isEmpty()) {
                    List<String> parts = new ArrayList<>();
                    problems.forEach((a, s) -> parts.add(a + ": " + s));
                    options.say.accept("snapshot not published: " + String.join("; ", parts));
                    return manifest;
                }
                for (Object entry : ((Map<?, ?>) manifest.get("areas")).values()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> e = (Map<String, Object>) entry;
                    e.put("file", candidate.getFileName() + "/" + e.get("file"));
                }
                Path pending = candidate.resolve(Snapshots.MANIFEST);
                Files.writeString(pending, Snapshots.manifestJson(manifest), StandardCharsets.UTF_8);
                options.publisher.publish(pending, outDir.resolve(Snapshots.MANIFEST));
                return manifest;
            } finally {
                // The persisted manifest identifies a committed capture even if interrupted right after the move.
                // Previous generations are kept for readers.
                if (manifest == null || !manifest.equals(Snapshots.loadManifest(outDir))) {
                    deleteTree(candidate);
                }
            }
        }
    }

    private static Map<String, Object> captureAreas(CommanderConfig client, List<String> names, Path outDir,
            Options options) throws IOException {
        Map<String, Boolean> otp = new LinkedHashMap<>();
        for (CgiFunction f : options.functions) {
            otp.put(f.cmd(), f.otpRequired());
        }
        String started = now();
        Map<String, Object> entries = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();
        Map<String, String> absent = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        int total = names.size();
        int index = 0;
        for (String area : names) {
            index++;
            String text;
            try {
                text = viewArea(client, area);
            } catch (DeviceUnreachable e) {
                failed.put(area, e.getMessage());
                options.say.accept("[" + index + "/" + total + "] " + area + ": FAILED " + e.getMessage());
                continue;
            } catch (DeviceError e) {
                if (Areas.PER_REGISTER.contains(Areas.baseArea(area)) && area.contains("&")) {
                    absent.put(area, "no such register on this Commander (" + e.getMessage() + ")");
                    options.say.accept("[" + index + "/" + total + "] " + area + ": absent (" + e.getMessage() + ")");
                    continue;
                }
                failed.put(area, e.getMessage());
                options.say.accept("[" + index + "/" + total + "] " + area + ": FAILED " + e.getMessage());
                continue;
            }
            texts.put(area, text);
            String name = Areas.areaFile(area);
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            Files.write(outDir.resolve(name), data); // as sent: no newline translation
            String reason = Areas.exclusion(area);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", name);
            entry.put("sha256", Normalise.digest(area, text));
            entry.put("content_sha256", Normalise.sha256Hex(data));
            entry.put("bytes", data.length);
            entry.put("parsed", parses(text));
            entry.put("otp", otp.getOrDefault("v" + Areas.baseArea(area), false));
            entry.put("excluded", reason);
            entries.put(area, entry);
            String mark = reason != null ? " (excluded: " + reason.split(":", 2)[0] + ")" : "";
            options.say.accept("[" + index + "/" + total + "] " + area + ": " + data.length + " bytes" + mark);
        }

        Map<String, Object> volatileFound = new LinkedHashMap<>();
        if (options.twice != null) {
            options.say.accept("viewing every area again in " + Math.round(options.twice.toMillis() / 1000.0)
                    + " s, to find what changes on its own");
            options.pauser.pause(options.twice);
            for (Map.Entry<String, String> first : texts.entrySet()) {
                String area = first.getKey();
                String second;
                try {
                    second = viewArea(client, area);
                } catch (DeviceError e) {
                    volatileFound.put(area, List.of("second view failed: " + e.getMessage()));
                    continue;
                }
                if (!Normalise.normalise(area, first.getValue()).equals(Normalise.normalise(area, second))) {
                    List<String> lines = Normalise.differences(area, first.getValue(), second, 40);
                    volatileFound.put(area, lines);
                    options.say.accept("VOLATILE? " + area + ":\n  " + String.join("\n  ", lines));
                }
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", Snapshots.MANIFEST_FORMAT);
        manifest.put("captured_at", started);
        manifest.put("finished_at", now());
        manifest.putAll(options.info);
        manifest.put("funclist_commands", options.functions.size());
        manifest.put("otp_required_commands", options.functions.stream().filter(CgiFunction::otpRequired)
                .map(CgiFunction::cmd).sorted().toList());
        manifest.put("areas", entries);
        manifest.put("requested_areas", names);
        Map<String, Object> excluded = new LinkedHashMap<>();
        entries.forEach((a, e) -> {
            Object why = ((Map<?, ?>) e).get("excluded");
            if (why != null) {
                excluded.put(a, why);
            }
        });
        manifest.put("excluded", excluded);
        manifest.put("failed", failed);
        manifest.put("absent", absent);
        manifest.put("volatile", options.twice != null ? volatileFound : null);
        long restorable = entries.values().stream().filter(e -> ((Map<?, ?>) e).get("excluded") == null).count();
        options.say.accept("captured " + entries.size() + " areas (" + restorable + " restorable, "
                + (entries.size() - restorable) + " excluded), " + failed.size() + " failed"
                + (options.twice != null ? ", " + volatileFound.size() + " changed on their own" : ""));
        return Snapshots.asJson(manifest);
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(ISO_SECONDS);
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * This checkout, which a snapshot may never be written into (the IP gate scans the tree): the folder above the
     * {@code target/} the classes were loaded from; null when that cannot be told.
     */
    static Path repoRoot() {
        try {
            var source = Capture.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path here = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            for (Path p = here; p != null; p = p.getParent()) {
                if (p.getFileName() != null && p.getFileName().toString().equals("target")) {
                    return p.getParent();
                }
            }
            return null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
