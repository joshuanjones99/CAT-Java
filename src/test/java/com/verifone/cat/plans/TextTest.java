package com.verifone.cat.plans;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void cleanCollapsesTabsAndNewlinesNotJustSpaces() {
        assertEquals("Cadence sign on", Text.clean("Cadence\tsign\n\n  on\r\n"));
    }

    @Test
    void cleanTurnsNoBreakSpacesIntoSpacesAndCollapsesThemWithTheRest() {
        assertEquals("Cadence sign on", Text.clean("Cadence sign   on"));
    }

    @Test
    void cleanCollapsesUnicodeSpacesAsPythonsStrDoes() {
        assertEquals("a b", Text.clean("a b"));
    }

    @Test
    void cleanStripsAndLeavesPlainTextAlone() {
        assertEquals("", Text.clean(" \t\n "));
        assertEquals("PETROCORE-36847 includes", Text.clean("PETROCORE-36847 includes"));
    }
}
