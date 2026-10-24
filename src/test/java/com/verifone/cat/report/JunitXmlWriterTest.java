package com.verifone.cat.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;


/**
 * The JUnit writer against the Python CAT's shape: the fixture is the sample events of
 * {@code tests/unit/test_petrotest_report.py}, and the assertions are that file's.
 */
class JunitXmlWriterTest {

    @TempDir
    Path dir;

    /** Writes the sample run's events to a temp folder and returns the JUnit file written from them. */
    private Document writeSample() throws Exception {
        Path events = dir.resolve(ReportPaths.EVENTS_FILE);
        try (InputStream in = getClass().getResourceAsStream("/report/sample-events.ndjson")) {
            Files.copy(in, events);
        }
        JunitXmlWriter.fromEvents(events).write(dir);
        return parse(dir.resolve(JunitXmlWriter.JUNIT_FILE));
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e && e.getTagName().equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element suite(Document doc) {
        return children(doc.getDocumentElement(), "testsuite").get(0);
    }

    private static Map<String, String> properties(Element owner) {
        Map<String, String> props = new HashMap<>();
        for (Element properties : children(owner, "properties")) {
            for (Element p : children(properties, "property")) {
                props.put(p.getAttribute("name"), p.getAttribute("value"));
            }
        }
        return props;
    }

    private static Element testcase(Element suite, String name) {
        return children(suite, "testcase").stream()
                .filter(t -> t.getAttribute("name").equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rootIsTestsuitesWithOneCatRunSuite() throws Exception {
        Document doc = writeSample();

        assertEquals("testsuites", doc.getDocumentElement().getTagName());
        assertEquals(1, children(doc.getDocumentElement(), "testsuite").size());
        assertEquals("CAT Run", suite(doc).getAttribute("name"));
    }

    @Test
    void countsThePlanNotJustTheMigratedSubset() throws Exception {
        Element suite = suite(writeSample());

        // 5 tests reported + 2 planned cases with no migrated file.
        assertEquals("7", suite.getAttribute("tests"));
        assertEquals("1", suite.getAttribute("passed"));
        assertEquals("1", suite.getAttribute("failures"));
        assertEquals("2", suite.getAttribute("errors"));
        assertEquals("3", suite.getAttribute("skipped"));
    }

    @Test
    void suiteCountsMatchTheElementsWritten() throws Exception {
        Element suite = suite(writeSample());

        assertEquals(suite.getAttribute("failures"), String.valueOf(suite.getElementsByTagName("failure").getLength()));
        assertEquals(suite.getAttribute("errors"), String.valueOf(suite.getElementsByTagName("error").getLength()));
        assertEquals(suite.getAttribute("skipped"), String.valueOf(suite.getElementsByTagName("skipped").getLength()));
    }

    @Test
    void recordsRigRunIdTestPlansAndFakePosAlwaysAsText() throws Exception {
        Map<String, String> props = properties(suite(writeSample()));

        assertEquals("test-rig", props.get("rig"));
        assertEquals("run-20260804-001", props.get("run_id"));
        // Absent from the recording, and still written: "absent" must not read as hardware.
        assertEquals("false", props.get("fake_pos"));
        assertFalse(props.containsKey("test_plans"));
    }

    @Test
    void preflightChecksAreSuiteProperties() throws Exception {
        Map<String, String> props = properties(suite(writeSample()));

        assertEquals("pass (verify)", props.get("preflight.system_checks"));
        assertEquals("error (verify)", props.get("preflight.commander_connection"));
        assertEquals("repaired (repair)", props.get("preflight.rig_lease"));
    }

    @Test
    void recoveryRungsAreTestcaseProperties() throws Exception {
        Element failing = testcase(suite(writeSample()), "test_fail");

        assertEquals("pass", properties(failing).get("recovery.rung_0"));
    }

    @Test
    void failureTakesItsTypeAndMessageFromTheEvent() throws Exception {
        Element failure = (Element) testcase(suite(writeSample()), "test_fail")
                .getElementsByTagName("failure").item(0);

        assertEquals("AssertionError", failure.getAttribute("type"));
        assertEquals("receipt line 3 differs", failure.getAttribute("message"));
        assertTrue(failure.getTextContent().contains("expected: CHARITY 1.00"));
    }

    @Test
    void errorsAndTimeoutsAreErrorElements() throws Exception {
        Element suite = suite(writeSample());

        // pytest.fail is "Failed"; it is not renamed AssertionError.
        Element error = (Element) testcase(suite, "test_error").getElementsByTagName("error").item(0);
        assertEquals("Failed", error.getAttribute("type"));
        assertEquals(1, testcase(suite, "test_timeout").getElementsByTagName("error").getLength());
    }

    @Test
    void unmigratedCasesAreSkippedWithTheirSummary() throws Exception {
        Element suite = suite(writeSample());

        Element withSummary = testcase(suite, "PETROCORE-61391");
        assertEquals("not-migrated", withSummary.getAttribute("classname"));
        assertEquals("0.000", withSummary.getAttribute("time"));
        Element skipped = (Element) withSummary.getElementsByTagName("skipped").item(0);
        assertEquals("not migrated: no pytest file for PETROCORE-61391 (Charity: receipt check)",
                skipped.getAttribute("message"));
        Element bare = (Element) testcase(suite, "PETROCORE-37043").getElementsByTagName("skipped").item(0);
        assertEquals("not migrated: no pytest file for PETROCORE-37043", bare.getAttribute("message"));
    }

    @Test
    void classnameIsTheModuleAsADottedPath() {
        assertEquals("tests.suites.test.test_pass", JunitXmlWriter.classname("tests/suites/test/test_pass.py"));
        assertEquals("tests.x.PETROCORE-1-TestCase",
                JunitXmlWriter.classname("tests/x/PETROCORE-1-TestCase.feature"));
        assertEquals("tests.x.y", JunitXmlWriter.classname("tests\\x\\y.py"));
    }

    @Test
    void nodeidSplitsOnTheLastDoubleColon() {
        String[] parts = JunitXmlWriter.splitNodeid("tests/x.feature::Scenario::step_one");

        assertEquals("tests/x.feature::Scenario", parts[0]);
        assertEquals("step_one", parts[1]);
    }

    @Test
    void aFailureWithNoReasonIsBareNotGuessed() throws Exception {
        Path events = dir.resolve(ReportPaths.EVENTS_FILE);
        Files.writeString(events,
                "{\"type\":\"test.finished\",\"nodeid\":\"tests/x.py::t\",\"outcome\":\"failed\"}\n");

        JunitXmlWriter.fromEvents(events).write(dir);
        Element failure = (Element) parse(dir.resolve(JunitXmlWriter.JUNIT_FILE))
                .getElementsByTagName("failure").item(0);

        assertFalse(failure.hasAttribute("type"));
        assertFalse(failure.hasAttribute("message"));
        assertEquals("", failure.getTextContent());
    }

    @Test
    void failureMessageIsTheFirstLineCutToTwoHundredCharacters() {
        Map<String, String> attrs = JunitXmlWriter.failureAttributes("", "first\nsecond");
        assertEquals("first", attrs.get("message"));
        assertFalse(attrs.containsKey("type"));

        String long200 = "x".repeat(300);
        assertEquals(200, JunitXmlWriter.failureAttributes("E", long200).get("message").length());
    }

    @Test
    void detailBodyKeepsUtf8ThroughTheWriter() throws Exception {
        String detail = "Ёлка — £1.00 ✖";
        Path events = dir.resolve(ReportPaths.EVENTS_FILE);
        Files.writeString(events,
                "{\"type\":\"test.finished\",\"nodeid\":\"tests/x.py::t\",\"outcome\":\"failed\",\"detail\":\""
                        + detail + "\"}\n", StandardCharsets.UTF_8);

        JunitXmlWriter.fromEvents(events).write(dir);
        Element failure = (Element) parse(dir.resolve(JunitXmlWriter.JUNIT_FILE))
                .getElementsByTagName("failure").item(0);

        assertEquals(detail, failure.getTextContent());
    }

    @Test
    void theFileIsAnXmlDeclaredUtf8DocumentOnDisk() throws Exception {
        writeSample();

        String head = Files.readString(dir.resolve(JunitXmlWriter.JUNIT_FILE), StandardCharsets.UTF_8);
        assertTrue(head.startsWith("<?xml"));
        assertTrue(head.contains("UTF-8"));
    }

    @Test
    void theLastEventForANodeidWins() throws Exception {
        Path events = dir.resolve(ReportPaths.EVENTS_FILE);
        Files.write(events, List.of(
                "{\"type\":\"test.finished\",\"nodeid\":\"a.py::a\",\"outcome\":\"passed\"}",
                "{\"type\":\"test.finished\",\"nodeid\":\"a.py::a\",\"outcome\":\"skipped\"}"),
                StandardCharsets.UTF_8);

        JunitXmlWriter.fromEvents(events).write(dir);
        Element suite = suite(parse(dir.resolve(JunitXmlWriter.JUNIT_FILE)));

        // Keyed by node id as the Python writer keys it: one testcase, and the later outcome.
        assertEquals("1", suite.getAttribute("tests"));
        assertEquals("0", suite.getAttribute("passed"));
        assertEquals("1", suite.getAttribute("skipped"));
    }
}
