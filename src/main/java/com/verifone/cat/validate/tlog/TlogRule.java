package com.verifone.cat.validate.tlog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.verifone.cat.validate.RuleFiles;

/**
 * {@code TLOG-RULE.txt}: the tags and attributes a TLog comparison does not count (DESIGN.md
 * §5.4; the Python CAT's {@code petrotest.validate.tlog_rule}).
 *
 * <p>The rule file is key/value lines, {@code KEY=a;b;c;}. The Python reader splits on the first
 * {@code =} and tokenises the rest on {@code ;}, keeping order and duplicates; this reproduces it.
 * {@link #SEEDED} is a Java literal of the Python CAT's {@code data/tlog-rule.json}, recording the
 * petroapps commit and the SHA-256 of the file it was parsed from. It is a literal because this
 * slice may not add resources outside {@code validate/}. {@link #verifySeed} re-parses a live file
 * when one resolves, so "seeded, not hand-written" stays checkable. Regenerating the seed is manual
 * here: parse the live file with {@link #parseLive} and update the literal.
 *
 * <p>The two lists mean what PATS's {@code DifferenceListener} means by them: a tag suppresses a
 * difference in that element's text and in whether it has children, but does not prune its
 * subtree; an attribute suppresses a difference in its value, by local name. CAT's own additions
 * ({@link #CAT_IGNORE_TAGS}, {@link #CAT_IGNORE_ATTRIBUTES}) sit beside the file, not in it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public record TlogRule(
        List<String> tagNames,
        List<String> attributeNames,
        String source,
        String commit,
        String sha256) {

    /** Environment variable naming the live {@code TLOG-RULE.txt}. It overrides the corpus lookup. */
    public static final String ENV_RULE_PATH = "PETROTEST_TLOG_RULE";

    /** Environment variable naming a petroapps checkout root. */
    public static final String ENV_CORPUS_ROOT = "PETROAPPS_ROOT";

    /** The rule file's path relative to a petroapps checkout root. */
    public static final String RULE_RELPATH = "AutomationTests/rulefiles/tlog/TLOG-RULE.txt";

    /** The checked-in parse of the live file at petroapps commit 934cf4d (31 distinct tags, 15 attributes). */
    public static final TlogRule SEEDED = new TlogRule(
            List.of("trUniqueSN", "posNum", "termMsgSN", "trSeq", "date", "duration", "storeNumber",
                    "trpcBatchNr", "trpcSeqNr", "trpcAuthDateTime", "trpcRefNum", "till", "trpcHostID",
                    "trlPrcOvrd", "trpcAuthCode", "trpcAuthSrc", "trpcTicket", "trpcEntryMeth", "trlFuel",
                    "trlFuelSeq", "trpcmMerchID", "trpcmTermID", "trpcAcquirerBatchNr", "trlCwCode",
                    "clockCashier", "clockCashier", "clockDate", "trpcAccount", "trlMatchMixes", "uniqueID",
                    "timeStamp", "trpcTraceNr"),
            List.of("seq", "level", "name", "sysid", "empNum", "posNum", "period", "drawer", "cat",
                    "custDOB", "trpUnsettledChange", "refundTrans", "timeStamp", "dob", "custAge"),
            "AutomationTests/rulefiles/tlog/TLOG-RULE.txt",
            "934cf4d2558e4ebc2c6bc3326d00ae25f6b947a8",
            "30332b300514b664db9803307f35086f6727efe1eb8284d750b94bea6dc210e7");

    /** {@link #SEEDED}'s tag names as a set: membership is the only question the comparer asks. */
    public static final Set<String> IGNORE_TAGS = Collections.unmodifiableSet(new LinkedHashSet<>(SEEDED.tagNames()));

    /** {@link #SEEDED}'s attribute names as a set. */
    public static final Set<String> IGNORE_ATTRIBUTES =
            Collections.unmodifiableSet(new LinkedHashSet<>(SEEDED.attributeNames()));

    /**
     * Tags CAT ignores on top of the file, in every TLog comparison. {@code dispenserReading} is a
     * pump's lifetime totaliser, which carries over from run to run on this rig (rig owner, 2026-09-23).
     */
    public static final Set<String> CAT_IGNORE_TAGS = Set.of("dispenserReading");

    /**
     * Attributes CAT ignores on top of the file. {@code custAge} is the customer's age on the day of
     * the sale, worked out from a fixed date of birth (PETROCORE-36860, 36878 on 55.02).
     */
    public static final Set<String> CAT_IGNORE_ATTRIBUTES = Set.of("custAge");

    public TlogRule {
        tagNames = List.copyOf(tagNames);
        attributeNames = List.copyOf(attributeNames);
    }

    /**
     * The entries of a rule file, keyed as written, in file order. Lines that are blank, comments
     * or have no {@code =} are skipped. Unknown keys are kept rather than rejected, as the Python
     * reader keeps them.
     */
    public static Map<String, List<String>> parseText(String text) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
            String line = raw.strip();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            List<String> names = parsed.computeIfAbsent(key, k -> new ArrayList<>());
            for (String token : line.substring(eq + 1).split(";", -1)) {
                String name = token.strip();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return parsed;
    }

    /** Where the live rule file is, or nothing if none resolves. Explicit path, then env, then the root. */
    public static Optional<Path> resolvePath(Path explicit, Map<String, String> env) {
        return RuleFiles.resolve(explicit, env, ENV_RULE_PATH, ENV_CORPUS_ROOT, Path.of(RULE_RELPATH));
    }

    /**
     * The names under {@code key} ({@code TAG_NAMES} or {@code ATTRIBUTE_NAMES}). Reads the live file
     * if one resolves; otherwise the seed, unless {@code allowSeed} is false.
     *
     * @throws IllegalStateException    if no live file resolves and {@code allowSeed} is false
     * @throws IllegalArgumentException if the live file has no such key
     */
    public static Set<String> parseKey(String key, Path explicit, Map<String, String> env, boolean allowSeed) {
        Optional<Path> source = resolvePath(explicit, env);
        if (source.isEmpty()) {
            if (!allowSeed) {
                throw new IllegalStateException("no live TLOG-RULE.txt found. Set $" + ENV_RULE_PATH
                        + " to the file, or $" + ENV_CORPUS_ROOT + " to a petroapps checkout (expected <root>/"
                        + RULE_RELPATH + "). DESIGN.md 5.4.");
            }
            return new LinkedHashSet<>(seedFor(key));
        }
        Map<String, List<String>> parsed = parseText(readText(source.get()));
        if (!parsed.containsKey(key)) {
            throw new IllegalArgumentException(key + " not found in " + source.get() + ". Present keys: "
                    + parsed.keySet() + ". DESIGN.md 5.4 expects TAG_NAMES and ATTRIBUTE_NAMES.");
        }
        return new LinkedHashSet<>(parsed.get(key));
    }

    /** The seeded names under {@code key}. */
    static List<String> seedFor(String key) {
        return switch (key) {
            case "TAG_NAMES" -> SEEDED.tagNames();
            case "ATTRIBUTE_NAMES" -> SEEDED.attributeNames();
            default -> throw new IllegalArgumentException(key + " is not in the checked-in seed (which holds "
                    + "TAG_NAMES and ATTRIBUTE_NAMES only). Point $" + ENV_RULE_PATH
                    + " at a live rule file to read other keys.");
        };
    }

    /** Parses a live rule file into a {@link TlogRule}, recording the SHA-256 of its bytes. */
    public static TlogRule parseLive(Path source, String commit) {
        byte[] data = readBytes(source);
        Map<String, List<String>> parsed = parseText(new String(data, StandardCharsets.UTF_8));
        return new TlogRule(
                parsed.getOrDefault("TAG_NAMES", List.of()),
                parsed.getOrDefault("ATTRIBUTE_NAMES", List.of()),
                source.toString().replace('\\', '/'),
                commit,
                sha256Hex(data));
    }

    /** What the live rule file says that the seed does not. */
    public record SeedDrift(boolean checked, Path source, Set<String> tagsAdded, Set<String> tagsRemoved,
            Set<String> attributesAdded, Set<String> attributesRemoved) {

        public SeedDrift {
            tagsAdded = Collections.unmodifiableSet(new TreeSet<>(tagsAdded));
            tagsRemoved = Collections.unmodifiableSet(new TreeSet<>(tagsRemoved));
            attributesAdded = Collections.unmodifiableSet(new TreeSet<>(attributesAdded));
            attributesRemoved = Collections.unmodifiableSet(new TreeSet<>(attributesRemoved));
        }

        /** No live file was reachable: nothing to disagree with. */
        public static SeedDrift notChecked() {
            return new SeedDrift(false, null, Set.of(), Set.of(), Set.of(), Set.of());
        }

        public boolean inSync() {
            return tagsAdded.isEmpty() && tagsRemoved.isEmpty() && attributesAdded.isEmpty()
                    && attributesRemoved.isEmpty();
        }

        public String describe() {
            if (!checked) {
                return "TLOG-RULE.txt drift not checked: no live file reachable (set $" + ENV_RULE_PATH
                        + " or $" + ENV_CORPUS_ROOT + ").";
            }
            if (inSync()) {
                return "seed matches " + source;
            }
            StringBuilder text = new StringBuilder("seed is stale against " + source + " (DESIGN.md 5.4):");
            appendLine(text, "TAG_NAMES added upstream", tagsAdded);
            appendLine(text, "TAG_NAMES removed upstream", tagsRemoved);
            appendLine(text, "ATTRIBUTE_NAMES added upstream", attributesAdded);
            appendLine(text, "ATTRIBUTE_NAMES removed upstream", attributesRemoved);
            return text.append("\n  update TlogRule.SEEDED by hand from the live file").toString();
        }

        private static void appendLine(StringBuilder text, String label, Set<String> names) {
            if (!names.isEmpty()) {
                text.append("\n  ").append(label).append(": ").append(String.join(", ", names));
            }
        }
    }

    /**
     * Compares {@link #SEEDED} with the live file, if one resolves. Returns {@code checked = false}
     * rather than failing when there is no checkout: a hosted CI runner has none.
     */
    public static SeedDrift verifySeed(Path explicit, Map<String, String> env) {
        Optional<Path> source = resolvePath(explicit, env);
        if (source.isEmpty()) {
            return SeedDrift.notChecked();
        }
        Map<String, List<String>> live = parseText(readText(source.get()));
        Set<String> liveTags = new LinkedHashSet<>(live.getOrDefault("TAG_NAMES", List.of()));
        Set<String> liveAttrs = new LinkedHashSet<>(live.getOrDefault("ATTRIBUTE_NAMES", List.of()));
        Set<String> seedTags = new LinkedHashSet<>(SEEDED.tagNames());
        Set<String> seedAttrs = new LinkedHashSet<>(SEEDED.attributeNames());
        Set<String> tagsAdded = new LinkedHashSet<>(liveTags);
        tagsAdded.removeAll(seedTags);
        Set<String> tagsRemoved = new LinkedHashSet<>(seedTags);
        tagsRemoved.removeAll(liveTags);
        Set<String> attrsAdded = new LinkedHashSet<>(liveAttrs);
        attrsAdded.removeAll(seedAttrs);
        Set<String> attrsRemoved = new LinkedHashSet<>(seedAttrs);
        attrsRemoved.removeAll(liveAttrs);
        return new SeedDrift(true, source.get(), tagsAdded, tagsRemoved, attrsAdded, attrsRemoved);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java 17 runtime", e);
        }
    }

    private static String readText(Path file) {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
