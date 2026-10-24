package com.verifone.cat.collect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which petroapps files a case takes, and which are CAT's (the selection half of {@code petrotest.collect}).
 *
 * <p>Paths are casefolded so the two TestCases trees ({@code testcases/} and {@code TestCases/}) form the same union
 * Windows sees. Every function here is pure over the index {@link Collect} reads from git; none touches git or disk.
 * Folder maps are keyed by casefolded path, and each value is the blobs at that name (more than one when the two
 * trees both hold it).
 *
 * <p>Java port UNVERIFIED AGAINST HARDWARE; the logic is tested against fixture repositories built from git objects.
 */
public final class Selection {

    /** Where the petroapps case folders live, casefolded. */
    static final String CASES = "automationtests/testcases";

    /** An authored case's file (DESIGN.md 5.6): CAT's, never PATS's, despite matching {@code *-TestCase.*}. */
    static final String FEATURE_SUFFIX = "-testcase.feature";

    /**
     * CAT's own baselines, one per PATS reference: {@code -CATReceipt*.txt}, {@code -1-CATReceipt.txt},
     * {@code -CATTlog.xml}, {@code -2-CATTlog.xml}, a simulator packet's {@code -<packet>-<DEVICE>-CATPacket.xml}, and
     * an EPS report's {@code -CATReport.xml}. Matched against the part of a name after the case key, casefolded.
     */
    private static final Pattern CAT_BASELINE = Pattern.compile(
            "(-\\d+)?-(catreceipt.*\\.txt|cattlog\\.xml|catreport\\.xml|[\\w-]+-catpacket\\.xml)");

    /** The one TLog baseline shape PATS and CAT share, after the case key. */
    private static final String TLOG_KIND = "-tlog.xml";

    private Selection() {}

    /**
     * Python's {@code str.casefold}, approximated as upper-then-lower. The same for every name this harness sees (ASCII
     * test names), and what NTFS's case folding is for them.
     */
    public static String casefold(String text) {
        return text.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /** Is a case-folder file name CAT's test: a {@code .py}, or an authored {@code -TestCase.feature}? */
    public static boolean isCatTest(String name) {
        String low = casefold(name);
        return low.endsWith(".py") || low.endsWith(FEATURE_SUFFIX);
    }

    /** {@code PETROCORE-61338-TestCase.py} or {@code .feature} gives {@code PETROCORE-61338}; else the stem. */
    public static String caseOf(String testName) {
        int dot = testName.lastIndexOf('.');
        String stem = dot < 0 ? testName : testName.substring(0, dot);
        String tail = "-testcase";
        return casefold(stem).endsWith(tail) ? stem.substring(0, stem.length() - tail.length()) : stem;
    }

    /**
     * Is {@code name} a baseline belonging to {@code caseKey}? {@code PETROCORE-61338-Tlog.xml},
     * {@code PETROCORE-61338-CATReceipt*.txt} and CAT's numbered and TLog baselines are; PATS's own {@code -Receipt.txt},
     * {@code -Rule.txt} and {@code -TestCase.xml} are not.
     */
    public static boolean isBaseline(String name, String caseKey) {
        String low = casefold(name);
        String stem = casefold(caseKey);
        if (!low.startsWith(stem)) {
            return false;
        }
        String tail = low.substring(stem.length());
        return tail.equals(TLOG_KIND) || CAT_BASELINE.matcher(tail).matches();
    }

    /** Files below {@code base} (casefolded), keyed by their casefolded path relative to it. */
    static Map<String, List<Blob>> under(Map<String, List<Blob>> files, String base) {
        if (base.isEmpty()) {
            return files;
        }
        String prefix = casefold(stripSlashes(base)) + "/";
        Map<String, List<Blob>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Blob>> e : files.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    /** Relative paths in {@code folder} matching {@code pattern} on their basename; sorted. */
    static List<String> matches(Map<String, List<Blob>> folder, String pattern, boolean recursive) {
        Pattern regex = fnmatch(casefold(pattern));
        List<String> hits = new ArrayList<>();
        for (String rel : folder.keySet()) {
            String basename = rel.substring(rel.lastIndexOf('/') + 1);
            if ((recursive || !rel.contains("/")) && regex.matcher(basename).matches()) {
                hits.add(rel);
            }
        }
        Collections.sort(hits);
        return hits;
    }

    /**
     * Relative paths {@code rule} copies from the case folder, or {@code null} if PATS finds none.
     *
     * <p>For a PATS rule CAT's test is never a hit: {@code <ID>-TestCase.py} or {@code <ID>-TestCase.feature}, which
     * {@code *-TestCase.*} would otherwise claim as PATS's own case file. The rule that pulls the test itself passes
     * {@code dropCatTests=false}.
     */
    static List<String> apply(Rule rule, Map<String, List<Blob>> caseFolder, String dist, boolean dropCatTests) {
        for (String where : rule.order()) {
            String base = "dist".equals(where) ? dist : "";
            String checked = rule.check() == null || rule.check().isEmpty() ? rule.subdir() : rule.check();
            Map<String, List<Blob>> check = under(caseFolder, join(base, checked));
            boolean found = rule.wholeDir()
                    ? !check.isEmpty()
                    : !keep(matches(check, rule.pattern(), false), dropCatTests).isEmpty();
            if (found) {
                String sourceBase = casefold(join(base, rule.subdir()));
                Map<String, List<Blob>> source = under(caseFolder, sourceBase);
                List<String> out = new ArrayList<>();
                for (String rel : keep(matches(source, rule.pattern(), rule.recursive()), dropCatTests)) {
                    out.add(join(sourceBase, rel));
                }
                return out;
            }
        }
        return null;
    }

    /** The petroapps index partitioned by case folder: case key (casefolded) to that folder's relative files. */
    static Map<String, Map<String, List<Blob>>> caseFolders(Map<String, List<Blob>> index) {
        Map<String, Map<String, List<Blob>>> folders = new LinkedHashMap<>();
        String prefix = CASES + "/";
        for (Map.Entry<String, List<Blob>> e : index.entrySet()) {
            String path = e.getKey();
            if (path.startsWith(prefix)) {
                String rest = path.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    folders.computeIfAbsent(rest.substring(0, slash), k -> new LinkedHashMap<>())
                            .put(rest.substring(slash + 1), e.getValue());
                }
            }
        }
        return folders;
    }

    /** Each shared asset's file count: top level only, or every file below it when recursive. */
    static List<CollectResult.Asset> sharedAssets(Map<String, List<Blob>> index) {
        List<CollectResult.Asset> results = new ArrayList<>();
        for (Rules.SharedAsset asset : Rules.SHARED_ASSETS) {
            Map<String, List<Blob>> below = under(index, asset.source());
            int count = 0;
            if (asset.recursive()) {
                count = below.size();
            } else {
                for (String rel : below.keySet()) {
                    if (!rel.contains("/")) {
                        count++;
                    }
                }
            }
            results.add(new CollectResult.Asset(asset.source(), asset.dest(), count));
        }
        return results;
    }

    /** The parent folder of a relative path, {@code ""} at the top. */
    static String parentOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    /** The last segment of a path. */
    static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static List<String> keep(List<String> hits, boolean dropCatTests) {
        if (!dropCatTests) {
            return hits;
        }
        List<String> kept = new ArrayList<>();
        for (String rel : hits) {
            if (!isCatTest(rel)) {
                kept.add(rel);
            }
        }
        return kept;
    }

    /** {@code _join}: the non-empty parts joined with {@code /}. */
    static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (out.length() > 0) {
                    out.append('/');
                }
                out.append(part);
            }
        }
        return out.toString();
    }

    private static String stripSlashes(String base) {
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        return base.substring(0, end);
    }

    /**
     * {@code fnmatchcase} for the patterns {@link Rules} uses: {@code *} and {@code ?}, everything else literal.
     */
    static Pattern fnmatch(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
