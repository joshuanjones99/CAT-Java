package com.verifone.cat.devices.commander;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.verifone.cat.devices.DeviceProtocolError;

/**
 * The Commander's CGILink replies, read as XML, and the parsers that turn the ones CAT needs
 * into typed records: the pure functions of the Python CAT's
 * {@code petrotest.devices.config_shim} ({@code parse_function_list}, {@code parse_fep_details},
 * {@code extract_upgrade_token}, {@code is_upgrade_complete}, {@code select_transaction_by_trseq}).
 *
 * <p>Nothing here touches the network, so every parser is tested against a fixture. Lookups
 * match by local name, namespaced or bare, because the Commander's replies mix both (the
 * Python's {@code _find_any_ns}). Text is read the way ElementTree's {@code .text} reads it:
 * the text before an element's first child, not the text of the whole subtree, so a check on
 * one element never sees its children's words.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the shapes are the Python CAT's, read from
 * {@code docs/commander-config-surface.md} and the lab replies quoted in its tests; no reply
 * captured here came from a Commander.
 */
public final class CgiReplies {

    /**
     * The literal success sentinel Overarch polls {@code vupgradesummary} for
     * ({@code 02-update.bat}'s {@code CheckAUStatus}). The element or attribute carrying it is
     * unconfirmed, so {@link #isUpgradeComplete} searches for it everywhere.
     */
    public static final String UPGRADE_COMPLETED_SENTINEL = "UPGRADE_COMPLETED_SUCCESSFULLY";

    private static final List<String> TOKEN_TAGS = List.of("token", "upgradeToken", "sessionToken", "upgradeSessionToken");

    private CgiReplies() {}

    /** One {@code <Function>} of the {@code validate} reply's funcList (config-surface doc section 3). */
    public record CgiFunction(String cmd, String display, String detail, String appGroup, boolean otpRequired) {

        /** A function known only by its command. */
        public CgiFunction(String cmd) {
            this(cmd, "", "", "", false);
        }
    }

    /**
     * One {@code <fepDetail>} of {@code vfepdetails}.
     *
     * <p>{@code fepType} is the Commander's own label ({@code CommandConstants.FEPTYPE_MAPPING}):
     * Primary, Secondary, Prepaid, Loyalty, Service, ... A Commercial Fleet FEP (WEX-OTR,
     * Comdata, mudflap) has FEPType Payment and so reads "Primary" here; {@code primary} is the
     * flag that names the real one.
     */
    public record FepDetail(String name, String fepType, boolean enabled, boolean primary) {}

    /**
     * What {@code vcloudconnectstatus} says about onboarding: {@code registered} when the reply
     * carries a {@code registrationTimeStamp}; otherwise {@code detail} is the state code and the
     * last error the Commander recorded. The Python returns this as a {@code (bool, str)} tuple.
     */
    public record CloudConnectStatus(boolean registered, String detail) {}

    /**
     * Parses a reply as XML and returns its document element.
     *
     * <p>Namespace-aware first. A reply that uses a prefix it never declares
     * ({@code <VFI:Fault>} with no {@code xmlns:VFI}) is read again without namespaces, so its
     * fault is still seen as a fault. The Python's ElementTree refuses such a reply, and then
     * decides a login retry by finding {@code LoginRequired} in the error text, which quotes the
     * body; reading the fault reaches the same decision without leaning on that. A DOCTYPE is
     * refused: a CGILink reply has none, and refusing one shuts out external entities.
     *
     * @param text the reply body
     * @param call what was called, for the protocol error's message
     * @throws DeviceProtocolError the body is not well-formed XML
     */
    public static Element parse(String text, String call) {
        try {
            return parse(text, true);
        } catch (SAXException namespaced) {
            try {
                return parse(text, false);
            } catch (SAXException | IOException e) {
                throw new DeviceProtocolError(call, "reply is not XML: " + snippet(text), namespaced);
            }
        } catch (IOException e) {
            throw new DeviceProtocolError(call, "reply is not XML: " + snippet(text), e);
        }
    }

    private static Element parse(String text, boolean namespaceAware) throws SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder;
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the JDK's XML parser refused its own configuration", e);
        }
        // The default handler prints "[Fatal Error]" to stderr before throwing; the throw is enough.
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        return builder.parse(new InputSource(new StringReader(text))).getDocumentElement();
    }

    /**
     * Raises {@link CommanderFault} if the reply holds an element whose name ends in
     * {@code Fault}, the root included.
     *
     * <p>The Commander answers HTTP 200 even for a refused command, so this is the only place a
     * fault shows. A fault with no code reads as {@code "(no faultCode)"}, as in the Python.
     */
    public static void checkFault(Element root) {
        for (Element element : all(root)) {
            if (localName(element).endsWith("Fault")) {
                Element code = child(element, "faultCode");
                String text = code == null ? "" : text(code);
                throw new CommanderFault(text.isEmpty() ? "(no faultCode)" : text);
            }
        }
    }

    /**
     * Every {@code <Function>} under {@code root}, namespaced or not, in the order the Commander
     * sent them. A function with no {@code FunctionCmd} is left out.
     *
     * <p>The catalogue is not static and depends on the login's roles (config-surface doc
     * section 3), so a caller reads it when it needs it and keeps no copy as the truth.
     */
    public static List<CgiFunction> functionList(Element root) {
        List<CgiFunction> functions = new ArrayList<>();
        for (Element node : all(root)) {
            if (!"Function".equals(localName(node))) {
                continue;
            }
            String cmd = childText(node, "FunctionCmd");
            if (!cmd.isEmpty()) {
                functions.add(new CgiFunction(
                        cmd,
                        childText(node, "FunctionDisplay"),
                        childText(node, "FunctionDetail"),
                        childText(node, "FunctionAppGroup"),
                        "true".equalsIgnoreCase(childText(node, "OTPRequired"))));
            }
        }
        return functions;
    }

    /**
     * {@code vfepdetails}'s reply, by FEP name, in the Commander's order.
     *
     * <p>The shape is PETROCORE-37412's {@code fepdetails-View-Config-Reference.xml}: a
     * {@code viper:fepDetails} holding one {@code <fepDetail fep=... fepType=...>} per FEP-table
     * row, each with {@code <enabled>} and {@code <isPrimary>}.
     *
     * @throws DeviceProtocolError the reply is not XML, or holds no {@code fepDetail}
     */
    public static Map<String, FepDetail> fepDetails(String xmlText) {
        Element root = parse(xmlText.strip(), "vfepdetails");
        Map<String, FepDetail> details = new LinkedHashMap<>();
        for (Element element : all(root)) {
            String fep = element.getAttribute("fep");
            if (!localName(element).endsWith("fepDetail") || fep.isEmpty()) {
                continue;
            }
            details.put(fep, new FepDetail(
                    fep,
                    element.getAttribute("fepType"),
                    "true".equals(childText(element, "enabled")),
                    "true".equals(childText(element, "isPrimary"))));
        }
        if (details.isEmpty()) {
            throw new DeviceProtocolError("vfepdetails", "no fepDetail in the reply: " + snippet(xmlText));
        }
        return details;
    }

    /**
     * Selects one transaction from a {@code vtransset} report by its {@code trSeq}.
     *
     * <p>PATS's {@code TestCGIViewCmd.getTlog} (petroapps, line 686) selects
     * {@code trSeq:matchesOwn(n)} with Jsoup and takes {@code .parents().get(2)}. The shape is
     * {@code <trans><trHeader><trTickNum><trSeq>}, so the {@code <trans>} is three levels above
     * the {@code trSeq}. Whitespace around either number is ignored, and the walk never leaves
     * {@code report}. Kept, and not called by {@link PdkShim#fetchTlog}: a fallback in case
     * {@code vtransdetails}'s reply turns out not to carry what an assertion needs.
     *
     * @throws IllegalArgumentException the number is not in the report, or the tree is not that
     *                                  shape (the Python raises {@code ValueError})
     */
    public static Element selectTransactionByTrseq(Element report, String transNum) {
        String wanted = transNum.strip();
        Element trSeq = null;
        for (Element element : all(report)) {
            if ("trSeq".equals(localName(element)) && text(element).strip().equals(wanted)) {
                trSeq = element;
                break;
            }
        }
        if (trSeq == null) {
            throw new IllegalArgumentException("Transaction sequence number '" + wanted + "' not found in report. "
                    + "This may indicate the transaction number is incorrect, the report does not contain the "
                    + "expected transaction, or the report format is unexpected (see "
                    + "docs/commander-config-surface.md §8a).");
        }
        Element current = trSeq;
        for (int level = 0; level < 3; level++) {
            Node parent = current == report ? null : current.getParentNode();
            if (!(parent instanceof Element)) {
                throw new IllegalArgumentException("Cannot reach enclosing <trans> element: tree ends after " + level
                        + " ancestor level(s). Expected to find <trans> 3 levels above <trSeq>, but the tree "
                        + "structure does not match the expected form (see docs/commander-config-surface.md §8a).");
            }
            current = (Element) parent;
        }
        String tag = localName(current);
        if (!"trans".equals(tag)) {
            throw new IllegalArgumentException("Reached element <" + tag + "> after 3 ancestor levels, expected "
                    + "<trans>. The report structure does not match the expected form "
                    + "(see docs/commander-config-surface.md §8a).");
        }
        return current;
    }

    /**
     * The {@code cbeginupgrade} token out of an {@code upgradeSummary} reply.
     *
     * <p>The element name is unconfirmed on a live Commander, so this tries the Python's four
     * candidates in its order. It raises rather than guessing when none matches: a wrong token
     * fed to {@code cbeginupgrade} would act on the live device.
     *
     * @throws DeviceProtocolError none of the candidate elements holds a token
     */
    public static String upgradeToken(Element summaryRoot) {
        for (String tag : TOKEN_TAGS) {
            Element element = descendant(summaryRoot, tag);
            if (element != null && !text(element).isEmpty()) {
                return text(element);
            }
        }
        throw new DeviceProtocolError("vupgradesummary",
                "no recognized upgrade-token element (tried token/upgradeToken/sessionToken/upgradeSessionToken) in "
                        + snippet(serialise(summaryRoot)) + " — the real element name is unconfirmed against a live "
                        + "Commander; update this function once it is known.");
    }

    /**
     * Whether an {@code upgradeSummary} reply reports the AU upgrade done.
     *
     * <p>Searches every element's text and attribute values for {@link #UPGRADE_COMPLETED_SENTINEL},
     * because which one carries it is unconfirmed. It recognises success only: there is no
     * confirmed failure sentinel, so a failed upgrade shows as this staying false until the
     * caller's poll budget runs out, not as a fast, specific failure.
     */
    public static boolean isUpgradeComplete(Element summaryRoot) {
        for (Element element : all(summaryRoot)) {
            if (text(element).contains(UPGRADE_COMPLETED_SENTINEL)) {
                return true;
            }
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                if (!isNamespaceDeclaration(attribute) && attribute.getNodeValue().contains(UPGRADE_COMPLETED_SENTINEL)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- XML helpers, package-private: the transport and the client use them too.

    /** The element's name without any prefix, whether or not it was parsed namespace-aware. */
    static String localName(Element element) {
        String name = element.getLocalName();
        if (name != null) {
            return name;
        }
        String tag = element.getTagName();
        return tag.substring(tag.indexOf(':') + 1);
    }

    /** Whether the element has this name with no namespace and no prefix: an ElementTree bare tag. */
    static boolean isBare(Element element, String name) {
        return element.getNamespaceURI() == null && name.equals(element.getTagName());
    }

    /**
     * ElementTree's {@code .text}, never {@code null}: the text and CDATA before the element's
     * first child element, comments skipped as ElementTree's parser drops them.
     */
    static String text(Element element) {
        StringBuilder out = new StringBuilder();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            short type = node.getNodeType();
            if (type == Node.ELEMENT_NODE) {
                break;
            }
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                out.append(node.getNodeValue());
            }
        }
        return out.toString();
    }

    /** {@code root} and every element under it, in document order: ElementTree's {@code iter()}. */
    static List<Element> all(Element root) {
        List<Element> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    /** The first direct child with this local name, or {@code null}. */
    static Element child(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName((Element) node))) {
                return (Element) node;
            }
        }
        return null;
    }

    /** The first element under {@code parent} (not {@code parent} itself) with this local name: {@code .//name}. */
    static Element descendant(Element parent, String localName) {
        List<Element> elements = all(parent);
        for (int i = 1; i < elements.size(); i++) {
            if (localName.equals(localName(elements.get(i)))) {
                return elements.get(i);
            }
        }
        return null;
    }

    /** The stripped text of a direct child, or {@code ""} when there is none. */
    static String childText(Element parent, String localName) {
        Element element = child(parent, localName);
        return element == null ? "" : text(element).strip();
    }

    /** The first 200 characters of a body, quoted, for an error message. */
    static String snippet(String text) {
        String shown = text.length() <= 200 ? text : text.substring(0, 200);
        return "'" + shown + "'";
    }

    /** An element as markup, for an error message only. */
    static String serialise(Element element) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            return "<" + element.getTagName() + ">";
        }
    }

    private static boolean isNamespaceDeclaration(Node attribute) {
        String name = attribute.getNodeName();
        return "xmlns".equals(name) || name.startsWith("xmlns:");
    }

    private static void collect(Element element, List<Element> out) {
        out.add(element);
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                collect((Element) node, out);
            }
        }
    }
}
