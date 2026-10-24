package com.verifone.cat.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Writes {@code junit.xml} from a run's events: pytest's results, plus what a CI reader
 * needs to know that pytest does not say.
 *
 * <p>The Python CAT's {@code petrotest.report.junit.JunitXMLWriter}, and the shape is the
 * same: a {@code <testsuites>} root with one {@code <testsuite name="CAT Run">}, its
 * {@code <properties>} (rig, run_id, test_plans, fake_pos, one {@code preflight.<name>} per
 * check), one {@code <testcase>} per test, and one per planned case with no migrated test
 * file. {@code tests/unit/test_petrotest_report.py} is the reference for that shape.
 *
 * <p>Three decisions are the Python CAT's and are kept on purpose:
 * <ul>
 *   <li>The suite's {@code tests} counts the plan's unmigrated cases as well. Reporting
 *       only the migrated subset reads a 39-case plan as a 30-case pass rate.</li>
 *   <li>{@code fake_pos} is always written, {@code "true"} or {@code "false"}, so absence
 *       cannot be read as hardware.</li>
 *   <li>A failure's {@code type} is the exception that was raised, and it is omitted when
 *       the recording does not know it. An assumed {@code AssertionError} would be a
 *       fabricated fact in an artifact a CI reader trusts.</li>
 * </ul>
 *
 * <p>Built with the JDK's DOM and Transformer, with no extra dependency. Attribute order
 * is the DOM's, not the insertion order the Python used; XML does not treat attribute
 * order as meaningful, so the two files are equivalent.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class JunitXmlWriter {

    /** The file's name in a run folder. */
    public static final String JUNIT_FILE = "junit.xml";

    /** The longest {@code message} attribute: a CI list view shows only its head. */
    static final int MESSAGE_CHARS = 200;

    /** One test's outcome as its {@code test.finished} event gave it. */
    record TestResult(String outcome, double duration, String excType, String detail) {}

    private final RunFacts facts = new RunFacts();
    private final Map<String, TestResult> tests = new TreeMap<>();

    /** Folds one event into this writer. Events it does not use are ignored. */
    public void processEvent(JsonNode event) {
        if (facts.fold(event)) {
            return;
        }
        if (!"test.finished".equals(RunFacts.text(event, "type"))) {
            return;
        }
        String nodeid = RunFacts.text(event, "nodeid");
        if (nodeid == null || nodeid.isEmpty()) {
            return;
        }
        JsonNode duration = event.get("duration");
        tests.put(nodeid, new TestResult(
                orEmpty(RunFacts.text(event, "outcome")),
                duration == null || duration.isNull() ? 0.0 : duration.asDouble(),
                orEmpty(RunFacts.text(event, "exc_type")),
                orEmpty(RunFacts.text(event, "detail"))));
    }

    /** The folded state, for a caller that reports on it too. */
    public RunFacts facts() {
        return facts;
    }

    /**
     * Writes {@code <artifactsDir>/junit.xml}, creating the folder if needed.
     *
     * @throws IOException when the file cannot be written
     */
    public void write(Path artifactsDir) throws IOException {
        Files.createDirectories(artifactsDir);
        Document doc = newDocument();

        Element testsuites = doc.createElement("testsuites");
        doc.appendChild(testsuites);

        Element suite = doc.createElement("testsuite");
        suite.setAttribute("name", "CAT Run");
        suite.setAttribute("tests", String.valueOf(tests.size() + facts.notRunCases().size()));
        testsuites.appendChild(suite);

        writeProperties(doc, suite);

        int passed = 0;
        int failed = 0;
        int errors = 0;
        int skipped = facts.notRunCases().size();
        for (Map.Entry<String, TestResult> entry : tests.entrySet()) {
            String nodeid = entry.getKey();
            TestResult test = entry.getValue();
            Element testcase = doc.createElement("testcase");
            String[] parts = splitNodeid(nodeid);
            testcase.setAttribute("classname", classname(parts[0]));
            testcase.setAttribute("name", parts[1]);
            testcase.setAttribute("time", String.format(Locale.ROOT, "%.3f", test.duration()));
            suite.appendChild(testcase);

            switch (test.outcome()) {
                case "passed" -> passed++;
                case "failed", "error", "timeout" -> {
                    // Counted by the element written, so the suite's counts agree with the
                    // <failure> and <error> elements a JUnit consumer finds.
                    boolean isFailure = "failed".equals(test.outcome());
                    if (isFailure) {
                        failed++;
                    } else {
                        errors++;
                    }
                    Element element = doc.createElement(isFailure ? "failure" : "error");
                    failureAttributes(test.excType(), test.detail())
                            .forEach(element::setAttribute);
                    if (!test.detail().isEmpty()) {
                        element.setTextContent(test.detail());
                    }
                    testcase.appendChild(element);
                }
                case "skipped" -> {
                    skipped++;
                    testcase.appendChild(doc.createElement("skipped"));
                }
                default -> {
                    // An outcome this writer does not count (an xfail, say) is written as a
                    // bare testcase, as the Python writer does.
                }
            }
            writeRungs(doc, testcase, nodeid);
        }

        writeNotMigrated(doc, suite);

        suite.setAttribute("passed", String.valueOf(passed));
        suite.setAttribute("failures", String.valueOf(failed));
        suite.setAttribute("errors", String.valueOf(errors));
        suite.setAttribute("skipped", String.valueOf(skipped));

        serialise(doc, artifactsDir.resolve(JUNIT_FILE));
    }

    /**
     * Reads {@code events} (a run's {@code events.ndjson}) and returns a writer holding its
     * state, ready for {@link #write}. The artifacts folder is the file's parent.
     */
    public static JunitXmlWriter fromEvents(Path events) throws IOException {
        JunitXmlWriter writer = new JunitXmlWriter();
        NdjsonReader.forEach(events, writer::processEvent);
        return writer;
    }

    /** {@code <properties>}: rig, run_id, test_plans, fake_pos, then one per preflight check. */
    private void writeProperties(Document doc, Element suite) {
        Element properties = doc.createElement("properties");
        suite.appendChild(properties);
        if (facts.rig() != null) {
            property(doc, properties, "rig", facts.rig());
        }
        if (facts.runId() != null) {
            property(doc, properties, "run_id", facts.runId());
        }
        if (!facts.testPlans().isEmpty()) {
            property(doc, properties, "test_plans", String.join(",", facts.testPlans()));
        }
        // Always written, both ways round: "absent" must not be readable as "hardware".
        property(doc, properties, "fake_pos", facts.fakePos() ? "true" : "false");
        for (RunFacts.PreflightCheck check : facts.preflightChecks()) {
            property(doc, properties, "preflight." + check.name(),
                    check.outcome() + " (" + check.phase() + ")");
        }
    }

    /** One {@code <testcase>} per planned case with no migrated test file. */
    private void writeNotMigrated(Document doc, Element suite) {
        for (Map.Entry<String, String> planned : new TreeMap<>(facts.notRunCases()).entrySet()) {
            String key = planned.getKey();
            String summary = planned.getValue();
            Element testcase = doc.createElement("testcase");
            testcase.setAttribute("classname", "not-migrated");
            testcase.setAttribute("name", key);
            testcase.setAttribute("time", "0.000");
            suite.appendChild(testcase);
            Element skipped = doc.createElement("skipped");
            String message = "not migrated: no pytest file for " + key
                    + (summary.isEmpty() ? "" : " (" + summary + ")");
            skipped.setAttribute("message", message);
            testcase.appendChild(skipped);
        }
    }

    /** The recovery rungs a test climbed, as {@code recovery.rung_<n>} properties. */
    private void writeRungs(Document doc, Element testcase, String nodeid) {
        List<RunFacts.Rung> rungs = facts.recoveryRungs().get(nodeid);
        if (rungs == null) {
            return;
        }
        Element properties = doc.createElement("properties");
        testcase.appendChild(properties);
        for (RunFacts.Rung rung : rungs) {
            property(doc, properties, "recovery.rung_" + rung.rung(), rung.outcome());
        }
    }

    /**
     * The {@code type} and {@code message} attributes of a failure, each omitted when unknown.
     * {@code message} is the first line of the detail, cut to {@link #MESSAGE_CHARS}.
     */
    static Map<String, String> failureAttributes(String excType, String detail) {
        Map<String, String> attrs = new TreeMap<>();
        if (!excType.isEmpty()) {
            attrs.put("type", excType);
        }
        if (!detail.isEmpty()) {
            String firstLine = detail.lines().findFirst().orElse("");
            int end = firstLine.offsetByCodePoints(0, Math.min(MESSAGE_CHARS,
                    firstLine.codePointCount(0, firstLine.length())));
            attrs.put("message", firstLine.substring(0, end));
        }
        return attrs;
    }

    /** The node id's module and test name: {@code path::name} splits on the last {@code ::}. */
    static String[] splitNodeid(String nodeid) {
        int at = nodeid.lastIndexOf("::");
        if (at < 0) {
            return new String[] {nodeid, nodeid};
        }
        return new String[] {nodeid.substring(0, at), nodeid.substring(at + 2)};
    }

    /**
     * The module as a dotted classname, as the Python writer makes it: a {@code .feature}
     * suffix is dropped, separators become dots, and {@code .py} is removed.
     */
    static String classname(String module) {
        String withoutFeature = module.endsWith(".feature")
                ? module.substring(0, module.length() - ".feature".length())
                : module;
        return withoutFeature.replace("/", ".").replace("\\", ".").replace(".py", "");
    }

    private static void property(Document doc, Element parent, String name, String value) {
        Element element = doc.createElement("property");
        element.setAttribute("name", name);
        element.setAttribute("value", value);
        parent.appendChild(element);
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static Document newDocument() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the JDK's XML DOM is unavailable", e);
        }
    }

    private static void serialise(Document doc, Path target) throws IOException {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            try (var out = Files.newOutputStream(target)) {
                transformer.transform(new DOMSource(doc), new StreamResult(out));
            }
        } catch (TransformerException e) {
            throw new IOException("could not write " + target, e);
        }
    }
}
