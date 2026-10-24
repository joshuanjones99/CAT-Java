package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptCaptureException;
import com.verifone.cat.pos.receipt.ReceiptWaitException;
import com.verifone.cat.validate.Normalisers;
import com.verifone.cat.validate.ReceiptAssertions;
import com.verifone.cat.validate.ReceiptRule;
import com.verifone.cat.validate.UnifiedDiff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code VALIDATE_RECEIPT}: the POS's printed receipt against PATS's reference, until CAT has a baseline of its
 * own. Ported from the Python {@code validation._validate_receipt} and {@code _compare_receipt}.
 *
 * <p>The comparison order is PATS's: the normalised texts, then PATS's shared rule applied to both, then the
 * POP-block order, the merchant copy and the report masks, and last this build's layout drift, which only
 * {@code CAT_RECEIPT_REBASE=layout} rewrites. The Python's {@code _pos_receipt} also tuned the POS receipt source
 * (its settle, abort and fresh-print marks); this port takes the capture as the receipt source gives it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
final class ReceiptHandler implements ActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The receipt's wait ceiling, as PATS's {@code generatePOSBill} waits (117 s). */
    static final double RECEIPT_WAIT = 117.0;

    /** The suffix of CAT's own receipt baseline. */
    static final String BASELINE_SUFFIX = "-CATReceipt.txt";

    private final ValidationContext context;

    ReceiptHandler(ValidationContext context) {
        this.context = context;
    }

    @Override
    public void handle(CaseRun run, RunnableAction action) throws Exception {
        String name = ValidationSupport.require(action, "SELECT_FILE");
        double timeout = run.budget(RECEIPT_WAIT);
        Receipt receipt = capture(run, timeout);
        String text = receipt.text();
        run.record().recordReceipt(text, name);
        String got = ValidationSupport.rstripNewlines(Normalisers.normaliseReceipt(text));
        Path catPath = ValidationSupport.baselineDir(run).resolve(
                ValidationSupport.stem(name, "Receipt") + BASELINE_SUFFIX);
        if (run.update()) {
            Files.writeString(catPath, got + "\n", StandardCharsets.UTF_8);
            LOG.info("{}: wrote {}", run.kase().key(), catPath);
            return;
        }
        try {
            compare(run, name, got, catPath);
        } catch (ValidationFailure soft) {
            run.record().markNewestReceipt(false);
            throw soft;
        }
        run.record().markNewestReceipt(true);
    }

    /**
     * The receipt to check: the DCR's when the case took a DCR print mark, else the POS's. A failure to capture
     * is a failed validation, not a stop, as PATS's receipt wait is.
     */
    private Receipt capture(CaseRun run, double timeout) {
        Optional<Long> mark = run.scratch(ValidationFamily.DCR_RECEIPT_MARK);
        try {
            if (mark.isPresent() && context.dcr() != null) {
                return context.dcr().receiptSince(mark.get(), timeout);
            }
            return context.receipts().capture();
        } catch (ReceiptWaitException e) {
            String how = e.deadEnd() ? " (PATS fails the same way, later)" : " (PATS fails the same way)";
            throw new ValidationFailure("receipt not received: " + e.getMessage() + how);
        } catch (ReceiptCaptureException e) {
            throw new ValidationFailure("receipt not received: " + e.getMessage() + " (PATS fails the same way)");
        }
    }

    /** {@code _compare_receipt}: returns when {@code got} matches, throws the diff when it does not. */
    private void compare(CaseRun run, String name, String got, Path catPath) throws Exception {
        String expected;
        if (Files.isRegularFile(catPath)) {
            expected = ValidationSupport.rstripNewlines(Normalisers.normaliseReceipt(ReceiptAssertions.asText(catPath)));
        } else if (context.references().reference("Receipt", name).isPresent()) {
            byte[] reference = context.references().reference("Receipt", name).get();
            expected = ValidationSupport.rstripNewlines(
                    Normalisers.normaliseReceipt(new String(reference, StandardCharsets.UTF_8)));
        } else {
            run.setCompared("", got);
            throw new ValidationFailure("reference file " + name + " does not exist (PATS fails the same way); captured:\n"
                    + UnifiedDiff.unified("", ReceiptRule.applyRule(got), catPath, 3));
        }
        run.setCompared(expected, got);
        if (expected.equals(got) || ReceiptRule.applyRule(expected).equals(ReceiptRule.applyRule(got))) {
            return;
        }
        if (ReceiptCanon.canonical(expected).equals(ReceiptCanon.canonical(got))) {
            return;
        }
        String wholeExpected = ReceiptCanon.withoutMerchantCopy(expected);
        String wholeGot = ReceiptCanon.withoutMerchantCopy(got);
        if (ReceiptCanon.canonical(wholeExpected).equals(ReceiptCanon.canonical(wholeGot))) {
            return;
        }
        String shownExpected = ReceiptRule.applyRule(wholeExpected);
        String shownGot = ReceiptRule.applyRule(wholeGot);
        boolean drift = ReceiptCanon.layoutDriftOnly(shownExpected, shownGot);
        if (drift && "layout".equals(context.env(ValidationContext.RECEIPT_REBASE_ENV))) {
            Files.writeString(catPath, got + "\n", StandardCharsets.UTF_8);
            LOG.warn("{}: layout drift only; rewrote {}", run.kase().key(), catPath);
            return;
        }
        throw new ValidationFailure((drift ? "receipt differs (layout drift only):\n" : "receipt differs:\n")
                + UnifiedDiff.unified(shownExpected, shownGot, catPath, 3));
    }
}
