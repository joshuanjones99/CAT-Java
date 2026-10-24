package com.verifone.cat.validate;

import static com.verifone.cat.validate.ValidateFixtures.CARWASH_RECEIPT;
import static com.verifone.cat.validate.ValidateFixtures.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Snapshot storage, {@code assertReceipt} and the update mode (DESIGN.md §5.1, §5.4).
 *
 * <p>Ported from the Python CAT's {@code tests/unit/test_validate_snapshots.py}, receipt half.
 * The TLog half and the {@code --snapshot-update} subprocess test are not ported: TLog is a later
 * slice, and the flag is a pytest option this module does not have.
 */
class SnapshotTest {

    private static final String RECEIPT = CARWASH_RECEIPT;
    private static final String RECEIPT_NAME = "PETROCORE-10823-CATReceipt.txt";

    /** A snapshot session for a fictitious migrated case, with its own claim set. */
    private static SnapshotSession session(Path dir, boolean update) {
        return SnapshotSession.forNodeId(dir.resolve("PETROCORE-10823-TestCase.py"),
                "tests/suites/cadence/PETROCORE-10823-TestCase.py::test_indoor_prepay", update, new HashSet<>());
    }

    // ------------------------------------------------------------------
    // Storage layout
    // ------------------------------------------------------------------

    @Test
    void baselinesSitInTheCaseFolderNamedAsPatsNamesItsFiles(@TempDir Path dir) {
        SnapshotSession session = session(dir, false);
        Path path = session.path(ReceiptAssertions.RECEIPT_SUFFIX, null);
        assertEquals(session.store().root(), path.getParent(), "flat, no __snapshots__");
        assertEquals(RECEIPT_NAME, path.getFileName().toString());
        assertEquals("PETROCORE-10823-Tlog.xml", SnapshotStore.compose("PETROCORE-10823", "-Tlog.xml", ""));
    }

    @Test
    void aTestNotNamedForACaseFallsBackToItsOwnStem(@TempDir Path dir) {
        SnapshotSession session = SnapshotSession.forNodeId(dir.resolve("test_scratch.py"),
                "test_scratch.py::test_x");
        assertEquals("test_scratch-CATReceipt.txt",
                session.path(ReceiptAssertions.RECEIPT_SUFFIX, null).getFileName().toString());
    }

    @Test
    void eachParameterKeepsItsOwnBaselineWithASafeName(@TempDir Path dir) {
        SnapshotSession session = SnapshotSession.forNodeId(dir.resolve("PETROCORE-10823-TestCase.py"),
                "PETROCORE-10823-TestCase.py::test_card[EMV AMEX CREDIT/chip]");
        String name = session.path(ReceiptAssertions.RECEIPT_SUFFIX, null).getFileName().toString();
        assertFalse(name.contains("/") || name.contains(" "));
        assertEquals("PETROCORE-10823-CATReceipt-EMV_AMEX_CREDIT_chip.txt", name);

        SnapshotSession plain = SnapshotSession.forNodeId(dir.resolve("PETROCORE-10823-TestCase.py"),
                "PETROCORE-10823-TestCase.py::test_card");
        assertFalse(plain.path(ReceiptAssertions.RECEIPT_SUFFIX, null).getFileName().toString().equals(name));
    }

    @Test
    void theStoreRoundTripsAndNormalisesLineEndings(@TempDir Path dir) throws IOException {
        SnapshotStore store = new SnapshotStore(dir, "PETROCORE-1");
        store.write("", "-K.txt", "a\nb");
        assertTrue(Files.readAllBytes(store.path("", "-K.txt")).length > 0);
        assertEquals("PETROCORE-1-K.txt", store.path("", "-K.txt").getFileName().toString());
        assertTrue(new String(Files.readAllBytes(store.path("", "-K.txt")), StandardCharsets.UTF_8).endsWith("b\n"));
        Files.write(store.path("", "-K.txt"), "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("a\nb\n", store.read("", "-K.txt"));
        assertNull(store.read("nope", "-K.txt"));
    }

    // ------------------------------------------------------------------
    // assertReceipt
    // ------------------------------------------------------------------

    @Test
    void aMissingBaselineFailsAndSaysWhereItGoes(@TempDir Path dir) {
        SnapshotSession session = session(dir, false);
        SnapshotMissingException thrown = assertThrows(SnapshotMissingException.class,
                () -> ReceiptAssertions.assertReceipt(text(RECEIPT), session));
        assertTrue(thrown.getMessage().contains(RECEIPT_NAME));
        assertTrue(thrown.getMessage().contains("update mode"));
        assertTrue(thrown instanceof ValidationFailedException, "a missing baseline fails, it is not a skip");
    }

    @Test
    void updateWritesTheNormalisedTextThenItPasses(@TempDir Path dir) throws IOException {
        SnapshotSession session = session(dir, true);
        ReceiptAssertions.assertReceipt(text(RECEIPT), session);
        Path written = session.path(ReceiptAssertions.RECEIPT_SUFFIX, null);
        assertTrue(Files.exists(written));
        String stored = Files.readString(written, StandardCharsets.UTF_8);

        // What is stored is what is compared: volatile fields are already masked on disk.
        assertTrue(stored.contains("<DATE>") && stored.contains("TRAN#<TXN>"));
        assertFalse(stored.contains("TRAN# 9010317"));
        assertEquals(Normalisers.normaliseReceipt(text(RECEIPT)), stripNewlines(stored));

        session.setUpdate(false);
        session.clearClaims();
        ReceiptAssertions.assertReceipt(text(RECEIPT), session);
    }

    @Test
    void aRerunWithNewVolatileValuesStillPasses(@TempDir Path dir) {
        SnapshotSession session = session(dir, true);
        ReceiptAssertions.assertReceipt(text(RECEIPT), session);
        session.setUpdate(false);
        session.clearClaims();

        String rerun = text(RECEIPT)
                .replace("3/1/21 13:21", "11/4/26 9:07")
                .replace("TRAN# 9010317", "TRAN# 9014412")
                .replace("Stan: 001015724", "Stan: 002884109");
        ReceiptAssertions.assertReceipt(rerun, session);
    }

    @Test
    void aRealChangeFailsWithADiffNamingTheLine(@TempDir Path dir) {
        SnapshotSession session = session(dir, true);
        ReceiptAssertions.assertReceipt(text(RECEIPT), session);
        session.setUpdate(false);
        session.clearClaims();

        String changed = text(RECEIPT).replace("TOTAL SALE    $20.00", "TOTAL SALE    $25.00");
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class,
                () -> ReceiptAssertions.assertReceipt(changed, session));
        String message = thrown.getMessage();
        assertTrue(message.contains("-TOTAL SALE    $20.00"));
        assertTrue(message.contains("+TOTAL SALE    $25.00"));
        assertTrue(message.contains(session.path(ReceiptAssertions.RECEIPT_SUFFIX, null).toString()));
        assertTrue(message.contains("update mode"));
    }

    @Test
    void twoReceiptsInOneTestNeedDistinctNames(@TempDir Path dir) {
        SnapshotSession session = session(dir, true);
        ReceiptAssertions.assertReceipt(text(RECEIPT), session);
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class,
                () -> ReceiptAssertions.assertReceipt(text(RECEIPT), session));
        assertTrue(thrown.getMessage().contains("two assertions claim"));
        ReceiptAssertions.assertReceipt(text(RECEIPT), session, "void", Normalisers.RECEIPT_NORMALISERS, true);
        assertEquals("PETROCORE-10823-CATReceipt-void.txt",
                session.path(ReceiptAssertions.RECEIPT_SUFFIX, "void").getFileName().toString());
    }

    @Test
    void twoTestsInOneModuleCannotClaimOneBaseline(@TempDir Path dir) {
        Set<String> shared = new HashSet<>();
        Path testFile = dir.resolve("PETROCORE-10823-TestCase.py");
        SnapshotSession first = SnapshotSession.forNodeId(testFile,
                "PETROCORE-10823-TestCase.py::test_one", true, shared);
        SnapshotSession second = SnapshotSession.forNodeId(testFile,
                "PETROCORE-10823-TestCase.py::test_two", true, shared);
        ReceiptAssertions.assertReceipt(text(RECEIPT), first);
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class,
                () -> ReceiptAssertions.assertReceipt(text(RECEIPT), second));
        assertTrue(thrown.getMessage().contains("tests in this module"));
    }

    @Test
    void asTextAcceptsWhatTheHarnessActuallyProduces(@TempDir Path dir) throws IOException {
        ReceiptAssertions.TextSource receipt = () -> "WELCOME\nTOTAL 1.00";
        assertEquals("WELCOME\nTOTAL 1.00", ReceiptAssertions.asText(receipt));
        assertEquals("raw", ReceiptAssertions.asText("raw"));
        assertEquals("bytes", ReceiptAssertions.asText("bytes".getBytes(StandardCharsets.UTF_8)));
        Path file = dir.resolve("receipt.txt");
        Files.write(file, "     WELCOME TO\r\nLINE\r".getBytes(StandardCharsets.UTF_8));
        assertEquals("     WELCOME TO\nLINE\n", ReceiptAssertions.asText(file));
        assertThrows(IllegalArgumentException.class, () -> ReceiptAssertions.asText(new Object()));
    }

    // ------------------------------------------------------------------
    // The diff
    // ------------------------------------------------------------------

    @Test
    void aSingleChangedLineIsAUnifiedHunkWithContext(@TempDir Path dir) {
        String diff = UnifiedDiff.unified("a\nb\nc", "a\nX\nc", dir.resolve("p.txt"), 3);
        assertEquals(String.join("\n",
                "--- " + dir.resolve("p.txt") + " (snapshot)",
                "+++ captured (this run)",
                "@@ -1,3 +1,3 @@",
                " a",
                "-b",
                "+X",
                " c"), diff);
    }

    @Test
    void distantChangesGiveSeparateHunks(@TempDir Path dir) {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            before.append("line ").append(i).append('\n');
            after.append(i == 2 || i == 19 ? "changed " + i : "line " + i).append('\n');
        }
        String diff = UnifiedDiff.unified(before.toString(), after.toString(), dir.resolve("p.txt"), 3);
        assertEquals(2, diff.lines().filter(line -> line.startsWith("@@")).count());
    }

    @Test
    void theSplitMatchesPythonsSplitlines() {
        assertEquals(List.of(), UnifiedDiff.splitLines(""));
        assertEquals(List.of("a"), UnifiedDiff.splitLines("a\n"));
        assertEquals(List.of("a", ""), UnifiedDiff.splitLines("a\n\n"));
        assertEquals(List.of("a", "b"), UnifiedDiff.splitLines("a\nb"));
    }

    private static String stripNewlines(String text) {
        return text.replaceAll("\n+$", "");
    }
}
