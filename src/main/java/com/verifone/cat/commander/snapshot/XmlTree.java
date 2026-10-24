package com.verifone.cat.commander.snapshot;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A small element tree with Python's {@code xml.etree.ElementTree} semantics: names in Clark notation
 * ({@code {uri}local}), {@code text} before the first child and {@code tail} after each element, comments dropped.
 * Only what {@code commander_snapshot.py} reads and writes: parse, the canonical serialisation, and a writer that keeps
 * the document's own prefixes.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class XmlTree {

    /** The namespace URI of {@code xmlns} declarations, which ElementTree never reports as attributes. */
    private static final String XMLNS_URI = XMLConstants.XMLNS_ATTRIBUTE_NS_URI;

    private XmlTree() {}

    /** One element. Fields are mutable, as the merge rewrites values in place. */
    static final class Node {
        final String tag;
        final Map<String, String> attrib = new LinkedHashMap<>();
        final List<Node> children = new ArrayList<>();
        String text;
        String tail;

        Node(String tag) {
            this.tag = tag;
        }
    }

    /** The text is not an XML document (ElementTree's ParseError). */
    static final class NotXml extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotXml(String message) {
            super(message);
        }
    }

    /** {@code {uri}local} -> {@code local}. */
    static String local(String name) {
        int brace = name.lastIndexOf('}');
        return brace < 0 ? name : name.substring(brace + 1);
    }

    /**
     * Parses {@code xml} (stripped first, as the Python does).
     *
     * @throws NotXml when it is not well-formed XML
     */
    static Node parse(String xml) {
        return new Builder().build(xml.strip());
    }

    /** {@code parse}, or null when the text is not XML. */
    static Node parseOrNull(String xml) {
        try {
            return parse(xml);
        } catch (NotXml e) {
            return null;
        }
    }

    /** Namespace URI -> the prefix the document declared for it; the first declaration wins. Empty when not XML. */
    static Map<String, String> prefixes(String xml) {
        Map<String, String> found = new LinkedHashMap<>();
        try {
            SAXParserFactory factory = secureFactory();
            factory.newSAXParser().parse(new InputSource(new StringReader(xml.strip())), new DefaultHandler() {
                @Override
                public void startPrefixMapping(String prefix, String uri) {
                    found.putIfAbsent(uri, prefix);
                }
            });
        } catch (SAXException | IOException | ParserConfigurationException e) {
            return found;
        }
        return found;
    }

    private static SAXParserFactory secureFactory() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    /** Builds the tree with ElementTree's text/tail placement. */
    private static final class Builder extends DefaultHandler {
        private final List<Node> stack = new ArrayList<>();
        private final StringBuilder pending = new StringBuilder();
        private Node root;

        Node build(String xml) {
            try {
                secureFactory().newSAXParser().parse(new InputSource(new StringReader(xml)), this);
            } catch (SAXException | IOException | ParserConfigurationException e) {
                throw new NotXml(e.getMessage());
            }
            if (root == null) {
                throw new NotXml("no root element");
            }
            return root;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            flush();
            Node node = new Node(qualify(uri, localName));
            for (int i = 0; i < atts.getLength(); i++) {
                String attrUri = atts.getURI(i);
                String attrQName = atts.getQName(i);
                if (XMLNS_URI.equals(attrUri) || attrQName.equals("xmlns") || attrQName.startsWith("xmlns:")) {
                    continue;
                }
                node.attrib.put(qualify(attrUri, atts.getLocalName(i)), atts.getValue(i));
            }
            if (stack.isEmpty()) {
                root = node;
            } else {
                stack.get(stack.size() - 1).children.add(node);
            }
            stack.add(node);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flush();
            stack.remove(stack.size() - 1);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            pending.append(ch, start, length);
        }

        /** Text goes to the open element's {@code text} before its first child, else to the last child's tail. */
        private void flush() {
            if (pending.length() == 0 || stack.isEmpty()) {
                pending.setLength(0);
                return;
            }
            String data = pending.toString();
            pending.setLength(0);
            Node open = stack.get(stack.size() - 1);
            if (open.children.isEmpty()) {
                open.text = open.text == null ? data : open.text + data;
            } else {
                Node last = open.children.get(open.children.size() - 1);
                last.tail = last.tail == null ? data : last.tail + data;
            }
        }

        private static String qualify(String uri, String local) {
            return uri == null || uri.isEmpty() ? local : "{" + uri + "}" + local;
        }
    }

    /**
     * The canonical serialisation the comparison keys are made from: names as Clark notation, attributes sorted,
     * text and tails escaped. Not an XML document.
     */
    static String serialise(Node element) {
        StringBuilder out = new StringBuilder();
        serialiseInto(element, out);
        return out.toString();
    }

    private static void serialiseInto(Node element, StringBuilder out) {
        out.append('<').append(element.tag);
        for (Map.Entry<String, String> attr : new TreeMap<>(element.attrib).entrySet()) {
            out.append(' ').append(attr.getKey()).append('=').append(quoteAttr(attr.getValue()));
        }
        out.append('>');
        out.append(escape(element.text == null ? "" : element.text));
        for (Node child : element.children) {
            serialiseInto(child, out);
            out.append(escape(child.tail == null ? "" : child.tail));
        }
        out.append("</").append(element.tag).append('>');
    }

    /** {@code xml.sax.saxutils.escape}: {@code & < >}. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** {@code xml.sax.saxutils.quoteattr}: the value in quotes, with line breaks and tabs as character references. */
    static String quoteAttr(String value) {
        String data = escape(value).replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;");
        if (data.contains("\"")) {
            if (data.contains("'")) {
                return "\"" + data.replace("\"", "&quot;") + "\"";
            }
            return "'" + data + "'";
        }
        return "\"" + data + "\"";
    }

    /**
     * {@code root} as XML, with the source document's own prefixes declared on the root. A namespace the source never
     * declared gets a generated {@code ns<n>} prefix, and a default namespace never applies to an attribute.
     */
    static String toXml(Node root, Map<String, String> prefixes) {
        Map<String, String> used = new LinkedHashMap<>(prefixes);
        String body = write(root, used);
        StringBuilder declared = new StringBuilder();
        for (Map.Entry<String, String> entry : used.entrySet()) {
            String uri = entry.getKey();
            String prefix = entry.getValue();
            declared.append(prefix.isEmpty() ? " xmlns=" : " xmlns:" + prefix + "=").append(quoteAttr(uri));
        }
        int cut = qualifiedName(root.tag, used, false).length() + 1;
        return body.substring(0, cut) + declared + body.substring(cut);
    }

    private static String write(Node element, Map<String, String> used) {
        StringBuilder attrs = new StringBuilder();
        for (Map.Entry<String, String> attr : element.attrib.entrySet()) {
            attrs.append(' ').append(qualifiedName(attr.getKey(), used, true)).append('=')
                    .append(quoteAttr(attr.getValue()));
        }
        StringBuilder inner = new StringBuilder(escape(element.text == null ? "" : element.text));
        for (Node child : element.children) {
            inner.append(write(child, used));
            inner.append(escape(child.tail == null ? "" : child.tail));
        }
        String tag = qualifiedName(element.tag, used, false);
        return inner.length() > 0 ? "<" + tag + attrs + ">" + inner + "</" + tag + ">"
                : "<" + tag + attrs + "/>";
    }

    private static String qualifiedName(String qualified, Map<String, String> used, boolean attribute) {
        if (!qualified.startsWith("{")) {
            return qualified;
        }
        int close = qualified.indexOf('}');
        String uri = qualified.substring(1, close);
        String localName = qualified.substring(close + 1);
        if (!used.containsKey(uri)) {
            used.put(uri, "ns" + used.size());
        }
        String prefix = used.get(uri);
        if (prefix.isEmpty() && attribute) {
            prefix = "ns" + used.size();
            used.put(uri, prefix);
        }
        return prefix.isEmpty() ? localName : prefix + ":" + localName;
    }
}
