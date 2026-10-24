package com.verifone.cat.validate.tlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.validate.ReceiptAssertions;
import com.verifone.cat.validate.SnapshotMissingException;
import com.verifone.cat.validate.SnapshotSession;
import com.verifone.cat.validate.ValidationFailedException;

/**
 * {@code assertTlog}: update mode, the reference, and a pass or a failure (DESIGN.md §5.1, §5.4).
 *
 * <p>Ported from the Python CAT's {@code assert_tlog} behaviour as {@code tests/unit/test_cat_validations.py}
 * drives it, minus the report hook. Every file is written under {@code @TempDir}.
 */
class TlogAssertionsTest {

    private static final String CASE = "PETROCORE-424242-TestCase.feature";
    private static final String NODE = CASE + "::test_petrocore_424242";

    private static SnapshotSession session(Path dir, boolean update) {
        return SnapshotSession.forNodeId(dir.resolve(CASE), NODE, update, new HashSet<>());
    }

    @Test
    void anUpdateWritesTheReferenceAndAComparisonPassesAgainstIt(@TempDir Path dir) throws IOException {
        TlogAssertions.assertTlog("<trans><amount>1.00</amount></trans>", session(dir, true));
        assertTrue(Files.isRegularFile(dir.resolve("PETROCORE-424242-Tlog.xml")));
        ReceiptAssertions.Verdict verdict =
                TlogAssertions.assertTlog("<trans><amount>1.00</amount></trans>", session(dir, false));
        assertEquals("PETROCORE-424242-Tlog.xml", verdict.snapshotName());
        assertTrue(verdict.expected().contains("1.00") && verdict.compared().contains("1.00"));
    }

    @Test
    void aChangedValueFailsNamingThePath(@TempDir Path dir) {
        TlogAssertions.assertTlog("<trans><amount>1.00</amount></trans>", session(dir, true));
        ValidationFailedException e = assertThrows(ValidationFailedException.class,
                () -> TlogAssertions.assertTlog("<trans><amount>2.00</amount></trans>", session(dir, false)));
        assertTrue(e.getMessage().contains("TLog does not match"), e.getMessage());
        assertTrue(e.getMessage().contains("amount") && e.getMessage().contains("2.00"), e.getMessage());
    }

    @Test
    void aMissingReferenceFailsAndSaysHowToCreateIt(@TempDir Path dir) {
        SnapshotMissingException e = assertThrows(SnapshotMissingException.class,
                () -> TlogAssertions.assertTlog("<trans/>", session(dir, false)));
        assertTrue(e.getMessage().contains("no snapshot baseline"), e.getMessage());
    }

    @Test
    void anUpdateWillNotOverwriteAnExistingReference(@TempDir Path dir) throws IOException {
        Path reference = dir.resolve("PETROCORE-424242-Tlog.xml");
        Files.writeString(reference, "<trans><amount>9.99</amount></trans>\n", StandardCharsets.UTF_8);
        SnapshotSession update = session(dir, true);
        TlogAssertions.assertTlog("<trans><amount>1.00</amount></trans>", update);
        assertEquals("<trans><amount>9.99</amount></trans>\n", Files.readString(reference, StandardCharsets.UTF_8));
        assertEquals(1, update.kept().size());
    }

    @Test
    void volatileChangesInACapturePassAgainstTheReference(@TempDir Path dir) {
        TlogAssertions.assertTlog("<trans><trSeq>100</trSeq><amount>1.00</amount></trans>", session(dir, true));
        TlogAssertions.assertTlog("<trans><trSeq>999</trSeq><amount>1.00</amount></trans>", session(dir, false));
    }

    @Test
    void aTruncatedCaptureIsAParseFailureNotAMismatch(@TempDir Path dir) {
        assertThrows(TlogParseException.class,
                () -> TlogAssertions.assertTlog("<trans><amount>", session(dir, false)));
    }

    @Test
    void aNonXmlValueIsRefused(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TlogAssertions.assertTlog(Integer.valueOf(3), session(dir, false)));
        assertTrue(e.getMessage().contains("cannot read TLog XML"), e.getMessage());
    }
}
