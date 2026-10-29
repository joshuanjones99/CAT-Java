package com.verifone.cat.validate.tlog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.verifone.cat.validate.PyRepr;

/**
 * TLog comparison: structural, over parsed XML, not textual (DESIGN.md §5.4). The Python CAT's
 * {@code petrotest.validate.tlog}.
 *
 * <p>A TLog is parsed, reduced to a map from element path ({@code /trans/trHeader/trTickNum/trSeq})
 * to its text and attributes, and the two maps are compared. A newly added element is one added
 * key, so one change cannot cascade into "everything after line 40 differs". Volatile values are
 * elided ({@link #IGNORED}) before anything is stored or compared, so the canonical text that
 * {@code --snapshot-update} would write is the comparison input, not a second representation.
 *
 * <p>Semantics follow PATS's {@code IgnoreNamedElementsDifferenceListener} for parity: values equal
 * ignoring case are identical; an ignored tag suppresses its own text but not its children; an
 * ignored attribute suppresses its value by local name; sibling order is ignored for siblings with
 * different tags but reported for siblings with the same tag, naming both paths.
 *
 * <p>Parsing uses the JDK's {@code javax.xml}, namespace-aware, with external entities and external
 * DTDs off, so a TLog never causes a network or file read.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public final class Tlog {

    /** Sentinel written in place of a value the rule file says is volatile. Impossible to mistake for data. */
    public static final String IGNORED = "~IGNORED~";

    private Tlog() {}

    /** What sort of difference was found at a path. */
    public enum Kind {
        /** Present in both, values differ. */
        CHANGED,
        /** In the baseline, absent from the actual TLog. */
        MISSING,
        /** In the actual TLog, absent from the baseline. */
        UNEXPECTED
    }

    /** One difference, anchored to an element or attribute path. */
    public record Difference(String path, Kind kind, String expected, String actual) {

        @Override
        public String toString() {
            return switch (kind) {
                case CHANGED -> path + ": expected " + PyRepr.repr(expected) + ", got " + PyRepr.repr(actual);
                case MISSING -> path + ": missing from the TLog (snapshot has " + PyRepr.repr(expected) + ")";
                case UNEXPECTED -> path + ": not in the snapshot (TLog has " + PyRepr.repr(actual) + ")";
            };
        }
    }

    /** The result of comparing two TLogs structurally. */
    public record Diff(List<Difference> differences, int compared, Set<String> ignoredTags,
            Set<String> ignoredAttributes) {

        public Diff {
            differences = List.copyOf(differences);
            ignoredTags = Set.copyOf(ignoredTags);
            ignoredAttributes = Set.copyOf(ignoredAttributes);
        }

        /** Whether the TLogs match. */
        public boolean ok() {
            return differences.isEmpty();
        }

        public String describe() {
            return describe(25);
        }

        /** A report naming the paths that differed, at most {@code limit} of them. */
        public String describe(int limit) {
            return describe(limit, "TLog", "TLOG-RULE.txt");
        }

        /**
         * The Python's {@code describe(limit=, kind=, rule=)}: {@code kind} and {@code rule} name what was compared
         * and the rule file that set the ignore lists, since the same comparison checks reports and packets too.
         */
        public String describe(int limit, String kind, String rule) {
            if (differences.isEmpty()) {
                return kind + "s match (" + compared + " paths compared)";
            }
            StringBuilder text = new StringBuilder(differences.size() + " " + kind + " difference(s) over " + compared
                    + " compared paths (DESIGN.md §5.4; " + ignoredTags.size() + " ignored tags, "
                    + ignoredAttributes.size() + " ignored attributes from " + rule + "):");
            for (Difference d : differences.subList(0, Math.min(limit, differences.size()))) {
                text.append("\n  ").append(d);
            }
            if (differences.size() > limit) {
                text.append("\n  ... and ").append(differences.size() - limit).append(" more");
            }
            return text.toString();
        }
    }

    /** An element's own text and its attributes, after flattening. */
    record Flat(String text, Map<String, String> attributes) {}

    /** The canonical tree, before it is written as text. */
    private record Canon(String tag, Map<String, String> attributes, String text, List<Canon> children) {}

    // ------------------------------------------------------------------
    // Parsing and path building
    // ------------------------------------------------------------------

    /**
     * Parses XML text, bytes, a {@link Path} to a {@code -Tlog.xml}, or an already parsed
     * {@link Element}. A {@link String} is always XML, never a file name: guessing between the two
     * turns a typo in a path into "the TLog is empty".
     *
     * @throws TlogParseException if the input is not well-formed XML
     */
    public static Element parse(Object source) {
        if (source instanceof Element element) {
            return element;
        }
        byte[] data;
        if (source instanceof Path file) {
            data = readBytes(file);
        } else if (source instanceof byte[] bytes) {
            data = bytes;
        } else if (source instanceof String text) {
            data = text.getBytes(StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("cannot parse TLog XML from "
                    + (source == null ? "null" : source.getClass().getSimpleName())
                    + ". Pass XML text, bytes, a Path, or a parsed Element.");
        }
        try {
            return builder().parse(new ByteArrayInputStream(data)).getDocumentElement();
        } catch (SAXException | IOException e) {
            throw new TlogParseException("could not parse TLog XML: " + e.getMessage(), e);
        }
    }

    /** The local name of an element or attribute, with any namespace prefix removed. */
    static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    /**
     * Maps every element to {@code path -> (text, attributes)}. The ordinal is added only where a
     * parent has more than one child with that tag, so a count change does not renumber unrelated
     * paths.
     */
    static Map<String, Flat> flatten(Element root) {
        Map<String, Flat> out = new LinkedHashMap<>();
        walk(root, "/" + localName(root), out);
        return out;
    }

    private static void walk(Element element, String path, Map<String, Flat> out) {
        out.put(path, new Flat(textOf(element), attributesOf(element)));
        List<Element> children = childElements(element);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Element child : children) {
            counts.merge(localName(child), 1, Integer::sum);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Element child : children) {
            String tag = localName(child);
            if (counts.get(tag) == 1) {
                walk(child, path + "/" + tag, out);
            } else {
                int ordinal = seen.merge(tag, 1, Integer::sum);
                walk(child, path + "/" + tag + "[" + ordinal + "]", out);
            }
        }
    }

    /**
     * The element's own text, stripped. Only the text before the first child element counts, so a
     * parent's value does not change when a descendant's does.
     */
    static String textOf(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            short type = node.getNodeType();
            if (type == Node.ELEMENT_NODE) {
                break;
            }
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return text.toString().strip();
    }

    private static Map<String, String> attributesOf(Element element) {
        Map<String, String> attributes = new LinkedHashMap<>();
        NamedNodeMap raw = element.getAttributes();
        for (int i = 0; i < raw.getLength(); i++) {
            Node attribute = raw.item(i);
            String name = attribute.getNodeName();
            if (name.equals("xmlns") || name.startsWith("xmlns:")) {
                continue;
            }
            attributes.put(localName(attribute), attribute.getNodeValue());
        }
        return attributes;
    }

    private static List<Element> childElements(Element element) {
        List<Element> children = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) node);
            }
        }
        return children;
    }

    // ------------------------------------------------------------------
    // Canonicalisation: what gets stored AND what gets compared
    // ------------------------------------------------------------------

    /** {@link #canonicalise(Object, TlogOptions)} with the rule file's lists. */
    public static String canonicalise(Object source) {
        return canonicalise(source, TlogOptions.rule());
    }

    /**
     * The TLog as stable, reviewable XML with volatile values elided. Attributes are sorted, text is
     * whitespace-normalised, and the tree is indented two spaces per level as Python's
     * {@code ElementTree.indent} does.
     */
    public static String canonicalise(Object source, TlogOptions options) {
        Element root = parse(source);
        StringBuilder text = new StringBuilder();
        write(canonicalNode(root, options), 0, text);
        return text.append('\n').toString();
    }

    private static Canon canonicalNode(Element element, TlogOptions options) {
        String tag = localName(element);
        boolean ignored = options.ignoreTags().contains(tag);
        Map<String, String> attributes = new TreeMap<>();
        for (Map.Entry<String, String> attr : attributesOf(element).entrySet()) {
            attributes.put(attr.getKey(),
                    options.ignoreAttributes().contains(attr.getKey()) ? IGNORED : attr.getValue());
        }
        List<Element> kids = childElements(element);
        String text = textOf(element);
        String out = null;
        if (ignored) {
            // Whether it held a value or not, so <date>x</date> and <date/> do not differ on an
            // ignored tag. A kept container's own text is dropped so its children stay on their lines.
            out = options.pruneIgnoredSubtrees() || kids.isEmpty() ? IGNORED : null;
        } else if (!text.isEmpty()) {
            out = text;
        }
        List<Canon> children = new ArrayList<>();
        if (!(ignored && options.pruneIgnoredSubtrees())) {
            for (Element child : kids) {
                children.add(canonicalNode(child, options));
            }
        }
        return new Canon(tag, attributes, out, children);
    }

    /** Writes a canonical tree the way {@code ElementTree.tostring} after {@code indent} does. */
    private static void write(Canon node, int level, StringBuilder sb) {
        sb.append('<').append(node.tag());
        for (Map.Entry<String, String> attr : node.attributes().entrySet()) {
            sb.append(' ').append(attr.getKey()).append("=\"").append(escapeAttribute(attr.getValue())).append('"');
        }
        if (node.children().isEmpty() && node.text() == null) {
            sb.append(" />");
            return;
        }
        sb.append('>');
        String text = node.text();
        if (!node.children().isEmpty() && (text == null || text.isBlank())) {
            text = "\n" + indent(level + 1);
        }
        if (text != null) {
            sb.append(escapeText(text));
        }
        List<Canon> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            write(children.get(i), level + 1, sb);
            sb.append('\n').append(indent(i < children.size() - 1 ? level + 1 : level));
        }
        sb.append("</").append(node.tag()).append('>');
    }

    private static String indent(int level) {
        return "  ".repeat(level);
    }

    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#09;");
    }

    // ------------------------------------------------------------------
    // Comparison
    // ------------------------------------------------------------------

    /** {@link #compare(Object, Object, TlogOptions)} with the rule file's lists. */
    public static Diff compare(Object expected, Object actual) {
        return compare(expected, actual, TlogOptions.rule());
    }

    /**
     * Compares two TLogs and returns a path-anchored diff. Both sides are canonicalised first, so
     * the comparison is over exactly what would be stored.
     */
    public static Diff compare(Object expected, Object actual, TlogOptions options) {
        Map<String, Flat> left = flatten(parse(canonicalise(expected, options)));
        Map<String, Flat> right = flatten(parse(canonicalise(actual, options)));
        Set<String> paths = new TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
        List<Difference> differences = diffMaps(left, right, options);
        return new Diff(differences, paths.size(), options.ignoreTags(), options.ignoreAttributes());
    }

    private static List<Difference> diffMaps(Map<String, Flat> left, Map<String, Flat> right, TlogOptions options) {
        List<Difference> out = new ArrayList<>();
        Set<String> paths = new TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
        for (String path : paths) {
            Flat l = left.get(path);
            Flat r = right.get(path);
            if (l != null && r == null) {
                out.add(new Difference(path, Kind.MISSING, render(l), null));
                continue;
            }
            if (r != null && l == null) {
                out.add(new Difference(path, Kind.UNEXPECTED, null, render(r)));
                continue;
            }
            if (!same(l.text(), r.text(), options)) {
                out.add(new Difference(path, Kind.CHANGED, l.text(), r.text()));
            }
            Set<String> names = new TreeSet<>(l.attributes().keySet());
            names.addAll(r.attributes().keySet());
            for (String name : names) {
                String attrPath = path + "@" + name;
                boolean inLeft = l.attributes().containsKey(name);
                boolean inRight = r.attributes().containsKey(name);
                if (inLeft && !inRight) {
                    out.add(new Difference(attrPath, Kind.MISSING, l.attributes().get(name), null));
                } else if (inRight && !inLeft) {
                    out.add(new Difference(attrPath, Kind.UNEXPECTED, null, r.attributes().get(name)));
                } else if (!same(l.attributes().get(name), r.attributes().get(name), options)) {
                    out.add(new Difference(attrPath, Kind.CHANGED,
                            l.attributes().get(name), r.attributes().get(name)));
                }
            }
        }
        return out;
    }

    private static boolean same(String left, String right, TlogOptions options) {
        if (left.equals(right)) {
            return true;
        }
        return options.caseInsensitiveValues() && left.equalsIgnoreCase(right);
    }

    private static String render(Flat node) {
        String text = node.text();
        Map<String, String> attributes = node.attributes();
        if (!attributes.isEmpty() && !text.isEmpty()) {
            return PyRepr.repr(text) + " " + PyRepr.repr(attributes);
        }
        if (!attributes.isEmpty()) {
            return PyRepr.repr(attributes);
        }
        return PyRepr.repr(text);
    }

    // ------------------------------------------------------------------
    // Support
    // ------------------------------------------------------------------

    private static DocumentBuilder builder() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Errors are thrown, not printed to stderr, so a truncated TLog fails with one exception.
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    // not an error
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the JDK XML parser refused a secure configuration", e);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read TLog " + file, e);
        }
    }
}
