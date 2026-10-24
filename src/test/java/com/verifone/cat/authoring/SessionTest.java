package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.authoring.Session.CaptureEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The session: its numbering, its edits, its blockers, and its baselines (captured unapproved, then approved). */
class SessionTest {

    static final String GOOD_STEPS = "    Given the register is idle\n"
            + "    When the cashier rings up PLU 9999\n"
            + "    And the cashier totals the sale\n"
            + "    And the customer pays 11.00 in cash\n"
            + "    Then the receipt matches its baseline\n";

    @Test
    void aStoryTicketIsAPetrocoreKeyOrNothing() {
        assertEquals("PETROCORE-123", Session.storyTicket(" petrocore-123 "));
        assertNull(Session.storyTicket(""));
        for (String bad : List.of("PETROCORE-", "PCS-12", "PETROCORE-0", "PETROCORE-12 x")) {
            SessionException refused = assertThrows(SessionException.class, () -> Session.storyTicket(bad));
            assertTrue(refused.getMessage().contains("PETROCORE-12345"), refused.getMessage());
        }
    }

    @Test
    void aNewCaseIsACleanSkeletonNumberedByItsPlace(@TempDir Path tmp) {
        Session session = Session.create(tmp);
        Case first = session.insert(0, "cash sale");
        Case second = session.insert(0, "sign on");
        assertEquals(List.of("sign on", "cash sale"), session.cases().stream().map(Case::title).toList());
        CaseReview review = session.review(first);
        assertEquals(2, review.number());
        assertTrue(review.analysis().ok(), review.analysis().diagnostics().toString());
        assertTrue(review.text().contains("@PETROCORE-2"), "D25: the placeholder is the place");
        assertTrue(session.text(second).startsWith("@PETROCORE-1\nFeature: Authored cases"));
    }

    @Test
    void editsRenameReorderAndRoundTrip(@TempDir Path tmp) {
        Session session = Session.create(tmp);
        Case caseOne = session.insert(0, "cash sale");
        session.insert(1, "card sale");
        session.setTicket("PETROCORE-42");
        session.writeText(caseOne, session.text(caseOne) + "    When the cashier totals the sale\n");
        session.rename(0, "Cash sale with charity");
        assertTrue(session.text(caseOne).contains("Scenario: Cash sale with charity"));
        assertEquals(1, session.move(0, 1));
        assertEquals(1, session.move(1, 1), "past the end: unchanged");
        Session loaded = Session.load(session.directory());
        assertEquals("PETROCORE-42", loaded.ticket());
        assertEquals(List.of("card sale", "Cash sale with charity"),
                loaded.cases().stream().map(Case::title).toList());
        assertTrue(loaded.text(loaded.cases().get(1)).endsWith("When the cashier totals the sale\n"));
    }

    @Test
    void aCaseNameIsOneLineWithoutAColon(@TempDir Path tmp) {
        Session session = Session.create(tmp);
        for (String bad : List.of("", "  ", "a: b")) {
            assertThrows(SessionException.class, () -> session.insert(0, bad));
        }
    }

    @Test
    void removeKeepsTheDraftOnDisk(@TempDir Path tmp) {
        Session session = Session.create(tmp);
        Case only = session.insert(0, "a");
        session.remove(0);
        assertTrue(Files.isRegularFile(session.featurePath(only)));
        assertTrue(Session.load(session.directory()).cases().isEmpty());
    }

    @Test
    void latestSkipsABrokenSession(@TempDir Path tmp) throws Exception {
        Session older = Session.create(tmp);
        older.insert(0, "kept");
        Path newer = Files.createDirectories(tmp.resolve("99999999-999999"));
        Files.writeString(newer.resolve(Session.SESSION_FILE), "{not json");
        Session latest = Session.latest(tmp);
        assertEquals(older.slug(), latest.slug());
        assertNull(Session.latest(tmp.resolve("absent")));
    }

    @Test
    void theRootComesFromTheEnvironment(@TempDir Path tmp) {
        assertEquals(tmp.resolve("x"), Session.authoringRoot(Map.of("CAT_AUTHORING_DIR", tmp.resolve("x").toString()),
                tmp.resolve("fallback")));
        assertEquals(tmp.resolve("fallback"), Session.authoringRoot(Map.of(), tmp.resolve("fallback")));
    }

    @Test
    void withKeySetsOrAddsTheCaseTag() {
        assertTrue(Session.withKey("@PETROCORE-1 @plan_end\nFeature: x\n", "PETROCORE-9").startsWith(
                "@PETROCORE-9 @plan_end"));
        assertEquals("# note\n@PETROCORE-9\nFeature: x\n", Session.withKey("# note\nFeature: x\n", "PETROCORE-9"));
    }

    @Test
    void replayAndPublishBlockers(@TempDir Path tmp) {
        Session session = Session.create(tmp);
        Case c = session.insert(0, "sale");
        session.writeText(c, session.text(c) + "    When the cashier dances\n");
        assertTrue(session.replayBlockers().stream().anyMatch(b -> b.contains("dances")));
        session.writeText(c, session.text(c).replace("When the cashier dances", "When the cashier totals the sale"));
        assertEquals(List.of(), session.replayBlockers());
        List<String> publish = session.publishBlockers();
        assertTrue(publish.stream().anyMatch(b -> b.contains("no baselines")));
        assertTrue(publish.contains("no story ticket (D17)"));
    }

    /** A capture run's directory as {@code --snapshot-update} would leave it, under {@code tmp}. */
    private static Session captured(Path tmp, CaptureEntry[] entries) throws Exception {
        Session session = Session.create(tmp.resolve("s"));
        Case c = session.insert(0, "sale");
        Path directory = Files.createDirectories(tmp.resolve("tests").resolve("generated").resolve(session.slug())
                .resolve("capture-1"));
        Files.writeString(directory.resolve("PETROCORE-1-TestCase.feature"), session.rendered(c));
        Files.writeString(directory.resolve("PETROCORE-1-CATReceipt.txt"), "TOTAL 11.00\n");
        Files.writeString(directory.resolve("PETROCORE-1-Tlog.xml"), "<tlog/>\n");
        Files.writeString(directory.resolve("PETROCORE-1-Receipt.txt"), "PATS's, never taken\n");
        entries[0] = new CaptureEntry(c.id(), 1);
        return session;
    }

    @Test
    void aCaptureIsImportedUnapprovedThenApproved(@TempDir Path tmp) throws Exception {
        CaptureEntry[] entries = new CaptureEntry[1];
        Session session = captured(tmp, entries);
        String relative = tmp.relativize(tmp.resolve("tests").resolve("generated").resolve(session.slug())
                .resolve("capture-1")).toString();
        assertEquals(2, session.importCapture(tmp, relative, "cap1", List.of(entries[0])));
        Case c = session.cases().get(0);
        CaseReview review = session.review(c);
        assertEquals(List.of("CASE-CATReceipt.txt", "CASE-Tlog.xml"),
                review.baselines().stream().map(p -> p.getFileName().toString()).toList());
        assertFalse(review.approved());
        assertEquals("cap1", review.capturedIn());
        String before = session.digest(c);
        assertEquals(2, session.approveBaselines(c));
        assertTrue(session.review(c).approved());
        assertFalse(session.digest(c).equals(before), "an approval is a change a replay must see");
        assertTrue(session.publishBlockers().stream()
                .noneMatch(b -> b.contains("baseline") && b.contains("sale")));
    }

    @Test
    void nothingToApproveIsRefused(@TempDir Path tmp) {
        Session session = Session.create(tmp);
        Case c = session.insert(0, "sale");
        SessionException refused = assertThrows(SessionException.class, () -> session.approveBaselines(c));
        assertTrue(refused.getMessage().contains("Capture"), refused.getMessage());
    }
}
