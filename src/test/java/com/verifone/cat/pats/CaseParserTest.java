package com.verifone.cat.pats;

import com.verifone.cat.pats.runner.RunnableAction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link CaseParser} and {@link Action}: the PATS XML case read action by action (the Python's {@code test_pats_runner}). */
class CaseParserTest {

    private static byte[] fixture(String name) {
        try (InputStream in = CaseParserTest.class.getResourceAsStream("/pats/testcases/" + name)) {
            assertTrue(in != null, "missing fixture " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anEmptyFieldIsPresentAndAMissingOneIsNot() {
        ParsedCase parsed = CaseParser.parseActions(fixture("empty-and-missing-fields.xml"));
        assertEquals(1, parsed.steps().size());
        Action action = parsed.steps().get(0);

        assertEquals("", action.raw("VALIDATION TEXT"));
        assertEquals("", action.raw("validation text"), "PATS lower-cases field names");
        assertNull(action.raw("VALIDATION_TEXT"), "a misspelling reads as absent, as in PATS");
        assertEquals("topaz101", action.get("DEVICEID"));
    }

    @Test
    void aTestcaseWrappedInAnotherRunsTheInnerOnesActions() {
        ParsedCase parsed = CaseParser.parseActions(fixture("wrapped-testcase.xml"));
        assertEquals("DEPT-TRANS", parsed.title());
        assertEquals(List.of("TOTAL", "SLEEP"), parsed.steps().stream().map(Action::name).toList());
        assertEquals("2000", parsed.steps().get(1).get("SLEEP"));
    }

    @Test
    void actionsKeepTheirPositionAndTheirFieldsInOrder() {
        ParsedCase parsed = CaseParser.parseActions(
                "<testscript><testcase name=\"T\"><action name=\"A\"/>"
                        + "<action name=\"B\"><field name=\"K\" value=\"1\"/><field name=\"K\" value=\"2\"/></action>"
                        + "</testcase></testscript>");
        assertEquals("T", parsed.title());
        assertEquals(List.of(0, 1), parsed.steps().stream().map(Action::index).toList());
        assertEquals(List.of("K", "K"), parsed.steps().get(1).fields().stream().map(RunnableAction.ActionField::name).toList());
        assertEquals("1", parsed.steps().get(1).get("k"), "the first non-empty value wins, as PATS's map reads");
        assertEquals("#2 B(K='1', K='2')", parsed.steps().get(1).toString(), "every non-empty field is printed");
    }

    @Test
    void anEmptyFieldDefaultsWhenReadThroughGet() {
        Action action = new Action("X", List.of(new RunnableAction.ActionField("F", "")), 0);
        assertEquals("fallback", action.get("F", "fallback"));
        assertEquals("", action.get("F"));
        assertEquals("#1 X()", action.toString(), "an empty field is left out of the printed action");
    }

    @Test
    void aDocumentWithNoTestcaseIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CaseParser.parseActions("<testscript><other/></testscript>"));
    }

    @Test
    void anExternalEntityIsNotExpanded() {
        // A case file is data: a DOCTYPE with an external entity is refused, never read from disk.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CaseParser.parseActions(fixture("external-entity.xml")));
        assertFalse(refused.getMessage().isEmpty());
    }

    @Test
    void theBytesAndTheTextOfOneDocumentParseAlike() {
        byte[] bytes = "<testscript><testcase name=\"Z\"><action name=\"TOTAL\"/></testcase></testscript>"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(CaseParser.parseActions(bytes), CaseParser.parseActions(new String(bytes, StandardCharsets.UTF_8)));
    }
}
