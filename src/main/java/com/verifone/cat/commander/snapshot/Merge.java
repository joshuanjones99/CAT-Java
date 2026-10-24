package com.verifone.cat.commander.snapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merge restore: a snapshot of another build written into this build's own view. A port of
 * {@code commander_snapshot.merge_document} and its pairing helpers.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no merged document has been sent to a Commander, nor a re-serialised one
 * (its own prefixes kept, its layout not).
 */
public final class Merge {

    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private Merge() {}

    /** A view the merge cannot pair element for element; the area is reported, not sent. */
    public static final class NotMergeable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotMergeable(String message) {
            super(message);
        }
    }

    /**
     * The document to send, and what it left as it was.
     *
     * @param xml   the merged view
     * @param notes snapshot values with nowhere to go on this build ("dropped"), and this build's own items the
     *              snapshot never had ("kept"); notes, not differences
     */
    public record Merged(String xml, List<String> notes) {}

    /**
     * The Commander's own view {@code live}, with the snapshot's values written into every path both documents have.
     *
     * <ul>
     * <li>An element or attribute only in {@code live} is kept untouched and never compared; one only in
     * {@code saved} is dropped and noted.</li>
     * <li>A leaf's text and a shared attribute take the snapshot's value; {@code VOLATILE} paths keep the
     * Commander's.</li>
     * <li>Repeated siblings pair by a key ({@link Tables#KEY_NAMES}); unkeyed, only the same count pairs by position,
     * and under an {@code UNORDERED} path they must already be the same set. Anything else raises
     * {@link NotMergeable}.</li>
     * </ul>
     *
     * @throws NotMergeable a document is not XML, the roots differ, or siblings cannot be paired
     */
    public static Merged mergeDocument(String area, String saved, String live) {
        return mergeDocument(area, saved, live, Rules.DEFAULT);
    }

    /** {@link #mergeDocument(String, String, String)} under {@code rules}. */
    public static Merged mergeDocument(String area, String saved, String live, Rules rules) {
        XmlTree.Node snapRoot;
        XmlTree.Node liveRoot;
        try {
            snapRoot = XmlTree.parse(saved);
            liveRoot = XmlTree.parse(live);
        } catch (XmlTree.NotXml e) {
            throw new NotMergeable("not XML: " + e.getMessage());
        }
        if (!snapRoot.tag.equals(liveRoot.tag)) {
            throw new NotMergeable("root " + XmlTree.local(snapRoot.tag) + " is "
                    + XmlTree.local(liveRoot.tag) + " now");
        }
        List<String> volatilePaths = Normalise.patterns(rules.volatilePaths(), area);
        List<String> unorderedPaths = Normalise.patterns(rules.unordered(), area);
        List<String> notes = new ArrayList<>();
        mergeInto(snapRoot, liveRoot, XmlTree.local(liveRoot.tag), volatilePaths, unorderedPaths, notes);
        String head = live.strip().startsWith("<?xml") ? DECLARATION : "";
        return new Merged(head + XmlTree.toXml(liveRoot, XmlTree.prefixes(live)), List.copyOf(notes));
    }

    private static void mergeInto(XmlTree.Node snap, XmlTree.Node live, String path, List<String> volatilePaths,
            List<String> unorderedPaths, List<String> notes) {
        for (Map.Entry<String, String> attr : snap.attrib.entrySet()) {
            String where = path + "@" + XmlTree.local(attr.getKey());
            if (volatilePaths.stream().filter(p -> p.contains("@")).anyMatch(p -> Normalise.fnmatch(where, p))) {
                continue;
            }
            if (live.attrib.containsKey(attr.getKey())) {
                live.attrib.put(attr.getKey(), attr.getValue());
            } else {
                notes.add("dropped " + where + "=" + attr.getValue() + " (not on this build)");
            }
        }
        if (snap.children.isEmpty() && live.children.isEmpty()) {
            String savedText = meaningful(snap.text);
            String liveText = meaningful(live.text);
            if (!java.util.Objects.equals(savedText, liveText)) {
                live.text = snap.text == null ? "" : snap.text;
            }
            return;
        }
        Map<String, List<XmlTree.Node>> snapGroups = groups(snap);
        Map<String, List<XmlTree.Node>> liveGroups = groups(live);
        for (Map.Entry<String, List<XmlTree.Node>> group : snapGroups.entrySet()) {
            String childPath = path + "/" + XmlTree.local(group.getKey());
            if (volatilePaths.stream().filter(p -> !p.contains("@")).anyMatch(p -> Normalise.fnmatch(childPath, p))) {
                continue;
            }
            List<XmlTree.Node> liveItems = liveGroups.get(group.getKey());
            if (liveItems == null || liveItems.isEmpty()) {
                notes.add("dropped " + childPath + " (not on this build)");
                continue;
            }
            for (XmlTree.Node[] pair : pair(group.getValue(), liveItems, childPath, path, unorderedPaths, notes)) {
                mergeInto(pair[0], pair[1], childPath, volatilePaths, unorderedPaths, notes);
            }
        }
    }

    /** Text with anything but whitespace in it, else null: how the leaf comparison reads an empty value. */
    private static String meaningful(String text) {
        return text != null && !text.isBlank() ? text : null;
    }

    private static Map<String, List<XmlTree.Node>> groups(XmlTree.Node element) {
        Map<String, List<XmlTree.Node>> out = new LinkedHashMap<>();
        for (XmlTree.Node child : element.children) {
            out.computeIfAbsent(child.tag, k -> new ArrayList<>()).add(child);
        }
        return out;
    }

    private static List<XmlTree.Node[]> pair(List<XmlTree.Node> saved, List<XmlTree.Node> live, String childPath,
            String parentPath, List<String> unorderedPaths, List<String> notes) {
        if (saved.size() == 1 && live.size() == 1) {
            return java.util.Collections.singletonList(new XmlTree.Node[] {saved.get(0), live.get(0)});
        }
        String key = siblingKey(saved, live);
        if (key != null) {
            Map<String, XmlTree.Node> liveBy = new LinkedHashMap<>();
            for (XmlTree.Node e : live) {
                liveBy.put(keyValue(e, key), e);
            }
            List<XmlTree.Node[]> pairs = new ArrayList<>();
            for (XmlTree.Node item : saved) {
                String value = keyValue(item, key);
                if (liveBy.containsKey(value)) {
                    pairs.add(new XmlTree.Node[] {item, liveBy.remove(value)});
                } else {
                    notes.add("dropped " + childPath + "[" + key + "=" + value + "] (not on this build)");
                }
            }
            for (String value : liveBy.keySet()) {
                notes.add("kept " + childPath + "[" + key + "=" + value + "] (only on this build)");
            }
            return pairs;
        }
        if (unorderedPaths.stream().anyMatch(p -> Normalise.fnmatch(parentPath, p))) {
            if (sortedSerials(saved).equals(sortedSerials(live))) {
                return List.of();
            }
            throw new NotMergeable(childPath + ": unordered, unkeyed and not the same set");
        }
        if (saved.size() != live.size()) {
            throw new NotMergeable(childPath + ": " + saved.size() + " in the snapshot, " + live.size()
                    + " now, no key");
        }
        List<XmlTree.Node[]> pairs = new ArrayList<>();
        for (int i = 0; i < saved.size(); i++) {
            pairs.add(new XmlTree.Node[] {saved.get(i), live.get(i)});
        }
        return pairs;
    }

    private static List<String> sortedSerials(List<XmlTree.Node> elements) {
        List<String> out = new ArrayList<>();
        for (XmlTree.Node e : elements) {
            out.add(XmlTree.serialise(e));
        }
        out.sort(null);
        return out;
    }

    /** The value that names a sibling: its attribute or leaf child, stripped; null when empty. */
    static String keyValue(XmlTree.Node element, String key) {
        for (Map.Entry<String, String> attr : element.attrib.entrySet()) {
            if (XmlTree.local(attr.getKey()).equals(key)) {
                String value = attr.getValue().strip();
                return value.isEmpty() ? null : value;
            }
        }
        for (XmlTree.Node child : element.children) {
            if (XmlTree.local(child.tag).equals(key) && child.children.isEmpty()) {
                String value = (child.text == null ? "" : child.text).strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /** The first key every sibling has, unique on both sides; null when none. */
    static String siblingKey(List<XmlTree.Node> saved, List<XmlTree.Node> live) {
        for (String key : Tables.KEY_NAMES) {
            if (uniqueKeys(saved, key) && uniqueKeys(live, key)) {
                return key;
            }
        }
        return null;
    }

    private static boolean uniqueKeys(List<XmlTree.Node> group, String key) {
        Set<String> seen = new HashSet<>();
        for (XmlTree.Node e : group) {
            String value = keyValue(e, key);
            if (value == null || !seen.add(value)) {
                return false;
            }
        }
        return true;
    }
}
