package com.verifone.cat.xray;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The summary's document model, rendered two ways: Jira's ADF, and Markdown. Port of the Python
 * CAT's {@code cat.xray.adf}.
 *
 * <p>Jira Cloud's v3 REST API takes issue descriptions and comments only as Atlassian Document
 * Format (ADF). The summary is written once as a short list of blocks; {@link #toAdf} renders the
 * tree Jira accepts, {@link #toMarkdown} renders the same blocks for {@code xray-summary.md}.
 *
 * <p>Only the subset the summary needs: a bold span is the one inline mark. An empty text node is
 * invalid ADF, so empty spans are dropped rather than emitted.
 */
public final class Adf {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Adf() {}

    /** One block of the summary. */
    public sealed interface Block permits Heading, Paragraph, Bullets, Table {}

    /** One run of inline text: plain, or bold. */
    public record Span(String text, boolean bold) {

        /** Plain text. */
        public static Span plain(String text) {
            return new Span(text, false);
        }

        /** Bold text. */
        public static Span bold(String text) {
            return new Span(text, true);
        }
    }

    /** A heading; level 2 unless the caller says otherwise. */
    public record Heading(String text, int level) implements Block {

        /** A level-2 heading. */
        public static Heading of(String text) {
            return new Heading(text, 2);
        }
    }

    /** A paragraph of spans. */
    public record Paragraph(List<Span> spans) implements Block {

        public Paragraph {
            spans = List.copyOf(spans);
        }

        /** A paragraph of the given spans. */
        public static Paragraph of(Span... spans) {
            return new Paragraph(List.of(spans));
        }
    }

    /** A bulleted list; each item is a line of spans. */
    public record Bullets(List<List<Span>> items) implements Block {

        public Bullets {
            List<List<Span>> copy = new ArrayList<>();
            items.forEach(item -> copy.add(List.copyOf(item)));
            items = List.copyOf(copy);
        }

        /** One single-span item per text. */
        public static Bullets ofText(List<String> texts) {
            List<List<Span>> items = new ArrayList<>();
            texts.forEach(text -> items.add(List.of(Span.plain(text))));
            return new Bullets(items);
        }
    }

    /** A table: a header row and body rows of plain text. */
    public record Table(List<String> header, List<List<String>> rows) implements Block {

        public Table {
            header = List.copyOf(header);
            List<List<String>> copy = new ArrayList<>();
            rows.forEach(row -> copy.add(List.copyOf(row)));
            rows = List.copyOf(copy);
        }
    }

    /** The ADF document Jira's {@code description} field and comment {@code body} take. */
    public static ObjectNode toAdf(List<? extends Block> blocks) {
        ObjectNode doc = NODES.objectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");
        for (Block block : blocks) {
            content.add(block(block));
        }
        return doc;
    }

    /** The same blocks as Markdown, for {@code xray-summary.md}. */
    public static String toMarkdown(List<? extends Block> blocks) {
        List<String> out = new ArrayList<>();
        for (Block block : blocks) {
            if (block instanceof Heading heading) {
                out.add("#".repeat(heading.level()) + " " + heading.text());
            } else if (block instanceof Paragraph paragraph) {
                out.add(markdownSpans(paragraph.spans()));
            } else if (block instanceof Bullets bullets) {
                List<String> lines = new ArrayList<>();
                bullets.items().forEach(item -> lines.add("- " + markdownSpans(item)));
                out.add(String.join("\n", lines));
            } else if (block instanceof Table table) {
                out.add(markdownTable(table));
            }
        }
        return String.join("\n\n", out) + "\n";
    }

    private static ObjectNode block(Block block) {
        if (block instanceof Heading heading) {
            ObjectNode node = NODES.objectNode();
            node.put("type", "heading");
            node.putObject("attrs").put("level", heading.level());
            node.set("content", textNodes(List.of(Span.plain(heading.text()))));
            return node;
        }
        if (block instanceof Paragraph paragraph) {
            return paragraph(paragraph.spans());
        }
        if (block instanceof Bullets bullets) {
            ObjectNode node = NODES.objectNode();
            node.put("type", "bulletList");
            ArrayNode items = node.putArray("content");
            for (List<Span> item : bullets.items()) {
                ObjectNode listItem = items.addObject();
                listItem.put("type", "listItem");
                listItem.putArray("content").add(paragraph(item));
            }
            return node;
        }
        Table table = (Table) block;
        ObjectNode node = NODES.objectNode();
        node.put("type", "table");
        ObjectNode attrs = node.putObject("attrs");
        attrs.put("isNumberColumnEnabled", false);
        attrs.put("layout", "default");
        ArrayNode rows = node.putArray("content");
        ObjectNode header = rows.addObject();
        header.put("type", "tableRow");
        ArrayNode headerCells = header.putArray("content");
        for (String text : table.header()) {
            headerCells.add(cell("tableHeader", text));
        }
        for (List<String> row : table.rows()) {
            ObjectNode bodyRow = rows.addObject();
            bodyRow.put("type", "tableRow");
            ArrayNode cells = bodyRow.putArray("content");
            for (String text : row) {
                cells.add(cell("tableCell", text));
            }
        }
        return node;
    }

    private static ObjectNode cell(String kind, String text) {
        ObjectNode node = NODES.objectNode();
        node.put("type", kind);
        node.putObject("attrs");
        node.putArray("content").add(paragraph(List.of(Span.plain(text))));
        return node;
    }

    /** A paragraph; an empty one is valid ADF, an empty text node is not. */
    private static ObjectNode paragraph(List<Span> spans) {
        ObjectNode node = NODES.objectNode();
        node.put("type", "paragraph");
        ArrayNode content = textNodes(spans);
        if (content.size() > 0) {
            node.set("content", content);
        }
        return node;
    }

    private static ArrayNode textNodes(List<Span> spans) {
        ArrayNode nodes = NODES.arrayNode();
        for (Span span : spans) {
            if (span.bold()) {
                if (!span.text().isEmpty()) {
                    ObjectNode node = nodes.addObject();
                    node.put("type", "text");
                    node.put("text", span.text());
                    node.putArray("marks").addObject().put("type", "strong");
                }
            } else if (!span.text().isEmpty()) {
                nodes.addObject().put("type", "text").put("text", span.text());
            }
        }
        return nodes;
    }

    private static String markdownSpans(List<Span> spans) {
        StringBuilder out = new StringBuilder();
        for (Span span : spans) {
            out.append(span.bold() ? "**" + span.text() + "**" : span.text());
        }
        return out.toString();
    }

    private static String markdownTable(Table table) {
        List<String> lines = new ArrayList<>();
        lines.add("| " + markdownRow(table.header()) + " |");
        lines.add("|" + " --- |".repeat(table.header().size()));
        for (List<String> row : table.rows()) {
            lines.add("| " + markdownRow(row) + " |");
        }
        return String.join("\n", lines);
    }

    private static String markdownRow(List<String> cells) {
        List<String> escaped = new ArrayList<>();
        cells.forEach(cell -> escaped.add(cell.replace("|", "\\|").replace("\n", " ")));
        return String.join(" | ", escaped);
    }
}
