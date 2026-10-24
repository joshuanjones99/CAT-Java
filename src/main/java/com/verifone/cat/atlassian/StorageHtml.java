package com.verifone.cat.atlassian;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tolerant tag scanner and the block reader the Python CAT's {@code cat.plans._PageParser}
 * builds on {@code html.parser}, for Confluence storage XHTML.
 *
 * <p>It yields the page's headings and tables in document order. A heading is an {@code h1}-
 * {@code h6}, or a {@code <p>} whose whole text sits in a {@code <strong>} that is its direct
 * child. Headings inside a table are ignored, and a table's cells carry their {@code rowspan}
 * and {@code colspan}. {@link #rectangular} then expands rows to one entry per grid column.
 *
 * <p>The scanner does not build a DOM and does not validate: a malformed page yields less, never
 * an exception. Like {@code html.parser}, a void tag written {@code <br>} stays on the open-tag
 * stack, so the direct-child test for a heading sees it; the port keeps that rather than fixing it.
 */
final class StorageHtml {

    /** One table cell: its text and how far it spans. */
    record Cell(String text, int rowspan, int colspan) {}

    /** A top-level block: a heading's text, or a table's rows. */
    sealed interface Block permits Heading, Table {}

    record Heading(String text) implements Block {}

    record Table(List<List<Cell>> rows) implements Block {}

    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /** Named entities the Confluence pages in use write; anything else is left as written. */
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
            Map.entry("hellip", "…"), Map.entry("le", "≤"), Map.entry("ge", "≥"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("deg", "°"),
            Map.entry("times", "×"), Map.entry("trade", "™"));

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ENTITY =
            Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});");

    private StorageHtml() {}

    /** Every top-level heading and table of {@code html}, in document order. */
    static List<Block> blocks(String html) {
        BlockReader reader = new BlockReader();
        new Scanner(html, reader).run();
        return reader.blocks;
    }

    /**
     * Expands {@code rowspan}/{@code colspan} so every row has one entry per grid column. A
     * rowspanned cell is carried into the rows below it, which keeps a column from shifting left
     * on the Cadence row of the real page.
     */
    static List<List<String>> rectangular(List<List<Cell>> rows) {
        List<List<String>> grid = new ArrayList<>();
        Map<Integer, String> carriedText = new HashMap<>();
        Map<Integer, Integer> carriedLeft = new HashMap<>();
        for (List<Cell> row : rows) {
            List<String> line = new ArrayList<>();
            int column = 0;
            int index = 0;
            while (index < row.size() || anyCarriedFrom(carriedLeft, column)) {
                if (carriedLeft.containsKey(column)) {
                    String text = carriedText.get(column);
                    int remaining = carriedLeft.remove(column);
                    line.add(text);
                    if (remaining > 1) {
                        carriedText.put(column, text);
                        carriedLeft.put(column, remaining - 1);
                    }
                    column++;
                    continue;
                }
                if (index >= row.size()) {
                    break;
                }
                Cell cell = row.get(index++);
                for (int i = 0; i < cell.colspan(); i++) {
                    line.add(cell.text());
                    if (cell.rowspan() > 1) {
                        carriedText.put(column, cell.text());
                        carriedLeft.put(column, cell.rowspan() - 1);
                    }
                    column++;
                }
            }
            grid.add(line);
        }
        return grid;
    }

    private static boolean anyCarriedFrom(Map<Integer, Integer> carriedLeft, int column) {
        for (int key : carriedLeft.keySet()) {
            if (key >= column) {
                return true;
            }
        }
        return false;
    }

    /** Collapses whitespace and no-break spaces, then strips, as {@code cat.plans._clean} does. */
    static String clean(String text) {
        return pyStrip(WHITESPACE.matcher(text.replace(' ', ' ')).replaceAll(" "));
    }

    /** Lowercase, alphanumerics only: the form the column names are matched against. */
    static String headerKey(String text) {
        return clean(text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Python's {@code str.strip()}: strips whitespace and any other space separator. */
    static String pyStrip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isPySpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isPySpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isPySpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Decodes the named and numeric entities a page uses; an unknown one is left as written. */
    static String decode(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String body = matcher.group(1);
            String replacement;
            if (body.startsWith("#x") || body.startsWith("#X")) {
                replacement = codePoint(body.substring(2), 16);
            } else if (body.startsWith("#")) {
                replacement = codePoint(body.substring(1), 10);
            } else {
                replacement = NAMED.get(body);
            }
            out.append(replacement == null ? matcher.group() : replacement);
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private static String codePoint(String digits, int radix) {
        try {
            int point = Integer.parseInt(digits, radix);
            return Character.isValidCodePoint(point) ? new String(Character.toChars(point)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The block reader: a direct port of {@code _PageParser}'s state machine. */
    private static final class BlockReader {
        final List<Block> blocks = new ArrayList<>();
        private final List<List<List<Cell>>> tables = new ArrayList<>();
        private List<Cell> row;
        private StringBuilder cell;
        private int rowspan = 1;
        private int colspan = 1;
        private final List<String> stack = new ArrayList<>();
        private StringBuilder heading;
        private int pDepth;
        private final StringBuilder pText = new StringBuilder();
        private final StringBuilder strongText = new StringBuilder();
        private int strongDepth;

        void startTag(String tag, Map<String, String> attributes) {
            if (tag.equals("table")) {
                tables.add(new ArrayList<>());
            } else if (tag.equals("tr") && !tables.isEmpty()) {
                row = new ArrayList<>();
            } else if ((tag.equals("td") || tag.equals("th")) && row != null) {
                cell = new StringBuilder();
                rowspan = intAttribute(attributes, "rowspan");
                colspan = intAttribute(attributes, "colspan");
            } else if (HEADINGS.contains(tag)) {
                heading = new StringBuilder();
            } else if (tag.equals("p")) {
                pDepth++;
                if (pDepth == 1) {
                    pText.setLength(0);
                    strongText.setLength(0);
                    strongDepth = 0;
                }
                if (cell != null) {
                    cell.append(' '); // block boundary inside a multi-paragraph cell
                }
            } else if (tag.equals("strong") && cell == null && top().equals("p")) {
                strongDepth++;
            } else if (tag.equals("br")) {
                text(" ");
            }
            stack.add(tag);
        }

        void endTag(String tag) {
            if (!stack.isEmpty() && stack.get(stack.size() - 1).equals(tag)) {
                stack.remove(stack.size() - 1);
            }
            if (tag.equals("table") && !tables.isEmpty()) {
                blocks.add(new Table(tables.remove(tables.size() - 1)));
            } else if (tag.equals("tr") && row != null && !tables.isEmpty()) {
                tables.get(tables.size() - 1).add(row);
                row = null;
            } else if ((tag.equals("td") || tag.equals("th")) && cell != null && row != null) {
                row.add(new Cell(cell.toString(), rowspan, colspan));
                cell = null;
            } else if (HEADINGS.contains(tag) && heading != null) {
                if (tables.isEmpty()) {
                    blocks.add(new Heading(heading.toString()));
                }
                heading = null;
            } else if (tag.equals("p") && pDepth > 0) {
                String whole = pyStrip(pText.toString());
                String strong = pyStrip(strongText.toString());
                if (pDepth == 1 && !whole.isEmpty() && whole.equals(strong) && tables.isEmpty()) {
                    blocks.add(new Heading(whole));
                }
                pDepth--;
            } else if (tag.equals("strong") && strongDepth > 0) {
                strongDepth--;
            }
        }

        void data(String data) {
            text(data);
        }

        private void text(String data) {
            if (cell != null) {
                cell.append(data);
                return;
            }
            if (heading != null) {
                heading.append(data);
            }
            if (pDepth > 0) {
                pText.append(data);
                if (strongDepth > 0) {
                    strongText.append(data);
                }
            }
        }

        private String top() {
            return stack.isEmpty() ? "" : stack.get(stack.size() - 1);
        }

        private static int intAttribute(Map<String, String> attributes, String name) {
            String value = attributes.get(name);
            if (value == null) {
                return 1;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }

    /** The tokenizer: tags, text, comments and declarations, in document order. */
    private static final class Scanner {
        private final String html;
        private final BlockReader reader;
        private int pos;

        Scanner(String html, BlockReader reader) {
            this.html = html;
            this.reader = reader;
        }

        void run() {
            int length = html.length();
            while (pos < length) {
                char c = html.charAt(pos);
                char next = pos + 1 < length ? html.charAt(pos + 1) : '\0';
                if (c == '<' && html.startsWith("<!--", pos)) {
                    int end = html.indexOf("-->", pos + 4);
                    pos = end < 0 ? length : end + 3;
                } else if (c == '<' && (next == '!' || next == '?')) {
                    int end = html.indexOf('>', pos);
                    pos = end < 0 ? length : end + 1;
                } else if (c == '<' && next == '/' && pos + 2 < length && Character.isLetter(html.charAt(pos + 2))) {
                    int end = html.indexOf('>', pos);
                    if (end < 0) {
                        return;
                    }
                    reader.endTag(tagName(html.substring(pos + 2, end)));
                    pos = end + 1;
                } else if (c == '<' && Character.isLetter(next)) {
                    if (!startTag()) {
                        return;
                    }
                } else {
                    // Text, or a stray '<' that opens no tag: up to the next '<' that might.
                    int from = c == '<' ? pos + 1 : pos;
                    int found = html.indexOf('<', from);
                    int end = found < 0 ? length : found;
                    reader.data(decode(html.substring(pos, end)));
                    pos = end;
                }
            }
        }

        /** Reads one start tag at {@code pos}. False when the tag never closes. */
        private boolean startTag() {
            int length = html.length();
            int i = pos + 1;
            int nameStart = i;
            while (i < length && !isNameEnd(html.charAt(i))) {
                i++;
            }
            String name = html.substring(nameStart, i).toLowerCase(Locale.ROOT);
            Map<String, String> attributes = new HashMap<>();
            boolean selfClosing = false;
            while (true) {
                while (i < length && Character.isWhitespace(html.charAt(i))) {
                    i++;
                }
                if (i >= length) {
                    return false;
                }
                char c = html.charAt(i);
                if (c == '>') {
                    i++;
                    break;
                }
                if (c == '/') {
                    selfClosing = true;
                    i++;
                    continue;
                }
                int attrStart = i;
                while (i < length && !Character.isWhitespace(html.charAt(i))
                        && html.charAt(i) != '>' && html.charAt(i) != '=' && html.charAt(i) != '/') {
                    i++;
                }
                String attrName = html.substring(attrStart, i).toLowerCase(Locale.ROOT);
                if (attrName.isEmpty()) {
                    i++; // a stray '=' with no name before it
                    continue;
                }
                String value = "";
                int afterName = i;
                while (afterName < length && Character.isWhitespace(html.charAt(afterName))) {
                    afterName++;
                }
                if (afterName < length && html.charAt(afterName) == '=') {
                    i = afterName + 1;
                    while (i < length && Character.isWhitespace(html.charAt(i))) {
                        i++;
                    }
                    if (i < length && (html.charAt(i) == '"' || html.charAt(i) == '\'')) {
                        int close = html.indexOf(html.charAt(i), i + 1);
                        if (close < 0) {
                            return false;
                        }
                        value = html.substring(i + 1, close);
                        i = close + 1;
                    } else {
                        int valueStart = i;
                        while (i < length && !Character.isWhitespace(html.charAt(i)) && html.charAt(i) != '>') {
                            i++;
                        }
                        value = html.substring(valueStart, i);
                    }
                }
                attributes.put(attrName, decode(value));
            }
            pos = i;
            reader.startTag(name, attributes);
            if (selfClosing) {
                reader.endTag(name);
            }
            if (name.equals("script") || name.equals("style")) {
                // Raw text up to the closing tag, as html.parser hands it over.
                Matcher close = Pattern.compile("</" + name, Pattern.CASE_INSENSITIVE).matcher(html);
                int stop = close.find(pos) ? close.start() : length;
                reader.data(html.substring(pos, stop));
                pos = stop;
            }
            return true;
        }

        private static boolean isNameEnd(char c) {
            return Character.isWhitespace(c) || c == '>' || c == '/';
        }

        private static String tagName(String inside) {
            int i = 0;
            while (i < inside.length() && !isNameEnd(inside.charAt(i))) {
                i++;
            }
            return inside.substring(0, i).toLowerCase(Locale.ROOT);
        }
    }
}
