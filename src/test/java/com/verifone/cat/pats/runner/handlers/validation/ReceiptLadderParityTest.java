package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredPosAndCommander;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.resource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.CaseRun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The receipt comparison ladder ({@code _compare_receipt}: exact, then the shared rule, then {@code _canonical},
 * then without the merchant copy, then the layout-drift label) against the real captures of CAT-Java run
 * {@code dec5794107fa} (2026-10-28), each verdict the one the Python's ladder gives on the same files.
 *
 * <p>The Python's verdicts, computed 2026-10-28 with {@code C:\gitrepos\CAT\.venv} over these files
 * ({@code normalise_receipt}, {@code apply_rule}, {@code _canonical}, {@code without_merchant_copy}):
 * <ul>
 * <li>36855's capture against PATS's buypass reference: the shared rule makes them equal (the tax-rate lines are
 * dropped by the rule). That is how the Python's passing runs (2581320c4367 and every run since 2026-10-21) pass:
 * {@code tests/suites/cadence} holds no 36855 CAT baseline, so they compare against PATS's reference.</li>
 * <li>36855's capture against the CAT baseline the Java pulled ({@code testcases/PETROCORE-36855/
 * PETROCORE-36855-CATReceipt.txt}, petroapps d8a40676dca, "translated test file(s) from tests/suites/cadence_55_02"):
 * every rung false. The CAT baseline is used whenever it exists, so the rule does not drop those lines.</li>
 * <li>36916's capture against the Python's {@code tests/suites/cadence} CAT baseline: equal once normalised. Against
 * the pulled cadence_55_02 baseline ({@code Cash Back         5.00}): every rung false.</li>
 * </ul>
 * So the Java ladder is the Python's, and these failures come from which baseline the suite carries, not from a
 * missing rung. Fake-tested only.
 */
class ReceiptLadderParityTest {

    @TempDir
    Path folder;

    private String validate(String key, String captured, String catBaseline, String patsReference) throws IOException {
        Path baselines = Files.createDirectories(folder.resolve("baselines"));
        if (catBaseline != null) {
            Files.writeString(baselines.resolve(key + "-CATReceipt.txt"), catBaseline, StandardCharsets.UTF_8);
        }
        ValidationKit.FakeReferences references = new ValidationKit.FakeReferences();
        if (patsReference != null) {
            references.reference("Receipt", key + "-Receipt.txt", patsReference);
        }
        ValidationContext ctx = ValidationKit.context(ValidationKit.receiptSource(captured),
                new ValidationKit.FakePos(), new ValidationKit.FakeCommander(), new ValidationKit.FakePackets(),
                references, noEnv());
        CaseRun run = ValidationKit.run(kase(key, action(0, "VALIDATE_RECEIPT", "SELECT_FILE", key + "-Receipt.txt")),
                ctx, baselines, false, declaredPosAndCommander(), folder);
        return verdict(run);
    }

    @Test
    void the36855CaptureMatchesPatsReferenceThroughTheSharedRuleAsInThePython() throws IOException {
        assertEquals("", validate("PETROCORE-36855", resource("receipts/ladder/36855-captured.txt"), null,
                resource("receipts/ladder/36855-pats-buypass.txt")));
    }

    @Test
    void the36855CaptureDiffersFromThePulledCadence5502BaselineAsInThePython() throws IOException {
        String failed = validate("PETROCORE-36855", resource("receipts/ladder/36855-captured.txt"),
                resource("receipts/ladder/36855-cat-55_02.txt"), resource("receipts/ladder/36855-pats-buypass.txt"));

        assertTrue(failed.contains("A(5.0000%)"), failed);
    }

    @Test
    void the36916CaptureMatchesThePythonsCadenceBaseline() throws IOException {
        assertEquals("", validate("PETROCORE-36916", resource("receipts/ladder/36916-captured.txt"),
                resource("receipts/ladder/36916-cat-cadence.txt"), null));
    }

    @Test
    void the36916CaptureDiffersFromThePulledCadence5502BaselineAsInThePython() throws IOException {
        String failed = validate("PETROCORE-36916", resource("receipts/ladder/36916-captured.txt"),
                resource("receipts/ladder/36916-cat-55_02.txt"), null);

        assertTrue(failed.contains("Cash Back  $      5.00"), failed);
    }
}
