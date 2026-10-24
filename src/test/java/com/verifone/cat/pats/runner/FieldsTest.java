package com.verifone.cat.pats.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The field reads PATS's way, and an action reduced to the fields the runner reads. */
class FieldsTest {

    private static final String ITEM = "item-element";

    @Test
    void anEmptyFieldIsPresentAndAMissingOneIsNot() {
        RunnableAction action = TestAction.of(0, "VALIDATION_BY_TEXT",
                "VALIDATION TEXT", "", "DEVICEID", "topaz101", "DEVICEID", "topaz101");

        assertEquals("", action.raw("VALIDATION TEXT"));
        assertEquals("", action.raw("validation text"), "PATS lowercases field names");
        assertNull(action.raw("VALIDATION_TEXT"), "a misspelling reads as absent, as in PATS");
        assertEquals("topaz101", action.get("DEVICEID"));
    }

    @Test
    void theFirstNonEmptyValueWinsAndDefaultsApplyToAbsentAndEmptyAlike() {
        RunnableAction action = TestAction.of(0, "X", "F", "", "F", "a", "F", "b");

        assertEquals("a", action.get("F"));
        assertEquals("POS", TestAction.of(0, "X", "DEVICE", "").get("DEVICE", "POS"));
        assertEquals("POS", TestAction.of(0, "X").get("DEVICE", "POS"));
    }

    @Test
    void describeNamesTheActionAndItsNonEmptyFieldsFromOne() {
        RunnableAction action = TestAction.of(1, "WIDGET_VALIDATION",
                "WEB ELEMENT", "x-id", "DEVICEID", "topaz101", "VALIDATION KEY", "CSR_FCT", "EMPTY", "");

        assertEquals("#2 WIDGET_VALIDATION(WEB ELEMENT='x-id', VALIDATION KEY='CSR_FCT')", action.describe());
    }

    @Test
    void canonicalFormDefaultsTheDeviceAndKeepsTheDeviceIdOnlyForADcr() {
        Fields.Canonical pos = Fields.canonical(TestAction.of(0, "TOUCH_EVENT", "WEB ELEMENT", ITEM,
                "DEVICEID", "topaz101"));
        Fields.Canonical dcr = Fields.canonical(TestAction.of(0, "TOUCH_EVENT", "WEB ELEMENT", ITEM,
                "DEVICE", "dcr1", "DEVICEID", "dcr1"));

        assertEquals(new Fields.CanonicalField("DEVICE", "POS"), pos.fields().get(0));
        assertEquals(new Fields.CanonicalField("DEVICEID", ""), pos.fields().get(1));
        assertEquals(new Fields.CanonicalField("DEVICE", "DCR1"), dcr.fields().get(0));
        assertEquals(new Fields.CanonicalField("DEVICEID", "DCR1"), dcr.fields().get(1));
    }

    @Test
    void canonicalFormReadsTheElementTypeWithItsIdDefaultAndKeepsAbsentRequiredFieldsAbsent() {
        Fields.Canonical absent = Fields.canonical(TestAction.of(0, "TOUCH_EVENT"));
        Fields.Canonical empty = Fields.canonical(TestAction.of(0, "TOUCH_EVENT", "WEB ELEMENT", ""));

        assertEquals(new Fields.CanonicalField("TYPE[ID/XPATH]", "ID"), absent.fields().get(3));
        assertEquals(new Fields.CanonicalField("WEB ELEMENT", null), absent.fields().get(2),
                "WEB ELEMENT is required, so absent stays apart from empty");
        assertEquals(new Fields.CanonicalField("WEB ELEMENT", ""), empty.fields().get(2));
    }

    @Test
    void canonicalFormIgnoresFieldsTheHandlerDoesNotRead() {
        Fields.Canonical bare = Fields.canonical(TestAction.of(0, "TOUCH_EVENT", "WEB ELEMENT", ITEM));
        Fields.Canonical padded = Fields.canonical(TestAction.of(0, "TOUCH_EVENT", "WEB ELEMENT", ITEM,
                "PLU", "145397", "IDC", "3"));

        assertEquals(bare, padded);
    }

    @Test
    void theNameIsKeptAsWrittenInTheCanonicalForm() {
        assertEquals("sleep", Fields.canonical(TestAction.of(0, "sleep", "SLEEP", "1000")).name());
    }

    @Test
    void theTableListsWhatEachHandlerReads() {
        assertEquals(List.of("WEB ELEMENT", "TYPE[ID/XPATH]"), Fields.FIELDS_READ.get("TOUCH_EVENT"));
        assertTrue(Fields.FIELDS_READ.get("SLEEP").contains("SLEEP"));
        assertFalse(Fields.REQUIRED.contains("DEVICE"), "DEVICE has its own default, not a required read");
    }
}
