package com.verifone.cat.plans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Partial-run selection in plan order, and the state prerequisites a partial run omits.
 *
 * <p>A whole plan is the default (rig owner, 2026-09-26). {@code fromCase} is PATS's Continue:
 * that case and every one after it. {@code only} is PATS's Run: those cases. Then the tag
 * filter, a Cucumber tag expression. Plan-end reports stay valid only after a whole, ordered
 * plan. Ported from {@code petrotest.selection}.
 *
 * <p>Not ported: {@code file_actions}, which reads PATS {@code -TestCase.xml} and phrase-written
 * {@code .feature} files through the PATS parsers. Prerequisite detection takes that as a
 * {@link CaseActions} the caller supplies, so the detection is the same with any reader.
 * UNVERIFIED AGAINST HARDWARE: the detection is a heuristic over case files, not a run.
 */
public final class PartialRun {

    /** PATS actions that leave the rig changed for the cases after them, and what they do. */
    public static final Map<String, String> STATE_ACTIONS = Map.of(
            "IMPORT_CONFIGURATION", "imports Commander configuration",
            "UPDATE_CONFIGURATION", "changes a configuration or simulator setting",
            "CONTROL_FUNCTION", "re-initialises a Commander driver",
            "CLOSE_REPORT", "closes a period",
            "LOGIN", "signs a cashier on",
            "CASHIER_LOGIN", "signs a cashier on",
            "LOGOUT", "signs the cashier off",
            "SELECT_TILL", "selects a till",
            "ENTER_DRAWER_AMNT", "opens a drawer",
            "STORE_TRANS_MAP", "stores a transaction for a later refund");

    private static final Pattern KEY_FILE = Pattern.compile("(PETROCORE-\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCENARIO = Pattern.compile("(Scenario|Scenario Outline|Example):");
    private static final Pattern TAG_TOKEN = Pattern.compile("\\s*(\\(|\\)|[^\\s()]+)");

    private PartialRun() {}

    /**
     * The PATS action names a case file says, upper-cased, or empty when they cannot be read.
     * A case that cannot be read is counted in the warnings, not judged.
     */
    @FunctionalInterface
    public interface CaseActions {
        Optional<Set<String>> namesIn(String node);
    }

    /** {@code 36847} and {@code petrocore-36847} both become {@code PETROCORE-36847}. */
    public static String normaliseKey(String text) {
        String trimmed = text.strip();
        boolean digits = !trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit);
        return digits ? "PETROCORE-" + trimmed : trimmed.toUpperCase(Locale.ROOT);
    }

    // -- Cucumber tag expressions ------------------------------------------------------------

    /**
     * A Cucumber tag expression ({@code @a and not (@b or @c)}) as a predicate on a tag set.
     * {@code not} binds tighter than {@code and}, and {@code and} tighter than {@code or}.
     * Comma-separated parts are ANDed, since PATS passes each as its own {@code --tags}. An
     * empty expression matches everything; a tag may be written without its {@code @}.
     *
     * @throws TagExpressionError when the expression does not parse
     */
    public static Predicate<Set<String>> parseTags(String expression) {
        List<Predicate<Set<String>>> predicates = new ArrayList<>();
        for (String part : expression.split(",")) {
            if (!part.isBlank()) {
                predicates.add(parseOne(part.strip()));
            }
        }
        return tags -> predicates.stream().allMatch(p -> p.test(tags));
    }

    private static Predicate<Set<String>> parseOne(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher match = TAG_TOKEN.matcher(text);
        while (match.find()) {
            tokens.add(match.group(1));
        }
        TagParser parser = new TagParser(text, tokens);
        Predicate<Set<String>> predicate = parser.or();
        if (parser.position != tokens.size()) {
            throw new TagExpressionError("'" + text + "': unexpected '" + tokens.get(parser.position) + "'");
        }
        return predicate;
    }

    /** Recursive descent over the tokens: or, then and, then not, then a tag or a group. */
    private static final class TagParser {
        private final String text;
        private final List<String> tokens;
        int position;

        TagParser(String text, List<String> tokens) {
            this.text = text;
            this.tokens = tokens;
        }

        private String peek() {
            return position < tokens.size() ? tokens.get(position) : null;
        }

        private String take() {
            if (position >= tokens.size()) {
                throw new TagExpressionError("'" + text + "': ends where a tag was expected");
            }
            return tokens.get(position++);
        }

        Predicate<Set<String>> or() {
            Predicate<Set<String>> left = and();
            while ("or".equals(peek())) {
                take();
                left = left.or(and());
            }
            return left;
        }

        private Predicate<Set<String>> and() {
            Predicate<Set<String>> left = not();
            while ("and".equals(peek())) {
                take();
                left = left.and(not());
            }
            return left;
        }

        private Predicate<Set<String>> not() {
            if ("not".equals(peek())) {
                take();
                return not().negate();
            }
            String token = take();
            if ("(".equals(token)) {
                Predicate<Set<String>> inner = or();
                if (!")".equals(take())) {
                    throw new TagExpressionError("'" + text + "': a '(' is not closed");
                }
                return inner;
            }
            if (")".equals(token) || "and".equals(token) || "or".equals(token)) {
                throw new TagExpressionError("'" + text + "': '" + token + "' where a tag was expected");
            }
            String tag = token.startsWith("@") ? token : "@" + token;
            return tags -> tags.contains(tag);
        }
    }

    // -- a case file's tags ------------------------------------------------------------------

    /**
     * Each scenario's tags (its own and its feature's), or the file's Jira key alone. A
     * {@code .feature}'s tags are the {@code @} lines above {@code Feature:} and above each
     * {@code Scenario}; any other case file carries only its key, taken from its name.
     */
    public static List<Set<String>> fileTags(Path file) {
        String name = file.getFileName().toString();
        Set<String> own = new HashSet<>();
        Matcher key = KEY_FILE.matcher(name);
        if (key.find()) {
            own.add("@" + key.group(1).toUpperCase(Locale.ROOT));
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".feature")) {
            return List.of(own);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of(own);
        }
        Set<String> feature = new HashSet<>(own);
        Set<String> pending = new HashSet<>();
        List<Set<String>> scenarios = new ArrayList<>();
        boolean inFeature = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("@")) {
                for (String token : line.split("\\s+")) {
                    if (token.startsWith("@")) {
                        pending.add(token);
                    }
                }
            } else if (line.startsWith("Feature:")) {
                feature.addAll(pending);
                pending.clear();
                inFeature = true;
            } else if (inFeature && SCENARIO.matcher(line).lookingAt()) {
                Set<String> scenario = new HashSet<>(feature);
                scenario.addAll(pending);
                scenarios.add(scenario);
                pending.clear();
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                pending.clear();
            }
        }
        return scenarios.isEmpty() ? List.of(feature) : scenarios;
    }

    // -- the selection ------------------------------------------------------------------------

    /**
     * The plan's cases a partial run keeps, in plan order.
     *
     * <p>{@code fromCase} first, then {@code only}, then the tag filter. A key not in the plan,
     * or a selection that keeps nothing, throws {@link SelectionError} naming it: a partial run
     * of nothing is not a run anyone asked for.
     *
     * @param plan the plan's cases in plan order; a case with no node is Not Run and is dropped
     * @param repoRoot the checkout the tag filter reads each node's file from
     */
    public static Selection select(
            List<CaseRef> plan,
            String tags,
            Collection<String> only,
            String fromCase,
            Path repoRoot) {
        return select(plan, tags, only, fromCase, node -> repoRoot.resolve(fileOf(node)));
    }

    /**
     * {@link #select(List, String, Collection, String, Path)}, with each node's file given by {@code fileOf}: a run's
     * cases can sit in more than one tree (the hand-migrated suites and the pulled ones).
     */
    public static Selection select(
            List<CaseRef> plan,
            String tags,
            Collection<String> only,
            String fromCase,
            java.util.function.Function<String, Path> fileOf) {
        List<CaseRef> ordered = plan.stream()
                .filter(c -> c.node() != null && !c.node().isEmpty())
                .distinct()
                .toList();
        List<String> keys = ordered.stream().map(CaseRef::key).toList();
        Set<String> keySet = new HashSet<>(keys);
        List<CaseRef> kept = ordered;
        if (fromCase != null && !fromCase.isBlank()) {
            String start = normaliseKey(fromCase);
            if (!keySet.contains(start)) {
                throw new SelectionError("--from " + start + ": not a migrated case of the plan");
            }
            kept = ordered.subList(keys.indexOf(start), ordered.size());
        }
        Set<String> wanted = new TreeSet<>();
        for (String key : only) {
            if (!key.isBlank()) {
                wanted.add(normaliseKey(key));
            }
        }
        if (!wanted.isEmpty()) {
            List<String> missing = wanted.stream().filter(k -> !keySet.contains(k)).toList();
            if (!missing.isEmpty()) {
                throw new SelectionError("--only " + String.join(", ", missing)
                        + ": not migrated case(s) of the plan");
            }
            kept = kept.stream().filter(c -> wanted.contains(c.key())).toList();
        }
        if (tags != null && !tags.isBlank()) {
            Predicate<Set<String>> predicate = parseTags(tags);
            kept = kept.stream()
                    .filter(c -> fileTags(fileOf.apply(c.node())).stream().anyMatch(predicate))
                    .toList();
        }
        if (kept.isEmpty()) {
            List<String> asked = new ArrayList<>();
            if (fromCase != null && !fromCase.isBlank()) {
                asked.add("--from " + fromCase);
            }
            if (!wanted.isEmpty()) {
                asked.add("--only " + String.join(" ", wanted));
            }
            if (tags != null && !tags.isBlank()) {
                asked.add("--tags '" + tags + "'");
            }
            throw new SelectionError(String.join(" ", asked) + ": no case of the plan is selected");
        }
        CaseRef last = kept.get(kept.size() - 1);
        Set<CaseRef> keptSet = new HashSet<>(kept);
        List<CaseRef> skipped = new ArrayList<>();
        for (CaseRef pair : ordered.subList(0, ordered.indexOf(last))) {
            if (!keptSet.contains(pair)) {
                skipped.add(pair);
            }
        }
        return new Selection(kept, skipped, ordered);
    }

    /** The file part of a node: {@code tests/a.py::test_x} names {@code tests/a.py}. */
    private static String fileOf(String node) {
        int at = node.indexOf("::");
        return at < 0 ? node : node.substring(0, at);
    }

    // -- prerequisites -----------------------------------------------------------------------

    /**
     * WARNING lines for a partial run: what it leaves out that the cases after it depend on.
     *
     * @param planEnd says whether a selected node is a {@code @plan_end} report case; may be null
     */
    public static List<String> prerequisiteWarnings(
            Selection selection, CaseActions actions, Predicate<String> planEnd) {
        List<String> lines = new ArrayList<>();
        lines.add("partial run: " + selection.pairs().size() + " case(s) selected; the plan's other "
                + "cases do not run, and cases change rig state for the cases after them (rig owner, "
                + "2026-09-26) — a verdict here may differ from the same case's in a whole run");
        Prerequisites found = prerequisiteCases(selection, actions);
        for (Prerequisite prerequisite : found.cases()) {
            lines.add("prerequisite not run: " + prerequisite.key() + " (" + prerequisite.node() + ") "
                    + String.join("; ", prerequisite.does()));
        }
        if (!found.stores().isEmpty() && !found.refunds().isEmpty()) {
            lines.add("prerequisite not run: " + String.join(", ", found.refunds()) + " refund(s) a transaction that "
                    + String.join(", ", found.stores()) + " store(s), and those do not run");
        }
        if (found.unreadable() > 0) {
            lines.add(found.unreadable() + " case(s) left out ahead of the selection have no PATS actions "
                    + "CAT can read; what they change is not known");
        }
        if (planEnd != null) {
            for (CaseRef pair : selection.pairs()) {
                if (planEnd.test(pair.node())) {
                    lines.add(pair.key() + " is a @plan_end case: it is judged only in a whole, in-order run "
                            + "of its plan, so this run skips it");
                }
            }
        }
        return lines;
    }

    /**
     * The cases a selection leaves out ahead of itself that change rig state, as data. The one
     * detection behind {@link #prerequisiteWarnings} and the Run scope that adds these cases to
     * a partial run by default, so the two cannot disagree about what a selection needs.
     */
    public static Prerequisites prerequisiteCases(Selection selection, CaseActions actions) {
        int unreadable = 0;
        List<String> stores = new ArrayList<>();
        List<Found> found = new ArrayList<>();
        for (CaseRef pair : selection.skippedBefore()) {
            Optional<Set<String>> own = actions.namesIn(pair.node());
            if (own.isEmpty()) {
                unreadable++;
                continue;
            }
            TreeSet<String> does = new TreeSet<>();
            for (String name : own.get()) {
                if (STATE_ACTIONS.containsKey(name)) {
                    does.add(STATE_ACTIONS.get(name));
                }
            }
            boolean storesOne = own.get().contains("STORE_TRANS_MAP");
            if (storesOne) {
                stores.add(pair.key());
            }
            if (!does.isEmpty()) {
                found.add(new Found(pair, List.copyOf(does), storesOne));
            }
        }
        List<CaseRef> refunds = new ArrayList<>();
        if (!stores.isEmpty()) {
            for (CaseRef pair : selection.pairs()) {
                Optional<Set<String>> names = actions.namesIn(pair.node());
                if (names.isPresent() && names.get().contains("REFUND_WITH_TRANS_MAP")) {
                    refunds.add(pair);
                }
            }
        }
        // Plan positions say which selected case comes after a left-out one. A Selection built
        // without `order` reads as one whose selected cases all come after.
        Map<CaseRef, Integer> position = new LinkedHashMap<>();
        for (CaseRef pair : selection.order()) {
            position.putIfAbsent(pair, position.size());
        }
        int absent = position.size();
        List<Integer> selectedPositions = new ArrayList<>();
        for (CaseRef pair : selection.pairs()) {
            selectedPositions.add(position.getOrDefault(pair, absent));
        }
        List<Integer> refundPositions = new ArrayList<>();
        for (CaseRef pair : refunds) {
            refundPositions.add(position.getOrDefault(pair, absent));
        }
        List<Prerequisite> cases = new ArrayList<>();
        for (Found item : found) {
            int at = position.getOrDefault(item.pair(), -1);
            int nextSelected = upperBound(selectedPositions, at);
            int nextRefund = upperBound(refundPositions, at);
            CaseRef refund = item.stores() && nextRefund < refunds.size() ? refunds.get(nextRefund) : null;
            CaseRef neededBy = refund != null ? refund : selection.pairs().get(
                    nextSelected < selection.pairs().size() ? nextSelected : 0);
            cases.add(new Prerequisite(item.pair().key(), item.pair().node(), item.does(), neededBy.key()));
        }
        return new Prerequisites(
                cases,
                stores,
                refunds.stream().map(CaseRef::key).toList(),
                unreadable);
    }

    /** Index of the first element greater than {@code value}: Python's {@code bisect_right}. */
    private static int upperBound(List<Integer> sorted, int value) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted.get(mid) <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private record Found(CaseRef pair, List<String> does, boolean stores) {}

    /** A case a partial run left out, ahead of a selected case, that changes rig state. */
    public record Prerequisite(
            String key,
            String node,
            /** What it does that the cases after it may depend on, sorted. */
            List<String> does,
            /** The selected case it comes before: a refund, for a case that stores a transaction. */
            String neededBy) {}

    /**
     * {@link #prerequisiteCases}' answer.
     *
     * @param cases in plan order
     * @param stores left-out cases that store a transaction
     * @param refunds selected cases that refund one (read only when {@code stores} is not empty)
     * @param unreadable left-out cases ahead of the selection whose actions cannot be read
     */
    public record Prerequisites(
            List<Prerequisite> cases,
            List<String> stores,
            List<String> refunds,
            int unreadable) {

        public Prerequisites {
            cases = List.copyOf(cases);
            stores = List.copyOf(stores);
            refunds = List.copyOf(refunds);
        }
    }
}
