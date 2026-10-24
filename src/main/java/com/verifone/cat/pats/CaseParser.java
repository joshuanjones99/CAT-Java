package com.verifone.cat.pats;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Reads a PATS {@code -TestCase.xml} into its {@link ParsedCase}. Port of {@code parse_actions} in
 * {@code petrotest.pats.case}.
 *
 * <p>XML is read with external entities and DTDs refused: a case file is data. A document with a DOCTYPE is
 * rejected, which is stricter than the Python's {@code ElementTree}; no petroapps case has one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CaseParser {

    private CaseParser() {
    }

    /**
     * The case in {@code xml}: PATS takes the root's {@code <testcase>} children, so a {@code <testcase>} wrapped
     * in another (PETROCORE-43126) runs the inner one's actions.
     *
     * @throws IllegalArgumentException when the document does not parse or holds no {@code <testcase>}
     */
    public static ParsedCase parseActions(byte[] xml) {
        return parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    /** {@link #parseActions(byte[])} for text already decoded. */
    public static ParsedCase parseActions(String xml) {
        return parse(new InputSource(new StringReader(xml)));
    }

    private static ParsedCase parse(InputSource source) {
        Document document;
        try {
            document = documentBuilder().parse(source);
        } catch (SAXException | IOException e) {
            throw new IllegalArgumentException("not a PATS test script: " + e.getMessage(), e);
        }
        Element root = document.getDocumentElement();
        Element testcase = firstChild(root, "testcase");
        if (testcase == null) {
            testcase = root.getTagName().equals("testcase") ? root : firstDescendant(root, "testcase");
        }
        if (testcase == null) {
            throw new IllegalArgumentException("no <testcase> element");
        }
        List<Action> actions = new ArrayList<>();
        List<Element> elements = children(testcase, "action");
        for (int i = 0; i < elements.size(); i++) {
            Element action = elements.get(i);
            List<Action.Field> fields = new ArrayList<>();
            for (Element field : children(action, "field")) {
                fields.add(new Action.Field(field.getAttribute("name"), field.getAttribute("value")));
            }
            actions.add(new Action(action.getAttribute("name"), fields, i));
        }
        return new ParsedCase(testcase.getAttribute("name"), actions);
    }

    /** A namespace-unaware builder with the external-entity and DOCTYPE refusals of {@code CaseReader}. */
    private static DocumentBuilder documentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("no secure XML parser on this JDK", e);
        }
    }

    private static Element firstChild(Element parent, String tag) {
        List<Element> found = children(parent, tag);
        return found.isEmpty() ? null : found.get(0);
    }

    /** The direct child elements named {@code tag}, in document order (Python's {@code findall}). */
    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && element.getTagName().equals(tag)) {
                out.add(element);
            }
        }
        return out;
    }

    private static Element firstDescendant(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }
}
