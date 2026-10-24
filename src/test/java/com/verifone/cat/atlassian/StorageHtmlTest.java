package com.verifone.cat.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The tag scanner and block reader on small pages; the real page is covered by CataloguePageTest. */
class StorageHtmlTest {

    @Test
    void namedAndNumericEntitiesAreDecodedAndUnknownOnesAreKept() {
        assertEquals("a b & c ≤ d — e", StorageHtml.decode("a&nbsp;b &amp; c &#8804; d &mdash; e"));
        assertEquals("&nosuch; &#xZZ;", StorageHtml.decode("&nosuch; &#xZZ;"));
        assertEquals("é", StorageHtml.decode("&#xE9;"));
    }

    @Test
    void aStrongParagraphIsAHeadingButAHeadingInsideATableIsNot() {
        List<StorageHtml.Block> blocks = StorageHtml.blocks("""
                <p><strong>Viper Features:</strong></p>
                <table><tbody><tr><td><h2>Inner</h2></td></tr></tbody></table>
                <h2>Named</h2>""");
        assertEquals(3, blocks.size());
        assertEquals("Viper Features:", ((StorageHtml.Heading) blocks.get(0)).text());
        assertInstanceOf(StorageHtml.Table.class, blocks.get(1));
        // The <h2> inside the cell is not a block of its own; the one after the table is.
        assertEquals("Named", ((StorageHtml.Heading) blocks.get(2)).text());
    }

    @Test
    void aParagraphWithTextOutsideItsStrongIsNotAHeading() {
        List<StorageHtml.Block> blocks = StorageHtml.blocks("<p><strong>Bold</strong> then more</p>");
        assertTrue(blocks.isEmpty());
    }

    @Test
    void commentsAndDeclarationsAreSkippedAndAttributesQuotedOrBareAreRead() {
        List<StorageHtml.Block> blocks = StorageHtml.blocks("""
                <!DOCTYPE html><!-- <h1>hidden</h1> -->
                <table><tr><td rowspan=2 colspan='1'>a</td><td>b</td></tr><tr><td>c</td></tr></table>""");
        StorageHtml.Table table = (StorageHtml.Table) blocks.get(0);
        assertEquals(2, table.rows().size());
        assertEquals(2, table.rows().get(0).get(0).rowspan());
        assertEquals(List.of(List.of("a", "b"), List.of("a", "c")),
                StorageHtml.rectangular(table.rows()));
    }

    @Test
    void aRowspanCarriesIntoTheRowsBelowAndAColspanRepeats() {
        List<StorageHtml.Cell> first = List.of(
                new StorageHtml.Cell("x", 2, 1), new StorageHtml.Cell("y", 1, 2));
        List<StorageHtml.Cell> second = List.of(new StorageHtml.Cell("z", 1, 1));
        assertEquals(List.of(List.of("x", "y", "y"), List.of("x", "z")),
                StorageHtml.rectangular(List.of(first, second)));
    }

    @Test
    void aBrInsideACellIsASpace() {
        List<StorageHtml.Block> blocks = StorageHtml.blocks(
                "<table><tr><td>one<br>two</td></tr></table>");
        assertEquals("one two", ((StorageHtml.Table) blocks.get(0)).rows().get(0).get(0).text());
    }

    @Test
    void aTruncatedPageYieldsWhatItHadAndNeverThrows() {
        assertEquals(List.of(), StorageHtml.blocks("<table><tr><td attr=\"unclosed"));
        assertEquals(List.of(), StorageHtml.blocks("<"));
    }

    @Test
    void cleanCollapsesWhitespaceAndNoBreakSpacesAndStripsLikePython() {
        assertEquals("Cadence", StorageHtml.clean("  Cadence \n "));
        assertEquals("", StorageHtml.clean("  "));
    }
}
