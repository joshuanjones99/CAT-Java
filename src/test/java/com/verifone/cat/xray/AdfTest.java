package com.verifone.cat.xray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.xray.Adf.Bullets;
import com.verifone.cat.xray.Adf.Heading;
import com.verifone.cat.xray.Adf.Paragraph;
import com.verifone.cat.xray.Adf.Span;
import com.verifone.cat.xray.Adf.Table;

/** The document model rendered as ADF and Markdown, as the Python's {@code cat.xray.adf} tests. */
class AdfTest {

    @Test
    void rendersTheFourBlockKindsJiraAccepts() {
        List<Adf.Block> blocks = List.of(
                new Heading("Title", 1),
                Paragraph.of(Span.bold("Result."), Span.plain(" 3 of 4 passed.")),
                new Bullets(List.of(List.of(Span.plain("one")), List.of(Span.bold("two"), Span.plain(" items")))),
                new Table(List.of("Case", "What"), List.of(List.of("PETROCORE-1", "a|b"))));
        JsonNode doc = Adf.toAdf(blocks);
        assertEquals("doc", doc.get("type").asText());
        assertEquals(1, doc.get("version").asInt());
        JsonNode content = doc.get("content");
        assertEquals("heading", content.get(0).get("type").asText());
        assertEquals(1, content.get(0).at("/attrs/level").asInt());
        assertEquals("Title", content.get(0).at("/content/0/text").asText());
        assertEquals("strong", content.get(1).at("/content/0/marks/0/type").asText());
        assertEquals(" 3 of 4 passed.", content.get(1).at("/content/1/text").asText());
        assertEquals("bulletList", content.get(2).get("type").asText());
        assertEquals("two", content.get(2).at("/content/1/content/0/content/0/text").asText());
        JsonNode table = content.get(3);
        assertEquals("table", table.get("type").asText());
        assertEquals(false, table.at("/attrs/isNumberColumnEnabled").asBoolean(true));
        assertEquals("tableHeader", table.at("/content/0/content/0/type").asText());
        assertEquals("tableCell", table.at("/content/1/content/0/type").asText());
        assertEquals("a|b", table.at("/content/1/content/1/content/0/content/0/text").asText());
    }

    @Test
    void markdownRendersTheSameBlocks() {
        String markdown = Adf.toMarkdown(List.of(
                new Heading("Title", 1),
                Paragraph.of(Span.bold("Result."), Span.plain(" 3 of 4 passed.")),
                new Bullets(List.of(List.of(Span.bold("two"), Span.plain(" items")))),
                new Table(List.of("Case", "What"), List.of(List.of("PETROCORE-1", "a|b")))));
        assertTrue(markdown.contains("# Title"));
        assertTrue(markdown.contains("**Result.** 3 of 4 passed."));
        assertTrue(markdown.contains("- **two** items"));
        assertTrue(markdown.contains("| PETROCORE-1 | a\\|b |"));
        assertTrue(markdown.endsWith("\n"));
    }

    @Test
    void anEmptySpanIsDroppedNotEmitted() {
        JsonNode doc = Adf.toAdf(List.of(Paragraph.of(Span.plain(""), Span.bold(""))));
        assertEquals("{\"type\":\"paragraph\"}", doc.get("content").get(0).toString());
    }
}
