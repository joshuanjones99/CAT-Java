package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.validate.tlog.Tlog;
import com.verifone.cat.validate.tlog.TlogOptions;
import com.verifone.cat.validate.tlog.TlogParseException;
import com.verifone.cat.validate.tlog.TlogRule;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The helpers the validation handlers share: PATS's field reads, the baseline folder, the rule-file lists,
 * and the XML operations (canonical comparison, element copies, serialisation) that the TLog, report and
 * packet checks use. Ported from the helpers in the Python {@code validation.py} and {@code core.py}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
final class ValidationSupport {

    /** The rule-file folders PATS reads its TLog, report and packet rules from. */
    static final String TLOG_RULES = "AutomationTests/rulefiles/tlog";
    static final String REPORT_RULES = "AutomationTests/rulefiles/reports";
    static final String PACKET_RULES = "AutomationTests/rulefiles/packets";

    /** `CAT_TLOG_IGNORE_TAGS` and `_ATTRIBUTES` are {@link TlogRule}'s; the report masks are CAT's own. */
    static final List<String> CAT_REPORT_IGNORE_TAGS = List.of("refNum");
    static final List<String> CAT_REPORT_IGNORE_ATTRIBUTES = List.of("epsSTAN");

    /** Report entries the EPS lists in no fixed order (`validation.py`'s UNORDERED_REPORT_ENTRIES). */
    static final Set<String> UNORDERED_REPORT_ENTRIES = Set.of("cardEntry", "batch", "byCashier");

    private static final Pattern PACKET_NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern OFFSET_TIME = Pattern.compile("^(\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?)[+-]\\d\\d:\\d\\d$");

    private ValidationSupport() {
    }

    // -- PATS field reads ---------------------------------------------------------------------

    /** Python's {@code _require}: the field's value, or a hard step failure when the field is absent. */
    static String require(RunnableAction action, String name) {
        String value = action.raw(name);
        if (value == null) {
            throw new PatsStepError(action.describe() + " has no " + name + " field (PATS would throw here)");
        }
        return value;
    }

    /** {@code _stem}: {@code PETROCORE-36906-1-Receipt.txt} becomes {@code PETROCORE-36906-1}. */
    static String stem(String filename, String kind) {
        return Pattern.compile("-" + Pattern.quote(kind) + "\\.\\w+$", Pattern.CASE_INSENSITIVE)
                .matcher(filename).replaceAll("");
    }

    /** Python's {@code Path(name).stem}: the name without its last suffix. */
    static String pathStem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * The directory of the case's test file, where CAT's own baselines are read and written: the Python's
     * {@code run.baseline_dir}, {@code Path(request.path).parent}. Step 5 sets it from the case entry's file, which is
     * the hand-migrated test's own folder ({@code tests/suites/cadence/} for a 57.02 Cadence case,
     * {@code cadence_55_02/} for 79667's) or {@code tests/suites/pulled} for a pulled case. A run that set none falls
     * back to the folder its case was loaded from.
     */
    static Path baselineDir(CaseRun run) {
        Optional<Path> set = run.baselineDir();
        if (set.isPresent()) {
            return set.get();
        }
        String source = run.kase().source();
        if (source != null && !source.isBlank()) {
            try {
                Path parent = Path.of(source).getParent();
                if (parent != null) {
                    return parent;
                }
            } catch (java.nio.file.InvalidPathException e) {
                // not a path: no baseline directory, as below
            }
        }
        throw new PatsStepError(run.kase().key() + ": no baseline directory for this case");
    }

    /** {@code _packet_prefix}: the case key up to its first numeric token. */
    static String packetPrefix(String caseKey) {
        String[] parts = caseKey.split("-", -1);
        for (int i = 0; i < parts.length; i++) {
            if (PACKET_NUMBER.matcher(parts[i]).matches()) {
                return String.join("-", Arrays.copyOf(parts, i + 1));
            }
        }
        throw new PatsStepError(caseKey + ": no numeric token to name its packets by (PATS throws)");
    }

    /** Python's {@code str.rstrip("\n")}. */
    static String rstripNewlines(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    /** Python's {@code str.splitlines()} for text with {@code \n} line ends: no trailing empty line. */
    static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (text.endsWith("\n")) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    // -- rule files ---------------------------------------------------------------------------

    /** The tag and attribute names a PATS rule file lists. */
    record RuleLists(List<String> tags, List<String> attributes) {

        static final RuleLists NONE = new RuleLists(List.of(), List.of());
    }

    /**
     * {@code _rule_lists}: {@code TAG_NAMES} and {@code ATTRIBUTE_NAMES} of a rule file, or nothing when it is
     * missing, as PATS's listener then ignores nothing.
     */
    static RuleLists ruleLists(ValidationContext context, String folder, String name) {
        Optional<byte[]> text = context.references().shared(folder, name);
        if (text.isEmpty()) {
            return RuleLists.NONE;
        }
        Map<String, List<String>> parsed = TlogRule.parseText(new String(text.get(), StandardCharsets.UTF_8));
        return new RuleLists(parsed.getOrDefault("TAG_NAMES", List.of()),
                parsed.getOrDefault("ATTRIBUTE_NAMES", List.of()));
    }

    /** The options a comparison uses: the rule file's lists plus CAT's extra ignores, case-insensitive values. */
    static TlogOptions options(List<String> tags, List<String> attributes) {
        return new TlogOptions(new HashSet<>(tags), new HashSet<>(attributes), true, false);
    }

    /** The tags and attributes of a list plus CAT's additions, in that order. */
    static List<String> concat(List<String> base, List<String> extra) {
        List<String> all = new ArrayList<>(base);
        all.addAll(extra);
        return List.copyOf(all);
    }

    // -- XML ----------------------------------------------------------------------------------

    /**
     * {@code _xml_diff}: empty when {@code actual} matches {@code reference} under the options, else the
     * difference. A reference no parser accepts is a failed validation, not a stop (PATS's
     * {@code PETROCORE-36862}).
     */
    static String xmlDiff(Object reference, Object actual, TlogOptions options) {
        return xmlDiff(reference, actual, options, "TLog", "TLOG-RULE.txt");
    }

    /** {@link #xmlDiff(Object, Object, TlogOptions)}, its difference naming {@code kind} and {@code rule}. */
    static String xmlDiff(Object reference, Object actual, TlogOptions options, String kind, String rule) {
        try {
            Tlog.Diff diff = Tlog.compare(reference, actual, options);
            return diff.ok() ? "" : diff.describe(25, kind, rule);
        } catch (TlogParseException e) {
            throw new ValidationFailure("the reference is not well-formed XML: " + e.getMessage());
        }
    }

    /** {@code _xml_texts}: both sides as {@link #xmlDiff} compares them, for the Validations view. */
    static String[] xmlTexts(Object reference, Object actual, TlogOptions options) {
        String shown = Tlog.canonicalise(actual, options);
        String expected;
        try {
            expected = Tlog.canonicalise(reference, options);
        } catch (RuntimeException e) {
            expected = reference instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(reference);
        }
        return new String[] {expected, shown};
    }

    /** Parses XML bytes to their root element. */
    static Element parse(byte[] xml) {
        return Tlog.parse(xml);
    }

    /** An element serialised as XML text, with no declaration: what {@code ET.tostring} gives PATS's files. */
    static String serialise(Node node) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException("cannot serialise XML: " + e.getMessage(), e);
        }
    }

    /** {@link #serialise(Node)} as UTF-8 bytes, the form the baselines are written in. */
    static byte[] serialiseBytes(Node node) {
        return serialise(node).getBytes(StandardCharsets.UTF_8);
    }

    /** Python's {@code _local}: a tag or attribute name with any namespace prefix removed. */
    static String localName(Node node) {
        String name = node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** The element children of {@code parent}, in document order. */
    static List<Element> childElements(Node parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    /** The element itself and every element below it, in document order. */
    static List<Element> elementsOf(Element root) {
        List<Element> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    private static void collect(Element element, List<Element> into) {
        into.add(element);
        for (Element child : childElements(element)) {
            collect(child, into);
        }
    }

    /**
     * Python's {@code iter()} with {@code tag}: the first element in document order whose local name is
     * {@code wanted}, or {@code null}.
     */
    static Element firstNamed(Element root, String wanted) {
        for (Element element : elementsOf(root)) {
            if (localName(element).equals(wanted)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Sorts each run of adjacent same-named sibling elements whose name is in {@code kinds}, by
     * {@code key}, in a copy of {@code root}. Every other element, and each element's own content, is left as
     * it was. The Python's {@code discounts_sorted} and {@code entries_sorted} are this.
     */
    static Element sortedRuns(Element root, Set<String> kinds, Function<Element, String> key) {
        Element copy = (Element) root.cloneNode(true);
        for (Element parent : elementsOf(copy)) {
            List<Element> children = childElements(parent);
            int i = 0;
            while (i < children.size()) {
                String kind = localName(children.get(i));
                int j = i;
                while (j < children.size() && localName(children.get(j)).equals(kind)) {
                    j++;
                }
                if (kinds.contains(kind) && j - i > 1) {
                    List<Element> run = new ArrayList<>(children.subList(i, j));
                    Element after = j < children.size() ? children.get(j) : null;
                    Comparator<Element> order = Comparator.comparing(key)
                            .thenComparing(ValidationSupport::serialise);
                    run.sort(order);
                    for (Element member : children.subList(i, j)) {
                        parent.removeChild(member);
                    }
                    for (Element member : run) {
                        if (after == null) {
                            parent.appendChild(member);
                        } else {
                            parent.insertBefore(member, after);
                        }
                    }
                }
                i = j;
            }
        }
        return copy;
    }

    /** Python's {@code _child_text}: the text of the first child with {@code name}, or "". */
    static String childText(Element element, String name) {
        for (Element child : childElements(element)) {
            if (localName(child).equals(name)) {
                return child.getTextContent() == null ? "" : child.getTextContent();
            }
        }
        return "";
    }

    /**
     * Python's {@code _without_time_offsets}: a copy of {@code root} with each time-of-day value's UTC offset
     * dropped, so a DST change does not read as a difference (PETROCORE-37048).
     */
    static Element withoutTimeOffsets(Element root) {
        Element copy = (Element) root.cloneNode(true);
        for (Element element : elementsOf(copy)) {
            String direct = directText(element);
            var match = OFFSET_TIME.matcher(direct.strip());
            if (match.matches()) {
                for (Node child = element.getFirstChild(); child != null; ) {
                    Node next = child.getNextSibling();
                    if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                        element.removeChild(child);
                    }
                    child = next;
                }
                element.insertBefore(element.getOwnerDocument().createTextNode(match.group(1)),
                        element.getFirstChild());
            }
        }
        return copy;
    }

    /** The text directly inside an element (Python's {@code element.text}), not its descendants'. */
    static String directText(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        return text.toString();
    }

    /**
     * {@code _DISCOUNT_KEYS}: each discount element's key, for the order a run of them is compared in. The
     * loyalty discounts follow their {@code programID}; a POP discount its program id.
     */
    static String discountKey(Element element) {
        return switch (localName(element)) {
            case "trlPopDisc" -> childText(element, "trlpdDiscProgramId");
            case "trloLnItemDisc", "trLoyaltyProgram" -> element.getAttribute("programID");
            default -> "";
        };
    }

    /** The element kinds whose runs {@link #discountKey} orders. */
    static final Set<String> DISCOUNT_KINDS = Set.of("trlPopDisc", "trloLnItemDisc", "trLoyaltyProgram");

    /** Python's {@code discounts_sorted}: a copy of {@code root} with each run of discounts in key order. */
    static Element discountsSorted(Element root) {
        return sortedRuns(root, DISCOUNT_KINDS, ValidationSupport::discountKey);
    }

    /**
     * Python's {@code entries_sorted}: a copy of {@code root} with each run of unordered report entries in one
     * order, by their content with what the comparison ignores left out, so an entry's STAN does not decide
     * where it sorts.
     */
    static Element entriesSorted(Element root, List<String> tags, List<String> attributes) {
        Set<String> ignoredTags = new HashSet<>(tags);
        Set<String> ignoredAttributes = new HashSet<>(attributes);
        return sortedRuns(root, UNORDERED_REPORT_ENTRIES, entry -> {
            Element bare = (Element) entry.cloneNode(true);
            for (Element element : elementsOf(bare)) {
                List<Attr> dropped = new ArrayList<>();
                for (int i = 0; i < element.getAttributes().getLength(); i++) {
                    Attr attr = (Attr) element.getAttributes().item(i);
                    if (ignoredAttributes.contains(localName(attr))) {
                        dropped.add(attr);
                    }
                }
                for (Attr attr : dropped) {
                    element.removeAttributeNode(attr);
                }
                for (Element child : childElements(element)) {
                    if (ignoredTags.contains(localName(child))) {
                        element.removeChild(child);
                    }
                }
            }
            return serialise(bare);
        });
    }

}
