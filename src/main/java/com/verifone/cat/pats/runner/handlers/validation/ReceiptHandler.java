package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pats.runner.handlers.posui.PosUiCaseState;
import com.verifone.cat.pos.receipt.ReceiptCaptureException;
import com.verifone.cat.pos.receipt.ReceiptWait;
import com.verifone.cat.pos.receipt.ReceiptWaitException;
import com.verifone.cat.validate.Normalisers;
import com.verifone.cat.validate.ReceiptAssertions;
import com.verifone.cat.validate.ReceiptRule;
import com.verifone.cat.validate.UnifiedDiff;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code VALIDATE_RECEIPT}: the POS's printed receipt against PATS's reference, until CAT has a baseline of its
 * own. Ported from the Python {@code validation._validate_receipt} and {@code _compare_receipt}.
 *
 * <p>The comparison order is PATS's: the normalised texts, then PATS's shared rule applied to both, then the
 * POP-block order, the merchant copy and the report masks, and last this build's layout drift, which only
 * {@code CAT_RECEIPT_REBASE=layout} rewrites. The POS receipt is read as the Python's {@code _pos_receipt} reads it
 * ({@link #posReceipt}): a no-{@code TRAN#} print is the receipt only in a case that prints a report, so a case's
 * PLU-not-found slip (36850) is never taken for its sale's receipt; the case's fresh-print mark and the unread-sale
 * settle are passed to the source for the one read. Deviation: the Python's {@code abort}
 * ({@code DialogWatch(run)}, an OK-only message holding the sale) is not passed, because the POS UI family's
 * {@code DialogWatch} is not reachable from this family yet; such a receipt waits out its ceiling instead.
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
final class ReceiptHandler implements ActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The receipt's wait ceiling, as PATS's {@code generatePOSBill} waits (117 s). */
    static final double RECEIPT_WAIT = 117.0;

    /**
     * How long a print from before the case's last POS action is held back for a newer one (the Python's
     * {@code core.RECEIPT_FRESH_GRACE}, 5 s).
     */
    static final Duration RECEIPT_FRESH_GRACE = Duration.ofSeconds(5);

    /**
     * PATS's {@code WorkFlow}: a case holding one of these keys its no-{@code TRAN#} print to the test, so a report
     * can be its receipt. In any other case PATS never picks such a print (the Python's {@code REPORT_ACTIONS}).
     */
    static final Set<String> REPORT_ACTIONS = Set.of("PRINT_FLASH_REPORT", "CLOSE_REPORT", "NETWORK_REPORT",
            "REPORT_PRINT");

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
            return posReceipt(run, timeout);
        } catch (ReceiptWaitException e) {
            String how = e.deadEnd() ? " (PATS fails the same way, later)" : " (PATS fails the same way)";
            throw new ValidationFailure("receipt not received: " + e.getMessage() + how);
        } catch (ReceiptCaptureException e) {
            throw new ValidationFailure("receipt not received: " + e.getMessage() + " (PATS fails the same way)");
        }
    }

    /**
     * {@code _pos_receipt}: the POS's printed receipt, waiting as long as PATS's {@code generatePOSBill} would, taking
     * a print with no {@code TRAN#} only in a case that prints a report, as PATS does, and preferring one printed
     * since the case's last POS action (the {@link ValidationFamily#PRINT_MARK}). A sale closed before that action and
     * not yet read can print after it began, so then every print settles first. However it ends, the sales closed so
     * far count as read.
     */
    private Receipt posReceipt(CaseRun run, double timeout) {
        PosUiCaseState state = PosUiCaseState.of(run);
        boolean reports = run.kase().actions().stream()
                .anyMatch(a -> REPORT_ACTIONS.contains(a.name().toUpperCase(Locale.ROOT)));
        int printMark = run.scratch(ValidationFamily.PRINT_MARK).orElse(-1);
        int unread = state.salesAtMark() - state.salesRead();
        ReceiptWait wait = new ReceiptWait(Duration.ofMillis(Math.round(timeout * 1000)), reports, printMark,
                RECEIPT_FRESH_GRACE, unread > 0, (int) state.unreadFrom().orElse(-1), unread + 1, null);
        try {
            return context.receipts().capture(wait);
        } finally {
            state.setSalesRead(state.salesClosed());
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
