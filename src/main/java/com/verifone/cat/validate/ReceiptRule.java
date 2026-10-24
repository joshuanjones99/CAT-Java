package com.verifone.cat.validate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PATS's shared {@code RECEIPT-RULE.txt}: the tokens whose lines a receipt comparison does not
 * count (DESIGN.md §5.4; the Python CAT's {@code petrotest.validate.receipt_rule}).
 *
 * <p>The rule file is one line of comma-separated entries. A numeric entry is a receipt line
 * number and a non-numeric entry is a token; PATS drops any line that contains a token, or a
 * date or time. Matching is {@code contains}, case-sensitive, anywhere in the line. This rule
 * only decides which surviving differences do not count: the global normalisers run first and
 * are unchanged, and the snapshot still holds the whole receipt.
 *
 * <p>The seed is {@link #SEEDED}, a Java literal of the Python CAT's {@code receipt-rule.json},
 * recording the petroapps commit and the SHA-256 of the file it was parsed from. It is a literal
 * rather than a resource because this slice may not add resources outside
 * {@code validate/}. {@link #verifySeed} re-parses a live rule file when one is reachable and
 * reports drift, so "seeded, not hand-written" stays checkable. Regenerating the seed is manual
 * here: parse the live file, drop the {@link #DEVIATIONS}, and update the literal.
 *
 * <p>Two deliberate deviations from PATS are dropped from the seed: {@code ST#CL120} (a rig
 * literal, which the validation layer bans) and {@code AuthNet:} (card identity, which stays
 * compared; see {@link Normalisers#CARD_IDENTITY_NORMALISERS}).
 *
 * <p>The report-line shapes ({@link #REPORT_LINE}, {@link #TIMINGS_SECTION}) are CAT's addition,
 * not PATS's file, by the rig owner's decision of 2026-09-17.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public record ReceiptRule(
        List<String> tokens,
        String source,
        String commit,
        String sha256,
        List<String> dropped) {

    /** Where the seed's rule file sits relative to a petroapps checkout root. */
    public static final String RULE_RELPATH = "AutomationTests/rulefiles/receipts/RECEIPT-RULE.txt";

    /** Environment variable naming the live rule file. It overrides the default lookup. */
    public static final String ENV_RULE_PATH = "PETROTEST_RECEIPT_RULE";

    /** Environment variable naming a petroapps checkout root. */
    public static final String ENV_CORPUS_ROOT = "PETROAPPS_ROOT";

    /**
     * PATS's {@code DATE_TIME_REGEX}, verbatim. A line holding a date or time is dropped whatever
     * else is on it.
     */
    public static final Pattern DATE_TIME = Pattern.compile(
            "\\b\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{2,4}(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?(?: [APap][Mm])?)?\\b",
            Pattern.UNIX_LINES);

    /**
     * The same date once {@link Normalisers} has masked it. Without this a dated line PATS drops
     * stayed compared, once the rule ran on normalised text. A bare {@code <TIME>} is not a date.
     */
    public static final Pattern MASKED_DATE = Pattern.compile("<DATE(?:TIME)?>", Pattern.UNIX_LINES);

    /**
     * Tokens in PATS's file that CAT's copy leaves out. The rig owner's decisions of 2026-09-15.
     */
    public static final Map<String, String> DEVIATIONS;

    static {
        Map<String, String> deviations = new LinkedHashMap<>();
        deviations.put("ST#CL120", "a rig literal, which the validation layer bans. No receipt line "
                + "carries it without also carrying the bare ST#, and TRAN# kills every trailer on its own.");
        deviations.put("AuthNet:", "card identity, not noise: masking it deletes the assertion that the "
                + "AMEX credit path printed an AMEX AuthNet. Rig owner, 2026-09-15: AuthNet stays compared.");
        DEVIATIONS = Collections.unmodifiableMap(deviations);
    }

    /**
     * A flash report's rig counters, by shape, each anchored so no sale line matches: the receipt
     * range, the OPEN and CLOSE daypart stamps, a totaliser meter row (label then two amounts, so
     * {@code BEGINNING CASH  150.00} stays compared), and the batch, day-sequence and receipt-range
     * counters that count from install.
     */
    public static final Pattern REPORT_LINE = Pattern.compile(
            "Receipt #\\d+ to #\\d+"
            + "|^\\s*(?:OPEN|CLOSE)\\s+daypart\\b"
            + "|^\\s*(?:ENDING|BEGINNING|DIFFERENCE)\\s+-?[\\d,]*\\.\\d+\\s+-?[\\d,]*\\.\\d+\\s*$"
            + "|^\\s*BATCH/DAY SEQ#\\s*=\\s*\\d+\\s*$"
            + "|^\\s*DAY SEQ#:\\s*\\d+\\s*$"
            + "|^\\s*RCPTS:\\s*\\d+-\\d+\\b",
            Pattern.UNIX_LINES);

    /** The report section whose rows are ignored: every line holding a digit, to the next header. */
    public static final String TIMINGS_SECTION = "TRANSACTION TIMINGS";

    /** A report section header such as {@code ----------TOTALS----------}. A bare rule of dashes is not one. */
    private static final Pattern SECTION_HEADER = Pattern.compile("^\\s*-{3,}[^-].*[^-]-{3,}\\s*$",
            Pattern.UNIX_LINES);

    /** The checked-in seed: PATS's {@code RECEIPT-RULE.txt} at petroapps commit d8da3636, minus {@link #DEVIATIONS}. */
    public static final ReceiptRule SEEDED = new ReceiptRule(
            List.of("TRAN#", "Stan:", "Trace #:", "Invoice #:", "Auth #:", "Store #", "CARD BAL:", "STAN:",
                    "Invoice number:", "CTL#:", "Inv#", "Auth#", "CSH:", "!BALANCE", "daypart#", "Batch:",
                    "EMV STAN#:", "APP CRYPTOGRAM: ", "INVOICE:", "STAN #:", "AUTH:", "Seq:", "Term Seq #:",
                    "Terminal Seq Num:", "ST#"),
            RULE_RELPATH,
            "d8da3636222e18745faff03e8f7556c8c441f176",
            "33d2312455a87836f65b9ba26273a4188702b6c02da0ee2c1e1f4843fd10c8b8",
            List.copyOf(DEVIATIONS.keySet()));

    /** The tokens CAT applies: PATS's file minus {@link #DEVIATIONS}. */
    public static final List<String> RECEIPT_RULE_TOKENS = SEEDED.tokens();

    public ReceiptRule {
        tokens = List.copyOf(tokens);
        dropped = List.copyOf(dropped);
    }

    /**
     * The tokens in a rule file, in file order and without duplicates. Only the tokens are
     * returned; numeric entries are line numbers and are not this module's business. Tokens keep
     * their own spacing: {@code APP CRYPTOGRAM: } has a trailing space, and PATS matches it with
     * that space.
     */
    public static List<String> parseReceiptRuleText(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String line : text.split("\\R", -1)) {
            for (String entry : line.strip().split(",", -1)) {
                if (!entry.isEmpty() && !isNumeric(entry)) {
                    tokens.add(entry);
                }
            }
        }
        return List.copyOf(tokens);
    }

    private static boolean isNumeric(String entry) {
        return entry.chars().allMatch(Character::isDigit);
    }

    /** Whether the rule skips this line on its own: a token, a date or time, or a {@link #REPORT_LINE}. */
    public static boolean isIgnored(String line) {
        return isIgnored(line, RECEIPT_RULE_TOKENS);
    }

    /** As {@link #isIgnored(String)}, with the given tokens. */
    public static boolean isIgnored(String line, Collection<String> tokens) {
        for (String token : tokens) {
            if (line.contains(token)) {
                return true;
            }
        }
        return DATE_TIME.matcher(line).find()
                || MASKED_DATE.matcher(line).find()
                || REPORT_LINE.matcher(line).find();
    }

    /** For each line of {@code text}, whether the rule skips it. The timings rows need their section. */
    public static List<Boolean> ignoredLines(String text, Collection<String> tokens) {
        List<Boolean> ignored = new ArrayList<>();
        boolean inTimings = false;
        for (String line : text.split("\n", -1)) {
            if (SECTION_HEADER.matcher(line).lookingAt()) {
                inTimings = line.contains(TIMINGS_SECTION);
            }
            boolean timingRow = inTimings && line.chars().anyMatch(Character::isDigit);
            ignored.add(timingRow || isIgnored(line, tokens));
        }
        return ignored;
    }

    /** {@link #ignoredLines(String, Collection)} with the seeded tokens. */
    public static List<Boolean> ignoredLines(String text) {
        return ignoredLines(text, RECEIPT_RULE_TOKENS);
    }

    /** {@code text} with every ignored line removed, the rest in order and unchanged. */
    public static String applyRule(String text, Collection<String> tokens) {
        List<Boolean> ignored = ignoredLines(text, tokens);
        String[] lines = text.split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!ignored.get(i)) {
                kept.add(lines[i]);
            }
        }
        return String.join("\n", kept);
    }

    /** {@link #applyRule(String, Collection)} with the seeded tokens. */
    public static String applyRule(String text) {
        return applyRule(text, RECEIPT_RULE_TOKENS);
    }

    /** Where the drift check's live file is, or nothing if none resolves. */
    public static Optional<Path> resolveRulePath(Path explicit, Map<String, String> env) {
        return RuleFiles.resolve(explicit, env, ENV_RULE_PATH, ENV_CORPUS_ROOT, Path.of(RULE_RELPATH));
    }

    /** The result of comparing the seed with a live rule file. */
    public record SeedDrift(boolean checked, Path source, Set<String> added, Set<String> removed) {

        public SeedDrift {
            added = Collections.unmodifiableSet(new TreeSet<>(added));
            removed = Collections.unmodifiableSet(new TreeSet<>(removed));
        }

        /** Whether the seed agrees with the live file, apart from the recorded deviations. */
        public boolean inSync() {
            return added.isEmpty() && removed.isEmpty();
        }

        public String describe() {
            if (!checked) {
                return "RECEIPT-RULE.txt drift not checked: no live file reachable (set $"
                        + ENV_RULE_PATH + " or $" + ENV_CORPUS_ROOT + ").";
            }
            if (inSync()) {
                return "seed matches " + source + ", apart from the recorded deviations";
            }
            StringBuilder text = new StringBuilder("seed is stale against " + source + ":");
            if (!added.isEmpty()) {
                text.append("\n  tokens added upstream: ").append(String.join(", ", added));
            }
            if (!removed.isEmpty()) {
                text.append("\n  tokens removed upstream: ").append(String.join(", ", removed));
            }
            text.append("\n  update ReceiptRule.SEEDED by hand from the live file");
            return text.toString();
        }
    }

    /**
     * Compares {@link #SEEDED} with the live rule file, if one resolves. Returns
     * {@code checked = false} rather than failing when there is no checkout: a hosted CI runner
     * has none, and that is not a failure.
     */
    public static SeedDrift verifySeed(Path explicit, Map<String, String> env) {
        Optional<Path> source = resolveRulePath(explicit, env);
        if (source.isEmpty()) {
            return new SeedDrift(false, null, Set.of(), Set.of());
        }
        Set<String> live = new LinkedHashSet<>(parseReceiptRuleText(readText(source.get())));
        Set<String> seed = new LinkedHashSet<>(SEEDED.tokens());
        Set<String> added = new LinkedHashSet<>(live);
        added.removeAll(seed);
        added.removeAll(DEVIATIONS.keySet());
        Set<String> removed = new LinkedHashSet<>(seed);
        removed.removeAll(live);
        return new SeedDrift(true, source.get(), added, removed);
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
