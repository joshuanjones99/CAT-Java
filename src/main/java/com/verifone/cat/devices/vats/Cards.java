package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.verifone.cat.devices.DeviceError;

/**
 * What a PATS {@code CARD_NAME} means on a physical pinpad: an image to load, or tracks to swipe. A port of the
 * Python CAT's {@code petrotest.devices.vats.cards} ({@link CardImages} is its {@code CardImages}).
 *
 * <p>PATS's rules ({@code CardActionToTerminalHandler} / {@code VatsRequestBuilder} in petroapps):
 * <ul>
 *   <li>a label containing {@code EMV} is a chip card, else a magstripe swipe (case-sensitive);</li>
 *   <li>a chip label containing {@code EMV_CTLS} is tapped (contactless), else inserted;</li>
 *   <li>a magstripe label's tracks come from {@code MSRCardData.xml}, looked up by {@code <label>}.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Cards {

    private Cards() {}

    /** How a card reaches the terminal. Its {@link #value()} is the rig's {@code vats.interface} spelling. */
    public enum Kind {
        CONTACT("contact"),
        CONTACTLESS("contactless"),
        MAGSTRIPE("magstripe");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** PATS's label rule, case-sensitive as PATS's {@code contains("EMV")} is. */
    public static Kind kindOf(String label) {
        if (!label.contains("EMV")) {
            return Kind.MAGSTRIPE;
        }
        return label.contains("EMV_CTLS") ? Kind.CONTACTLESS : Kind.CONTACT;
    }

    /** A magstripe card's three tracks; an absent track is "", never null (the SDK takes {@code len()} of each). */
    public record Tracks(String track1, String track2, String track3) {

        public Tracks {
            track1 = track1 == null ? "" : track1;
            track2 = track2 == null ? "" : track2;
            track3 = track3 == null ? "" : track3;
        }
    }

    /**
     * {@code MSRCardDataXMLHandler.getMSRCard(label)}: the {@code <Card>} whose {@code <label>} matches, or null when
     * the file is absent or has none.
     */
    public static Tracks msrTracks(String label, Path dataFile) {
        if (dataFile == null || !Files.isRegularFile(dataFile)) {
            return null;
        }
        Document doc = parse(dataFile);
        NodeList cards = doc.getElementsByTagName("Card");
        for (int i = 0; i < cards.getLength(); i++) {
            Element card = (Element) cards.item(i);
            if (childText(card, "label").strip().equals(label)) {
                return new Tracks(childText(card, "track1").strip(), childText(card, "track2").strip(),
                        childText(card, "track3").strip());
            }
        }
        return null;
    }

    /** ElementTree's {@code findtext(tag) or ""}: the first direct child's text. */
    private static String childText(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                String text = e.getTextContent();
                return text == null ? "" : text;
            }
        }
        return "";
    }

    static Document parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return builder().parse(in);
        } catch (IOException | SAXException e) {
            throw new DeviceError("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** A DOM parser with external entities off. */
    static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lower-cased file name, the image index's key. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
