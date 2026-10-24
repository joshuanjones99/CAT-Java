package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ingest: a ticket read, read-only, into one spec file; and the Atlassian Document Format and storage text as Markdown. */
class IngestTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtlassianCredentials.Jira JIRA =
            new AtlassianCredentials.Jira("tester@verifone.com", "not-a-real-token");

    static final String ADF = """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Round up"}]},
              {"type":"paragraph","content":[
                {"type":"text","text":"Offer a "},
                {"type":"text","text":"donation","marks":[{"type":"strong"}]},
                {"type":"text","text":" after cash, says "},
                {"type":"mention","attrs":{"text":"@Product"}}]},
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"PLU 9999"}]}]},
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"tender 11.00"}]}]}]},
              {"type":"table","content":[
                {"type":"tableRow","content":[
                  {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Card"}]}]},
                  {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Prompt"}]}]}]},
                {"type":"tableRow","content":[
                  {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"AMEX"}]}]},
                  {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"donate"}]}]}]}]},
              {"type":"mediaSingle","content":[{"type":"media","attrs":{"id":"x"}}]}
            ]}
            """;

    static String ticket() {
        return """
                {"key":"PETROCORE-5","names":{"customfield_100":"Acceptance Criteria","customfield_200":"Story Points"},
                 "fields":{"summary":"Round up for charity on cash","issuetype":{"name":"Story"},
                   "status":{"name":"In Progress"},"labels":["charity"],"description":%s,
                   "customfield_100":"Given a cash sale, the pinpad offers the donation.","customfield_200":5,
                   "comment":{"comments":[{"created":"2026-10-01T10:00:00.000+0000","body":"Use PLU 9999."}]},
                   "issuelinks":[{"type":{"outward":"relates to"},"outwardIssue":{"key":"PETROCORE-4",
                     "fields":{"summary":"Card flow","status":{"name":"Done"},"issuetype":{"name":"Story"}}}}],
                   "subtasks":[{"key":"PETROCORE-6","fields":{"summary":"Receipt line",
                     "status":{"name":"To Do"},"issuetype":{"name":"Sub-task"}}}],
                   "attachment":[{"filename":"flow.png","mimeType":"image/png","size":10}]}}
                """.formatted(ADF);
    }

    private static AtlassianClient client(FakeSite site) {
        return new AtlassianClient(site.base(), site.base().resolve("/api/v2"), () -> JIRA, () -> null,
                Duration.ofSeconds(5));
    }

    private static FakeSite site() {
        return new FakeSite()
                .on("GET", "/rest/api/3/issue/PETROCORE-5", 200, ticket())
                .on("GET", "/rest/api/3/issue/PETROCORE-5/remotelink", 200, """
                        [{"globalId":"appId=abc&pageId=777",
                          "object":{"url":"https://x/wiki/spaces/P/pages/777","title":"Charity spec"}}]
                        """)
                .on("GET", "/wiki/api/v2/pages/777", 200, """
                        {"title":"Charity spec","body":{"storage":{"value":"<p>Round to the next dollar.</p>"}}}
                        """);
    }

    @Test
    void adfBecomesReadableText() throws Exception {
        String text = Ingest.adfText(JSON.readTree(ADF));
        assertTrue(text.contains("#### Round up"), text);
        assertTrue(text.contains("Offer a **donation** after cash, says @Product"), text);
        assertTrue(text.contains("- PLU 9999\n- tender 11.00"), text);
        assertTrue(text.contains("| Card | Prompt |\n|---|---|\n| AMEX | donate |"), text);
        assertTrue(text.contains("[image or file — not read]"), text);
        assertEquals("", Ingest.adfText(null));
        assertEquals("plain", Ingest.adfText(new com.fasterxml.jackson.databind.node.TextNode("plain")));
    }

    @Test
    void confluenceStorageBecomesText() {
        String text = Ingest.storageText("<h2>Scope</h2><p>Cash &amp; card</p><ul><li>donate</li></ul>"
                + "<table><tr><td>a</td><td>b</td></tr></table>");
        assertTrue(text.contains("#### Scope"), text);
        assertTrue(text.contains("Cash & card"), text);
        assertTrue(text.contains("- donate"), text);
        assertTrue(text.contains("| a | b"), text);
    }

    @Test
    void aTicketIsReadIntoOneSpec() {
        try (FakeSite site = site()) {
            Ingest.Spec spec = Ingest.fetch("petrocore-5", client(site));
            String text = spec.markdown();
            assertTrue(text.startsWith("# PETROCORE-5: Round up for charity on cash"), text);
            assertTrue(text.contains("- **Status:** In Progress"), text);
            assertTrue(text.contains("## Acceptance Criteria\n\nGiven a cash sale, the pinpad offers the donation."), text);
            assertFalse(text.contains("Story Points"), "not a spec field");
            assertTrue(text.contains("relates to PETROCORE-4 (Story, Done): Card flow"), text);
            assertTrue(text.contains("PETROCORE-6 (Sub-task, To Do): Receipt line"), text);
            assertTrue(text.contains("## Confluence: Charity spec\n\nRound to the next dollar."), text);
            assertTrue(text.contains("**2026-10-01**\n\nUse PLU 9999."), text);
            assertTrue(text.contains("flow.png (image/png, 10 bytes)"), text);
            assertTrue(site.seen.stream().allMatch(s -> s.authorization() != null
                    && s.authorization().startsWith("Basic ")));
            assertEquals(1, spec.pages().size());
        }
    }

    @Test
    void anUnreadableTicketSaysWhy() {
        try (FakeSite site = site()) {
            Ingest.IngestException refused = assertThrows(Ingest.IngestException.class,
                    () -> Ingest.fetch("PETROCORE-404", client(site)));
            assertTrue(refused.getMessage().contains("HTTP 404"), refused.getMessage());
            Ingest.IngestException notAKey = assertThrows(Ingest.IngestException.class,
                    () -> Ingest.fetch("not a key", client(site)));
            assertTrue(notAKey.getMessage().contains("not a Jira key"), notAKey.getMessage());
        }
    }

    @Test
    void ingestIntoMakesItTheSessionsSpec(@TempDir Path tmp) throws Exception {
        Session session = Session.create(tmp);
        try (FakeSite site = site()) {
            Ingest.intoSession(session, "PETROCORE-5", client(site));
        }
        assertEquals("PETROCORE-5", session.specTicket());
        String written = Files.readString(Session.load(session.directory()).specPath());
        assertTrue(written.startsWith("# PETROCORE-5"), written);
    }
}
