package com.verifone.cat.devices.viperpay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The exact and bracketed prompt texts, branch for branch, as PATS's {@code getPrompt} renders them.
 * Ported from the Python CAT's {@code tests/unit/test_devices_viperpay.py} prompt cases.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class PromptTextTest {

    private static List<String> lines(String... values) {
        return Arrays.asList(values);
    }

    static Stream<Arguments> exactTexts() {
        return Stream.of(
                // CAT's deviation (rig owner, 2026-10-20): a line of blanks is dropped like an empty one.
                Arguments.of(lines("A", "   ", "B"), "A,B"),
                Arguments.of(lines("WELCOME TO OUR STORE", "INSERT TAP OR SWIPE CARD", " "),
                        "WELCOME TO OUR STORE,INSERT TAP OR SWIPE CARD"),
                Arguments.of(lines(" ", "WELCOME TO OUR STORE"), "WELCOME TO OUR STORE"),
                Arguments.of(lines(null, " A ", "", "B\t"), "A,B"),
                // Java's trim strips characters at or below U+0020 only: a no-break space stays.
                Arguments.of(lines(" A"), " A"),
                Arguments.of(lines("\u0001A\u001f"), "A"),
                Arguments.of(lines(), ""));
    }

    @ParameterizedTest
    @MethodSource("exactTexts")
    void theExactTextFollowsGetPromptBranchForBranch(List<String> display, String text) {
        assertEquals(text, PromptText.exactText(display));
    }

    @Test
    void theBracketedTextKeepsEveryLineAndJoinsWithCommaSpace() {
        assertEquals("[Total: $20.72, Add a Tip ?]", PromptText.bracketedText(lines("Total: $20.72", "Add a Tip ?")));
        assertEquals("[WELCOME TO OUR STORE, INSERT TAP OR SWIPE CARD, ]",
                PromptText.bracketedText(lines("WELCOME TO OUR STORE", "INSERT TAP OR SWIPE CARD", "")));
        assertEquals("[null, A]", PromptText.bracketedText(lines(null, "A")));
    }

    static Stream<Arguments> exactMatches() {
        List<String> welcome = lines("WELCOME TO OUR STORE", "INSERT TAP OR SWIPE CARD");
        return Stream.of(
                Arguments.of(welcome, "WELCOME TO OUR STORE,INSERT TAP OR SWIPE CARD", true),
                Arguments.of(welcome, "  welcome to our store,insert tap or swipe card ", true),
                Arguments.of(welcome, "WELCOME TO OUR STORE", false), // a substring is not the display
                Arguments.of(welcome, "WELCOME TO OUR STORE INSERT TAP OR SWIPE CARD", false),
                Arguments.of(welcome, "WELCOME TO OUR STORE, INSERT TAP OR SWIPE CARD", false));
    }

    @ParameterizedTest
    @MethodSource("exactMatches")
    void anExactMatchIsTheWholeDisplayIgnoringCase(List<String> display, String prompt, boolean matched) {
        assertEquals(matched, PromptText.matchesExactly(display, prompt));
    }

    static Stream<Arguments> promptMatches() {
        return Stream.of(
                // Arrays.toString keeps every line, empty ones too: Smart Suggestion's key (run 501cd89b458b).
                Arguments.of(lines("WELCOME TO OUR STORE", "INSERT TAP OR SWIPE CARD", ""),
                        "WELCOME TO OUR STORE, INSERT TAP OR SWIPE CARD, ", true),
                Arguments.of(lines("Total: $20.72", "Add a Tip ?"), "Total: $20.72, Add a Tip ?", true), // Tipping
                Arguments.of(lines("DO YOU WANT", "CASHBACK ", ""), "DO YOU WANT, CASHBACK ", true),
                Arguments.of(lines("Total: $20.72", "Add a Tip ?"), "Total: $19.00, Add a Tip ?", false),
                // CAT's earlier, space-joined reading still matches what it matched.
                Arguments.of(lines("WELCOME TO OUR STORE", "INSERT TAP OR SWIPE CARD"), "STORE INSERT", true),
                Arguments.of(lines("SWIPE CARD"), "swipe card", true),
                Arguments.of(lines(null, "A"), "null, A", true));
    }

    @ParameterizedTest
    @MethodSource("promptMatches")
    void aPromptIsFoundAsPatsRendersTheDisplay(List<String> display, String prompt, boolean matched) {
        assertEquals(matched, PromptText.matchesPrompt(display, prompt));
    }

    @Test
    void javaTrimStripsOnlyCharactersAtOrBelowSpace() {
        assertEquals("a", PromptText.javaTrim("\u0000 a\t\n"));
        assertEquals(" a", PromptText.javaTrim(" a"));
    }
}
