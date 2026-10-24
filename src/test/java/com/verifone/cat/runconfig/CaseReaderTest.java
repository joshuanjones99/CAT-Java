package com.verifone.cat.runconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the Run scope reads from a case file: its PATS actions, its tags, and its plan_end mark. */
class CaseReaderTest {

    @TempDir
    Path root;

    @Test
    void aPatsXmlGivesItsActionNamesUpperCased() throws IOException {
        write("tests/a/PETROCORE-5-TestCase.xml", """
                <testscript><testcase name="t">
                  <action name="import_configuration"/><action name="LOGIN"/>
                </testcase></testscript>
                """);
        CaseFacts facts = new CaseReader(root).facts("tests/a/PETROCORE-5-TestCase.xml");
        assertEquals(Set.of("IMPORT_CONFIGURATION", "LOGIN"), facts.actions());
        assertEquals(Set.of("@PETROCORE-5"), facts.tags().get(0));
        assertFalse(facts.planEnd());
    }

    @Test
    void aDoctypeIsNotExpanded() throws IOException {
        write("tests/a/PETROCORE-6-TestCase.xml", """
                <!DOCTYPE testscript [<!ENTITY x SYSTEM "file:///nonexistent-secret">]>
                <testscript><testcase name="t"><action name="&x;"/></testcase></testscript>
                """);
        assertNull(new CaseReader(root).facts("tests/a/PETROCORE-6-TestCase.xml").actions());
    }

    @Test
    void aFeatureHasTagsAndNoReadableActions() throws IOException {
        write("tests/a/PETROCORE-7-TestCase.feature", """
                @fuel @plan_end
                Feature: Fuel
                  Scenario: One
                    Given a sale
                """);
        CaseFacts facts = new CaseReader(root).facts("tests/a/PETROCORE-7-TestCase.feature");
        assertNull(facts.actions());
        assertTrue(facts.planEnd());
        assertTrue(facts.tags().get(0).contains("@fuel"));
        assertEquals("@fuel @plan_end", facts.tagWords("PETROCORE-7"));
        assertTrue(new CaseReader(root).namesIn("tests/a/PETROCORE-7-TestCase.feature").isEmpty());
    }

    @Test
    void aMissingFileIsUnknownNotAnError() {
        CaseFacts facts = new CaseReader(root).facts("tests/none/PETROCORE-8-TestCase.xml");
        assertNull(facts.actions());
        assertFalse(facts.planEnd());
        assertEquals(Optional.empty(), new CaseReader(root).namesIn("tests/none/PETROCORE-8-TestCase.xml"));
    }

    private void write(String name, String text) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
