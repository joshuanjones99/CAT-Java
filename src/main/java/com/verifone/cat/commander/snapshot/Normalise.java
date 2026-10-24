package com.verifone.cat.commander.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The canonical form two views of one area are compared in, and the lines a difference is reported as. A port of
 * {@code commander_snapshot.normalise}, {@code _lines}, {@code differences} and {@code digest}.
 *
 * <p>Element and attribute names by namespace URI, not prefix (so {@code ns0:} and {@code domain:} compare equal),
 * attributes sorted, whitespace-only text dropped (indentation), {@code VOLATILE} paths removed and
 * {@code UNORDERED} children sorted. Text with anything but whitespace in it is kept exactly. A reply that is not XML
 * ({@code vPLUs}) is compared as text with the whitespace between tags removed. The result is a comparison key, not
 * an XML document.
 *
 * <p>{@link #differences} is {@code difflib.unified_diff(n=0)}'s lines ({@link Difflib}), so a difference is shown
 * line for line as the Python CAT shows it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Normalise {

    /** Marks a comparison key made from text rather than XML, as the Python's {@code _TEXT_FORM}. */
    static final String TEXT_FORM = "text:";

    /** Lines of difference shown per area before "and N more". */
    public static final int DIFF_LIMIT = 12;

    private static final Pattern BETWEEN_TAGS = Pattern.compile(">\\s+<");
    private static final Map<String, Pattern> GLOBS = new ConcurrentHashMap<>();

    private Normalise() {}

    /** {@link #normalise(String, String, Rules)} with the committed tables. */
    public static String normalise(String area, String xml) {
        return normalise(area, xml, Rules.DEFAULT);
    }

    /** The comparison key for {@code xml}. */
    public static String normalise(String area, String xml, Rules rules) {
        Canon canon = canonical(area, xml, rules);
        return canon.tree == null ? TEXT_FORM + canon.text : XmlTree.serialise(canon.tree);
    }

    /** The canonical tree, or the text form of a reply that is not XML. */
    record Canon(XmlTree.Node tree, String text) {}

    static Canon canonical(String area, String xml, Rules rules) {
        List<String> volatilePaths = patterns(rules.volatilePaths(), area);
        List<String> unorderedPaths = patterns(rules.unordered(), area);
        XmlTree.Node root = XmlTree.parseOrNull(xml);
        if (root == null) {
            return new Canon(null, BETWEEN_TAGS.matcher(xml.strip()).replaceAll("><"));
        }
        prune(root, XmlTree.local(root.tag), volatilePaths);
        // A value equal to its entity default reads as absent (tlssite: no delete, so a restore writes defaults).
        Map<String, String> defaults = Tables.DEFAULTS.getOrDefault(Areas.baseArea(area), Map.of());
        root.children.removeIf(child -> {
            String fallback = defaults.get(XmlTree.local(child.tag));
            return fallback != null && fallback.equals(child.text == null ? "" : child.text);
        });
        if (!unorderedPaths.isEmpty()) {
            sortChildren(root, XmlTree.local(root.tag), unorderedPaths);
        }
        return new Canon(root, null);
    }

    /** The table entries for {@code area}: its base's, then its own when it is parameterised. */
    static List<String> patterns(Map<String, List<String>> table, String area) {
        String base = Areas.baseArea(area);
        List<String> out = new ArrayList<>(table.getOrDefault(base, List.of()));
        if (!area.equals(base)) {
            out.addAll(table.getOrDefault(area, List.of()));
        }
        return out;
    }

    /** Whether {@code path} matches any of the attribute ({@code @}) or element patterns. */
    static boolean matchesAny(String path, List<String> patterns, boolean attribute) {
        for (String pattern : patterns) {
            if (pattern.contains("@") == attribute && fnmatch(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static void prune(XmlTree.Node element, String path, List<String> volatilePaths) {
        if (element.text != null && element.text.strip().isEmpty()) {
            element.text = null;
        }
        if (element.tail != null && element.tail.strip().isEmpty()) {
            element.tail = null;
        }
        element.attrib.keySet().removeIf(name -> matchesAny(path + "@" + XmlTree.local(name), volatilePaths, true));
        element.children.removeIf(child -> matchesAny(path + "/" + XmlTree.local(child.tag), volatilePaths, false));
        for (XmlTree.Node child : element.children) {
            prune(child, path + "/" + XmlTree.local(child.tag), volatilePaths);
        }
    }

    private static void sortChildren(XmlTree.Node element, String path, List<String> unordered) {
        for (XmlTree.Node child : element.children) {
            sortChildren(child, path + "/" + XmlTree.local(child.tag), unordered);
        }
        if (unordered.stream().anyMatch(p -> fnmatch(path, p))) {
            // A stable sort on the serialised child, as Python's sorted(element, key=_serialise).
            element.children.sort((a, b) -> XmlTree.serialise(a).compareTo(XmlTree.serialise(b)));
        }
    }

    /** {@link #lines(String, String, Rules)} with the committed tables. */
    static List<String> lines(String area, String xml) {
        return lines(area, xml, Rules.DEFAULT);
    }

    /** One {@code path[@attr]=value} line per value of a view (normalised), for a readable diff. */
    static List<String> lines(String area, String xml, Rules rules) {
        Canon canon = canonical(area, xml, rules);
        if (canon.tree == null) {
            return splitLines(canon.text.replace("><", ">\n<"));
        }
        List<String> out = new ArrayList<>();
        walk(canon.tree, XmlTree.local(canon.tree.tag), out);
        return out;
    }

    private static void walk(XmlTree.Node element, String path, List<String> out) {
        element.attrib.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.add(path + "@" + XmlTree.local(e.getKey()) + "=" + e.getValue()));
        if (element.text != null && !element.text.isEmpty()) {
            out.add(path + "=" + element.text);
        } else if (element.children.isEmpty() && element.attrib.isEmpty()) {
            out.add(path);
        }
        for (XmlTree.Node child : element.children) {
            walk(child, path + "/" + XmlTree.local(child.tag), out);
            if (child.tail != null && !child.tail.isEmpty()) {
                out.add(path + "=" + child.tail);
            }
        }
    }

    /** Python's {@code str.splitlines()}: every line boundary it knows, no trailing empty line. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean boundary = c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c == '\u001c'
                    || c == '\u001d' || c == '\u001e' || c == '\u0085' || c == ' ' || c == ' ';
            if (!boundary) {
                i++;
                continue;
            }
            out.add(text.substring(start, i));
            i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
            start = i;
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** {@link #differences(String, String, String, int, Rules)} with the committed tables. */
    public static List<String> differences(String area, String saved, String now, int limit) {
        return differences(area, saved, now, limit, Rules.DEFAULT);
    }

    /** {@link #differences(String, String, String, int)} with the default limit. */
    public static List<String> differences(String area, String saved, String now) {
        return differences(area, saved, now, DIFF_LIMIT);
    }

    /**
     * The first {@code limit} lines of a diff between two views of {@code area} (normalised): the {@code -} and
     * {@code +} lines of {@code difflib.unified_diff(n=0)}, its {@code ---}, {@code +++} and {@code @@} lines
     * dropped (as the Python drops them: a value line that happens to start so goes too).
     */
    public static List<String> differences(String area, String saved, String now, int limit, Rules rules) {
        List<String> diff = new ArrayList<>();
        for (String line : Difflib.unifiedDiff(lines(area, saved, rules), lines(area, now, rules), "snapshot", "now")) {
            if (!line.startsWith("---") && !line.startsWith("+++") && !line.startsWith("@@")) {
                diff.add(line);
            }
        }
        if (diff.size() > limit) {
            List<String> shown = new ArrayList<>(diff.subList(0, limit));
            shown.add("... and " + (diff.size() - limit) + " more");
            return shown;
        }
        return diff;
    }

    /** SHA-256 of an area's comparison key, hex. */
    public static String digest(String area, String xml) {
        return sha256Hex(normalise(area, xml).getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of {@code bytes}, lower-case hex. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }

    /**
     * Python's {@code fnmatchcase}: {@code *} matches any run of characters (across {@code /}), {@code ?} one,
     * {@code [...]} a set ({@code [!...]} its complement), everything else literally.
     */
    static boolean fnmatch(String name, String pattern) {
        return GLOBS.computeIfAbsent(pattern, Normalise::compileGlob).matcher(name).matches();
    }

    private static Pattern compileGlob(String glob) {
        StringBuilder regex = new StringBuilder();
        int n = glob.length();
        int i = 0;
        while (i < n) {
            char c = glob.charAt(i++);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else if (c == '[') {
                int j = i;
                if (j < n && glob.charAt(j) == '!') {
                    j++;
                }
                if (j < n && glob.charAt(j) == ']') {
                    j++;
                }
                while (j < n && glob.charAt(j) != ']') {
                    j++;
                }
                if (j >= n) {
                    regex.append("\\[");
                    continue;
                }
                String set = glob.substring(i, j);
                i = j + 1;
                boolean negate = set.startsWith("!");
                if (negate) {
                    set = set.substring(1);
                }
                StringBuilder cls = new StringBuilder("[");
                if (negate) {
                    cls.append('^');
                }
                for (int k = 0; k < set.length(); k++) {
                    char s = set.charAt(k);
                    if (s == '-' && k > 0 && k < set.length() - 1) {
                        cls.append('-');
                    } else if (Character.isLetterOrDigit(s)) {
                        cls.append(s);
                    } else {
                        cls.append('\\').append(s);
                    }
                }
                regex.append(cls).append(']');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
