package com.verifone.cat.commander.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code restore} sends for an area: the snapshot's view, adjusted where a plain {@code u<area>} would leave
 * records behind. A port of {@code restore_document}, {@code restore_documents} and their string helpers.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): none of these documents has been sent to a Commander.
 */
public final class Restores {

    private static final Pattern TABLE_ACTION = Pattern.compile("(<(?:\\w+:)?TableAction\\s+type=\")update(\")");
    private static final Pattern ROOT_OPEN = Pattern.compile("<(?!\\?)([\\w:.-]+)((?:\\s[^>]*?)?)(/?)>");
    private static final Pattern SITE = Pattern.compile("\\s*<site>[^<]*</site>");
    private static final String EMPTY = "<operation type=\"empty\"/>";

    private Restores() {}

    /**
     * {@code saved} as {@code restore} sends it. A NAXML maintenance dataset's {@code TableAction} reads
     * {@code initialize} rather than {@code update}, which adds and changes but never removes a record.
     */
    public static String restoreDocument(String area, String saved) {
        if (!Areas.baseArea(area).equals("Maintenance")) {
            return saved;
        }
        return TABLE_ACTION.matcher(saved).replaceFirst("$1initialize$2");
    }

    /** Every document {@code restore} sends for {@code area}, in order. */
    public static List<String> restoreDocuments(String area, String saved) {
        String name = Areas.baseArea(area);
        if (Tables.EMPTY_FIRST.containsKey(name)) {
            return List.of(insideRoot(saved, Tables.EMPTY_FIRST.get(name)));
        }
        if (Tables.EMPTY_IN_TABLE.containsKey(name)) {
            return List.of(emptyInTable(saved, Tables.EMPTY_IN_TABLE.get(name)));
        }
        if (Tables.EMPTY_ALONE.containsKey(name)) {
            List<String> out = new ArrayList<>();
            out.add(insideRoot(emptyRoot(saved), Tables.EMPTY_ALONE.get(name)));
            if (hasRecords(saved)) {
                out.add(saved);
            }
            return out;
        }
        if (Tables.DEFAULTS.containsKey(name)) {
            StringBuilder missing = new StringBuilder();
            for (Map.Entry<String, String> entry : Tables.DEFAULTS.get(name).entrySet()) {
                if (!saved.contains("<" + entry.getKey() + ">")) {
                    missing.append('<').append(entry.getKey()).append('>').append(entry.getValue())
                            .append("</").append(entry.getKey()).append('>');
                }
            }
            return List.of(missing.length() == 0 ? saved : insideRoot(saved, missing.toString()));
        }
        return List.of(restoreDocument(area, saved));
    }

    /**
     * {@code saved} with {@code inner} placed first inside its root element, after {@code <site>} (which the views put
     * first and the handlers ignore). A self-closing root is opened to hold it.
     */
    static String insideRoot(String saved, String inner) {
        Matcher root = ROOT_OPEN.matcher(saved);
        if (!root.find()) {
            throw new IllegalArgumentException("no root element");
        }
        String tag = root.group(1);
        String attrs = root.group(2);
        if (!root.group(3).isEmpty()) {
            return saved.substring(0, root.start()) + "<" + tag + attrs + ">" + inner + "</" + tag + ">"
                    + saved.substring(root.end());
        }
        Matcher site = SITE.matcher(saved);
        site.region(root.end(), saved.length());
        int at = site.lookingAt() ? site.end() : root.end();
        return saved.substring(0, at) + inner + saved.substring(at);
    }

    /**
     * {@code saved} with an empty operation first inside its {@code table} element, which is added after {@code <site>}
     * when the snapshot has none, so an empty table stays empty.
     */
    static String emptyInTable(String saved, String table) {
        Matcher opened = Pattern.compile("<" + table + "(\\s[^>]*?)?(/?)>").matcher(saved);
        if (!opened.find()) {
            return insideRoot(saved, "<" + table + ">" + EMPTY + "</" + table + ">");
        }
        if (!opened.group(2).isEmpty()) {
            String attrs = opened.group(1) == null ? "" : opened.group(1);
            return saved.substring(0, opened.start()) + "<" + table + attrs + ">" + EMPTY + "</" + table + ">"
                    + saved.substring(opened.end());
        }
        return saved.substring(0, opened.end()) + EMPTY + saved.substring(opened.end());
    }

    /** {@code saved}'s root element with nothing in it, its namespace declarations kept. */
    static String emptyRoot(String saved) {
        Matcher root = ROOT_OPEN.matcher(saved);
        if (!root.find()) {
            throw new IllegalArgumentException("no root element");
        }
        return saved.substring(0, root.start()) + "<" + root.group(1) + root.group(2) + "/>";
    }

    /** Whether a document holds anything but its {@code <site>}; text that is not XML counts as holding records. */
    static boolean hasRecords(String saved) {
        XmlTree.Node root = XmlTree.parseOrNull(saved);
        if (root == null) {
            return true;
        }
        for (XmlTree.Node child : root.children) {
            if (!XmlTree.local(child.tag).equals("site")) {
                return true;
            }
        }
        return false;
    }
}
