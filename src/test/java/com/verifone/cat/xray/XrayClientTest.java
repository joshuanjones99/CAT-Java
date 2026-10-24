package com.verifone.cat.xray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.atlassian.AtlassianUnavailable;

/** The Xray and Jira client's reading of replies, against the loopback fake. */
class XrayClientTest {

    @Test
    void theSecretNeverAppearsInAnError() {
        try (XrayFake fake = new XrayFake()) {
            fake.refuseAuthenticate.set(true);
            XrayClient client = Events.xrayClient(fake);
            AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                    () -> client.importExecution(XrayFake.map("tests", List.of())));
            assertTrue(thrown.getMessage().contains("HTTP 401"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains(Events.XRAY.clientSecret()), thrown.getMessage());
        }
    }

    @Test
    void aMissingTransitionIsNotAppliedAndNotAnError() {
        try (XrayFake fake = new XrayFake()) {
            XrayClient client = Events.xrayClient(fake);
            assertFalse(client.transition("PETROCORE-1", "Reopen"));
            assertEquals(List.of("GET"), fake.seen.stream().map(XrayFake.Seen::method).toList());
            assertTrue(client.transition("PETROCORE-1", "close"));
        }
    }

    @Test
    void a404FromARejectedTokenNamesTheCredentials() {
        // Run 168e643ede21's ending: the token stopped working, Jira served the finish as anonymous.
        try (XrayFake fake = new XrayFake()) {
            fake.hideIssues.set(true);
            fake.rejectMyself.set(true);
            XrayClient client = Events.xrayClient(fake);
            AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                    () -> client.transition("PETROCORE-142756", "Close"));
            assertTrue(thrown.getMessage().contains("Jira credentials"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("press s in CAT"), thrown.getMessage());
            assertEquals("/rest/api/3/myself", fake.seen.get(fake.seen.size() - 1).path());
        }
    }

    @Test
    void a404WithAGoodTokenIsReportedAsItCame() {
        try (XrayFake fake = new XrayFake()) {
            fake.hideIssues.set(true);
            XrayClient client = Events.xrayClient(fake);
            AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                    () -> client.addComment("PETROCORE-1", Adf.toAdf(List.of())));
            assertTrue(thrown.getMessage().contains("HTTP 404: "), thrown.getMessage());
            assertFalse(thrown.getMessage().contains("Jira credentials"), thrown.getMessage());
            // The comment, then one myself probe; no retry on a 404.
            assertEquals(2, fake.seen.size());
        }
    }

    @Test
    void theMyselfCallNamesTheJiraUser() {
        try (XrayFake fake = new XrayFake()) {
            XrayClient.Myself me = Events.xrayClient(fake).myself();
            assertEquals("712020:abc", me.accountId());
            assertEquals("Tester T", me.displayName());
        }
    }

    @Test
    void aRetryAfterHeaderIsHonouredUpToTwoMinutes() {
        try (XrayFake fake = new XrayFake()) {
            XrayClient client = new XrayClient(Events.client(fake), Duration.ofSeconds(20), null);
            assertEquals(Duration.ofSeconds(2), client.retryAfter("2"));
            assertEquals(Duration.ofSeconds(120), client.retryAfter("999"));
            assertEquals(Duration.ofSeconds(20), client.retryAfter("soon"));
            assertEquals(Duration.ofSeconds(20), client.retryAfter(null));
        }
    }

    @Test
    void importReturnsTheExecutionKeyOfACreateAndOfAnUpdate() {
        try (XrayFake fake = new XrayFake()) {
            XrayClient client = Events.xrayClient(fake);
            assertEquals("PETROCORE-900001",
                    client.importExecution(XrayFake.map("info", XrayFake.map("project", "PETROCORE"))));
            assertEquals("PETROCORE-5", client.importExecution(XrayFake.map(
                    "testExecutionKey", "PETROCORE-5", "tests", List.of())));
        }
    }
}
