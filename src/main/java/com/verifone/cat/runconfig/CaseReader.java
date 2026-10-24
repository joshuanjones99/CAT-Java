package com.verifone.cat.runconfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.verifone.cat.plans.PartialRun;

/**
 * Reads a case file's facts from the checkout: the tags, the PATS actions and {@code @plan_end}.
 *
 * <p>Tags come from {@link PartialRun#fileTags}, the actions from a PATS {@code -TestCase.xml}
 * (the {@code <testcase>}'s {@code <action>} names). A {@code .feature} written as PATS phrases
 * needs the Python CAT's phrase reader, which is not ported, so its actions are
 * {@code null}: the prerequisite check counts such a case as unreadable rather than judging it.
 * Facts are cached per file and re-read when its modification time changes.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: a heuristic over case files, the same one the Python Config
 * tab's Run scope makes. It reads files; it runs nothing.
 */
public final class CaseReader implements PartialRun.CaseActions {

    private record Stamped(long modified, CaseFacts facts) {}

    private final Path root;
    private final Map<Path, Stamped> cache = new HashMap<>();

    public CaseReader(Path repoRoot) {
        this.root = repoRoot.toAbsolutePath().normalize();
    }

    /** The facts of {@code node}'s file, or {@link CaseFacts#unknown()} when it has none on disk. */
    public synchronized CaseFacts facts(String node) {
        Path file = root.resolve(new ScopeCase("", node, "").file());
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return CaseFacts.unknown();
        }
        Stamped cached = cache.get(file);
        if (cached != null && cached.modified() == modified) {
            return cached.facts();
        }
        CaseFacts facts = read(file, node);
        cache.put(file, new Stamped(modified, facts));
        return facts;
    }

    /** The PATS action names of {@code node}'s file; empty when CAT cannot read them. */
    @Override
    public Optional<Set<String>> namesIn(String node) {
        return Optional.ofNullable(facts(node).actions());
    }

    private CaseFacts read(Path file, String node) {
        List<Set<String>> tags;
        try {
            tags = PartialRun.fileTags(file);
        } catch (RuntimeException e) {
            tags = List.of(Set.of());
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Set<String> actions = name.endsWith(".xml") ? patsActions(file) : null;
        return new CaseFacts(tags, actions, planEnd(file, name, tags));
    }

    private static boolean planEnd(Path file, String name, List<Set<String>> tags) {
        if (name.endsWith(".feature")) {
            return tags.stream().anyMatch(scenario -> scenario.contains("@plan_end"));
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains("plan_end");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The action names in a PATS test script, or {@code null} when the file does not parse. */
    static Set<String> patsActions(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // A case file is data: no DTDs, no external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(in);
            Element testcase = firstChild(document.getDocumentElement(), "testcase");
            if (testcase == null) {
                testcase = document.getDocumentElement().getTagName().equals("testcase")
                        ? document.getDocumentElement()
                        : firstDescendant(document.getDocumentElement(), "testcase");
            }
            if (testcase == null) {
                return null;
            }
            Set<String> names = new TreeSet<>();
            for (Element action : children(testcase, "action")) {
                names.add(action.getAttribute("name").strip().toUpperCase(Locale.ROOT));
            }
            return names;
        } catch (Exception e) {
            return null;
        }
    }

    private static Element firstChild(Element parent, String tag) {
        List<Element> found = children(parent, tag);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> children(Element parent, String tag) {
        java.util.ArrayList<Element> out = new java.util.ArrayList<>();
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
