package com.verifone.cat.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianUnavailable;
import com.verifone.cat.validate.PyRepr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingests a Jira ticket, the spec of what needs testing, into one reviewable Markdown file. Port of
 * {@code cat.authoring.ingest}, read-only: every call is a GET through {@link AtlassianClient#getJson}.
 *
 * <p>What is read: the issue's summary and facts (type, status, priority, labels, components, fix versions, parent), its
 * description (Atlassian Document Format, rendered as text), every custom field whose name says it is a spec
 * ({@link #SPEC_FIELD}), its comments (the newest {@link #MAX_COMMENTS}, dated), its linked issues and subtasks, each
 * Confluence page linked to it (as text, up to {@link #MAX_PAGES}), and its attachments by name only.
 *
 * <p>Not ported: the Confluence page's macros beyond their own text, and HTML entities beyond the common named and numeric
 * ones ({@link #unescape}). Those cases read as the Python reads the common ones.
 *
 * <p>UNVERIFIED AGAINST A REAL TOKEN: the paths are the ones the Python's unit tests fake; no ticket has been ingested from
 * the live site from this repository.
 */
public final class Ingest {

    /** A custom field whose display name matches this is part of the spec. */
    public static final Pattern SPEC_FIELD = Pattern.compile(
            "acceptance|criteria|test (?:notes|steps|scenarios?)|scenario|requirement|expected|spec",
            Pattern.CASE_INSENSITIVE);

    /** More than this many comments: the newest are kept. */
    public static final int MAX_COMMENTS = 30;

    /** More than this many linked Confluence pages: the first are read, the rest listed. */
    public static final int MAX_PAGES = 5;

    /** A page longer than this is cut, and says so. */
    public static final int MAX_PAGE_CHARS = 40_000;

    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
    private static final Pattern PAGE_ID = Pattern.compile("pageId=(\\d+)|/pages/(\\d+)");
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "table");

    private Ingest() {}

    /** A Confluence page read into the spec. */
    public record Page(String id, String title, String text, boolean truncated) {}

    /** The ticket as the spec file holds it. Built by {@link #fetch}; {@link #markdown()} is the file. */
    public static final class Spec {
        final String key;
        final String summary;
        final List<String[]> facts = new ArrayList<>();
        String description = "";
        final List<String[]> sections = new ArrayList<>();
        final List<String> links = new ArrayList<>();
        final List<String> subtasks = new ArrayList<>();
        final List<Page> pages = new ArrayList<>();
        final List<String> unreadPages = new ArrayList<>();
        final List<String[]> comments = new ArrayList<>();
        final List<String> attachments = new ArrayList<>();
        final List<String> problems = new ArrayList<>();

        Spec(String key, String summary) {
            this.key = key;
            this.summary = summary;
        }

        public String key() {
            return key;
        }

        public String summary() {
            return summary;
        }

        public List<Page> pages() {
            return Collections.unmodifiableList(pages);
        }

        /** The spec as Markdown: what the drafting model is given and what the author reads on the Spec tab. */
        public String markdown() {
            List<String> out = new ArrayList<>();
            out.add("# " + key + ": " + summary);
            out.add("");
            for (String[] fact : facts) {
                if (!fact[1].isEmpty()) {
                    out.add("- **" + fact[0] + ":** " + fact[1]);
                }
            }
            out.add("");
            out.add("## Description");
            out.add("");
            out.add(description.isEmpty() ? "_(empty)_" : description);
            for (String[] section : sections) {
                out.add("");
                out.add("## " + section[0]);
                out.add("");
                out.add(section[1]);
            }
            if (!links.isEmpty()) {
                out.add("");
                out.add("## Linked issues");
                out.add("");
                for (String line : links) {
                    out.add("- " + line);
                }
            }
            if (!subtasks.isEmpty()) {
                out.add("");
                out.add("## Subtasks");
                out.add("");
                for (String line : subtasks) {
                    out.add("- " + line);
                }
            }
            for (Page page : pages) {
                out.add("");
                out.add("## Confluence: " + page.title() + (page.truncated() ? " (cut: longer than the limit)" : ""));
                out.add("");
                out.add(page.text().isEmpty() ? "_(empty)_" : page.text());
            }
            if (!unreadPages.isEmpty()) {
                out.add("");
                out.add("## Confluence pages not read");
                out.add("");
                for (String p : unreadPages) {
                    out.add("- " + p);
                }
            }
            if (!comments.isEmpty()) {
                out.add("");
                out.add("## Comments");
                out.add("");
                for (String[] comment : comments) {
                    out.add("**" + comment[0] + "**");
                    out.add("");
                    out.add(comment[1]);
                    out.add("");
                }
            }
            if (!attachments.isEmpty()) {
                out.add("");
                out.add("## Attachments (listed, not read)");
                out.add("");
                for (String a : attachments) {
                    out.add("- " + a);
                }
            }
            if (!problems.isEmpty()) {
                out.add("");
                out.add("## What could not be read");
                out.add("");
                for (String p : problems) {
                    out.add("- " + p);
                }
            }
            return stripEnd(String.join("\n", out)) + "\n";
        }
    }

    /** The ticket could not be read; the message says why, and never carries a credential. */
    public static final class IngestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IngestException(String message) {
            super(message);
        }

        public IngestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // -- Atlassian Document Format -> text ---------------------------------------------------

    /** An ADF document as Markdown-ish text; a plain string as itself (stripped); null as the empty string. */
    public static String adfText(JsonNode document) {
        if (document == null || document.isNull() || document.isMissingNode()) {
            return "";
        }
        if (document.isTextual()) {
            return document.asText().strip();
        }
        if (!document.isObject()) {
            return document.asText();
        }
        List<String> lines = children(document.path("content"), 0);
        String text = String.join("\n", lines);
        return stripEnd(text.replaceAll("\n{3,}", "\n\n")).strip();
    }

    private static String marks(String text, JsonNode marks) {
        String out = text;
        for (JsonNode mark : marks) {
            String kind = mark.path("type").asText();
            switch (kind) {
                case "strong" -> out = "**" + out + "**";
                case "em" -> out = "*" + out + "*";
                case "code" -> out = "`" + out + "`";
                case "link" -> {
                    String href = mark.path("attrs").path("href").asText("");
                    out = href.isEmpty() ? out : "[" + out + "](" + href + ")";
                }
                default -> {
                }
            }
        }
        return out;
    }

    private static String inline(Iterable<JsonNode> nodes) {
        StringBuilder out = new StringBuilder();
        for (JsonNode node : nodes) {
            String kind = node.path("type").asText();
            JsonNode attrs = node.path("attrs");
            switch (kind) {
                case "text" -> out.append(marks(node.path("text").asText(), node.path("marks")));
                case "hardBreak" -> out.append('\n');
                case "mention" -> out.append(attrs.hasNonNull("text") ? attrs.get("text").asText() : "@someone");
                case "emoji" -> out.append(attrs.hasNonNull("text") ? attrs.get("text").asText()
                        : attrs.path("shortName").asText(""));
                case "inlineCard", "blockCard" -> out.append(attrs.path("url").asText(""));
                case "status" -> out.append('[').append(attrs.path("text").asText("")).append(']');
                case "date" -> out.append(attrs.path("timestamp").asText(""));
                default -> out.append(inline(node.path("content")));
            }
        }
        return out.toString();
    }

    private static List<String> block(JsonNode node, int depth) {
        String kind = node.path("type").asText();
        JsonNode content = node.path("content");
        JsonNode attrs = node.path("attrs");
        String indent = "  ".repeat(depth);
        switch (kind) {
            case "paragraph":
                return List.of(inline(content), "");
            case "heading": {
                int level = Math.min(6, attrs.path("level").asInt(3) + 2);
                return List.of("#".repeat(level) + " " + inline(content), "");
            }
            case "bulletList", "orderedList", "taskList": {
                List<String> lines = new ArrayList<>();
                int number = 0;
                for (JsonNode item : content) {
                    number++;
                    String bullet = kind.equals("orderedList") ? number + "." : "-";
                    if (kind.equals("taskList")) {
                        bullet = "DONE".equals(item.path("attrs").path("state").asText()) ? "- [x]" : "- [ ]";
                    }
                    List<String> parts = children(item.path("content"), depth + 1);
                    String first = parts.isEmpty() ? "" : parts.get(0);
                    lines.add(indent + bullet + " " + first.strip());
                    for (int i = 1; i < parts.size(); i++) {
                        if (!parts.get(i).isBlank()) {
                            lines.add(parts.get(i));
                        }
                    }
                }
                lines.add("");
                return lines;
            }
            case "codeBlock": {
                List<String> out = new ArrayList<>(List.of("```", inline(content), "```", ""));
                return out;
            }
            case "blockquote": {
                List<String> out = new ArrayList<>();
                for (String line : children(content, depth)) {
                    if (!line.isBlank()) {
                        out.add("> " + line);
                    }
                }
                out.add("");
                return out;
            }
            case "rule":
                return List.of("---", "");
            case "table":
                return table(content);
            case "mediaSingle", "mediaGroup", "media":
                return List.of("[image or file — not read]", "");
            case "expand": {
                List<String> out = new ArrayList<>();
                out.add("**" + (attrs.hasNonNull("title") ? attrs.get("title").asText() : "Details") + "**");
                out.addAll(children(content, depth));
                return out;
            }
            case "panel": {
                List<String> out = new ArrayList<>();
                out.add("> (" + (attrs.hasNonNull("panelType") ? attrs.get("panelType").asText() : "note") + ")");
                out.addAll(children(content, depth));
                return out;
            }
            case "taskItem", "listItem":
                return children(content, depth);
            default:
                if (content.size() > 0) {
                    return children(content, depth);
                }
                String text = inline(List.of(node));
                return text.isEmpty() ? List.of() : List.of(text, "");
        }
    }

    private static List<String> table(JsonNode rowsNode) {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : rowsNode) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row.path("content")) {
                cells.add(String.join(" ", children(cell.path("content"), 0)).strip());
            }
            List<String> flat = new ArrayList<>();
            for (String cell : cells) {
                flat.add(cell.replace("\n", " "));
            }
            rows.add("| " + String.join(" | ", flat) + " |");
        }
        List<String> out = new ArrayList<>(rows);
        if (!rows.isEmpty()) {
            int width = count(rows.get(0), '|') - 1;
            out.add(1, "|" + "---|".repeat(width));
        }
        out.add("");
        return out;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static List<String> children(JsonNode nodes, int depth) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : nodes) {
            out.addAll(block(node, depth));
        }
        return out;
    }

    // -- Confluence storage XHTML -> text ----------------------------------------------------

    /** A Confluence page's storage XHTML as plain text, the macros' own text kept. */
    public static String storageText(String storage) {
        StringBuilder parts = new StringBuilder();
        int i = 0;
        while (i < storage.length()) {
            char c = storage.charAt(i);
            if (c == '<') {
                int close = storage.indexOf('>', i);
                if (close < 0) {
                    parts.append(unescape(storage.substring(i)));
                    break;
                }
                String tag = storage.substring(i + 1, close).strip();
                i = close + 1;
                if (tag.startsWith("!") || tag.startsWith("?")) {
                    continue;
                }
                boolean end = tag.startsWith("/");
                String name = (end ? tag.substring(1) : tag).strip();
                int stop = 0;
                while (stop < name.length() && (Character.isLetterOrDigit(name.charAt(stop)))) {
                    stop++;
                }
                name = name.substring(0, stop).toLowerCase(Locale.ROOT);
                if (!end) {
                    startTag(name, parts);
                } else if (BLOCK_TAGS.contains(name)) {
                    parts.append('\n');
                }
            } else {
                int next = storage.indexOf('<', i);
                int stop = next < 0 ? storage.length() : next;
                parts.append(unescape(storage.substring(i, stop)));
                i = stop;
            }
        }
        String text = unescape(parts.toString());
        text = text.replaceAll("[ \\t]+\\n", "\n");
        return stripEnd(text.replaceAll("\\n{3,}", "\n\n")).strip();
    }

    private static void startTag(String name, StringBuilder parts) {
        if (BLOCK_TAGS.contains(name)) {
            parts.append('\n');
        }
        if (name.equals("td") || name.equals("th")) {
            parts.append(" | ");
        }
        if (name.equals("li")) {
            parts.append("- ");
        }
        if (name.length() == 2 && name.charAt(0) == 'h' && Character.isDigit(name.charAt(1))) {
            int level = Math.min(6, name.charAt(1) - '0' + 2);
            parts.append("#".repeat(level)).append(' ');
        }
    }

    private static final Map<String, String> NAMED = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ");

    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9A-Fa-f]+|#\\d+|[A-Za-z]+);");

    /** The HTML entities the storage format uses: named (the common few) and numeric, as {@code html.unescape} reads them. */
    static String unescape(String text) {
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String body = m.group(1);
            String value;
            if (body.startsWith("#x") || body.startsWith("#X")) {
                value = codePoint(Integer.parseInt(body.substring(2), 16));
            } else if (body.startsWith("#")) {
                value = codePoint(Integer.parseInt(body.substring(1)));
            } else {
                value = NAMED.getOrDefault(body, m.group(0));
            }
            out.append(value);
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String codePoint(int point) {
        return Character.isValidCodePoint(point) ? new String(Character.toChars(point)) : "�";
    }

    private static String stripEnd(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    // -- the spec ----------------------------------------------------------------------------

    private static String name(JsonNode value, String key) {
        return value.isObject() ? value.path(key).asText("") : "";
    }

    private static String issueLine(JsonNode issue) {
        JsonNode fields = issue.path("fields");
        return issue.path("key").asText() + " (" + name(fields.path("issuetype"), "name") + ", "
                + name(fields.path("status"), "name") + "): " + fields.path("summary").asText("");
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.size() > 0;
    }

    /**
     * Reads ticket {@code key} into a {@link Spec}.
     *
     * @throws IngestException when the key is not a Jira key, or the issue cannot be read (the message says why)
     */
    public static Spec fetch(String key, AtlassianClient client) {
        String normal = key.strip().toUpperCase(Locale.ROOT);
        if (!KEY.matcher(normal).matches()) {
            throw new IngestException(PyRepr.reprString(normal) + " is not a Jira key like PETROCORE-12345");
        }
        JsonNode issue;
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("fields", "*all");
            query.put("expand", "names");
            issue = client.getJson("/rest/api/3/issue/" + normal, query);
        } catch (AtlassianUnavailable e) {
            throw new IngestException(normal + ": " + e.getMessage(), e);
        }
        JsonNode fields = issue.path("fields");
        JsonNode names = issue.path("names");
        Spec spec = new Spec(normal, fields.path("summary").asText(""));
        JsonNode parent = fields.path("parent");
        spec.facts.add(new String[] {"Type", name(fields.path("issuetype"), "name")});
        spec.facts.add(new String[] {"Status", name(fields.path("status"), "name")});
        spec.facts.add(new String[] {"Priority", name(fields.path("priority"), "name")});
        spec.facts.add(new String[] {"Labels", join(fields.path("labels"), null)});
        spec.facts.add(new String[] {"Components", join(fields.path("components"), "name")});
        spec.facts.add(new String[] {"Fix versions", join(fields.path("fixVersions"), "name")});
        spec.facts.add(new String[] {"Parent", parent.isObject()
                ? parent.path("key").asText() + ": " + parent.path("fields").path("summary").asText("")
                : ""});
        spec.description = adfText(fields.path("description"));
        Iterator<Map.Entry<String, JsonNode>> entries = fields.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> field = entries.next();
            String id = field.getKey();
            JsonNode value = field.getValue();
            String label = names.hasNonNull(id) ? names.get(id).asText() : id;
            if (!id.startsWith("customfield_") || !truthy(value) || !SPEC_FIELD.matcher(label).find()) {
                continue;
            }
            String text = value.isObject() || value.isTextual() ? adfText(value) : "";
            if (!text.isEmpty()) {
                spec.sections.add(new String[] {label, text});
            }
        }
        for (JsonNode link : fields.path("issuelinks")) {
            JsonNode kind = link.path("type");
            if (link.has("outwardIssue")) {
                spec.links.add(outwardText(kind, "outward") + " " + issueLine(link.get("outwardIssue")));
            } else if (link.has("inwardIssue")) {
                spec.links.add(outwardText(kind, "inward") + " " + issueLine(link.get("inwardIssue")));
            }
        }
        for (JsonNode task : fields.path("subtasks")) {
            spec.subtasks.add(issueLine(task));
        }
        JsonNode comments = fields.path("comment").path("comments");
        int from = Math.max(0, comments.size() - MAX_COMMENTS);
        for (int i = from; i < comments.size(); i++) {
            JsonNode comment = comments.get(i);
            String text = adfText(comment.path("body"));
            if (!text.isEmpty()) {
                String created = comment.path("created").asText("");
                spec.comments.add(new String[] {created.length() > 10 ? created.substring(0, 10) : created, text});
            }
        }
        for (JsonNode a : fields.path("attachment")) {
            spec.attachments.add(a.path("filename").asText() + " (" + a.path("mimeType").asText("?") + ", "
                    + (a.has("size") ? a.get("size").asText() : "?") + " bytes)");
        }
        readPages(spec, client);
        return spec;
    }

    private static String outwardText(JsonNode kind, String side) {
        return kind.hasNonNull(side) ? kind.get(side).asText() : "relates to";
    }

    private static String join(JsonNode array, String field) {
        List<String> parts = new ArrayList<>();
        for (JsonNode item : array) {
            parts.add(field == null ? item.asText() : name(item, field));
        }
        return String.join(", ", parts);
    }

    private static void readPages(Spec spec, AtlassianClient client) {
        JsonNode remote;
        try {
            remote = client.getJson("/rest/api/3/issue/" + spec.key + "/remotelink", Map.of());
        } catch (AtlassianUnavailable e) {
            spec.problems.add("remote links: " + e.getMessage());
            return;
        }
        JsonNode links = remote.isArray() ? remote : remote.path("values");
        Set<String> seen = new HashSet<>();
        for (JsonNode link : links) {
            JsonNode target = link.path("object");
            String url = target.path("url").asText("");
            String title = target.hasNonNull("title") ? target.get("title").asText() : url;
            Matcher found = PAGE_ID.matcher(link.path("globalId").asText("") + " " + url);
            String application = link.path("application").asText("").toLowerCase(Locale.ROOT);
            if (!url.contains("/wiki/") && !application.contains("confluence")) {
                continue;
            }
            if (!found.find()) {
                spec.unreadPages.add(title + ": " + url + " (no page id)");
                continue;
            }
            String pageId = found.group(1) != null ? found.group(1) : found.group(2);
            if (!seen.add(pageId)) {
                continue;
            }
            if (spec.pages.size() >= MAX_PAGES) {
                spec.unreadPages.add(title + ": " + url);
                continue;
            }
            JsonNode page;
            try {
                page = client.getJson("/wiki/api/v2/pages/" + pageId, Map.of("body-format", "storage"));
            } catch (AtlassianUnavailable e) {
                spec.problems.add("Confluence page " + pageId + ": " + e.getMessage());
                continue;
            }
            String storage = page.path("body").path("storage").path("value").asText("");
            String text = storageText(storage);
            String fallback = target.hasNonNull("title") ? target.get("title").asText() : pageId;
            String pageTitle = page.hasNonNull("title") ? page.get("title").asText() : fallback;
            boolean cut = text.length() > MAX_PAGE_CHARS;
            spec.pages.add(new Page(pageId, pageTitle, cut ? text.substring(0, MAX_PAGE_CHARS) : text, cut));
        }
    }

    /**
     * Ingests ticket {@code key} into {@code session}: the spec file {@code spec-<KEY>.md} is written and the session's spec
     * ticket becomes the key.
     *
     * @return the spec read
     */
    public static Spec intoSession(Session session, String key, AtlassianClient client) {
        Spec spec = fetch(key, client);
        session.setSpecTicket(spec.key());
        try {
            Files.writeString(session.specPath(), spec.markdown(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return spec;
    }
}
