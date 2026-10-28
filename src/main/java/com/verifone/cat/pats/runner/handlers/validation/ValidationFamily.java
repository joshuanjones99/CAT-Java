package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.posui.PosUiCaseState;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * The validation action family: {@code VALIDATE_RECEIPT}, {@code VALIDATE_TRANSACTION}, {@code VALIDATE_REPORT},
 * {@code RESET_PACKETS} and {@code VALIDATE_PACKETS}, each a handler on the case runner's registry.
 *
 * <p>{@link #register} is the family's one entry point; the orchestrator calls it with the run's registry and the
 * rig's {@link ValidationContext}. It also registers the family's begin hook, which takes the marks PATS's runner
 * takes as a case begins: the POS's transaction mark, the DCR's receipt print count, and the DCR registers'
 * transaction marks. A case's own scratch state (the marks as the case moves on) is held under this family's
 * {@link ScratchKey}s, so no other family can collide with it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the handlers are tested against fakes of every seam.
 */
public final class ValidationFamily {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The POS's transaction number mark: the case's start, then the transaction the last TLog read returned. */
    static final ScratchKey<String> TLOG_MARK = ScratchKey.of("validation.tlog-mark", String.class);

    /** The TLog the last {@code VALIDATE_TRANSACTION} read, for a later action of the same case. */
    static final ScratchKey<Element> LAST_TLOG = ScratchKey.of("validation.last-tlog", Element.class);

    /** The DCR registers' transaction marks, by their number series. */
    static final ScratchKey<DcrMarks> DCR_TLOG_MARKS = ScratchKey.of("validation.dcr-tlog-marks", DcrMarks.class);

    /** The DCR's print count at the case's start, when the case prints at the DCR. */
    static final ScratchKey<Long> DCR_RECEIPT_MARK = ScratchKey.of("validation.dcr-receipt-mark", Long.class);

    /**
     * The POS printer's receipt count when the case's latest POS-driving action began (the Python's
     * {@code CaseRun.print_mark}); absent before any such action, and -1 with no serial printer.
     */
    static final ScratchKey<Integer> PRINT_MARK = ScratchKey.of("validation.print-mark", Integer.class);

    private ValidationFamily() {
    }

    /** The transaction marks of the DCR registers, keyed by number series ({@code 901}, {@code 902}, ...). */
    static final class DcrMarks {

        private final Map<String, String> marks = new HashMap<>();

        /** The mark for {@code series}, or {@code null} when none was taken. */
        String get(String series) {
            return marks.get(series);
        }

        /** Sets {@code series}' mark. */
        void put(String series, String trseq) {
            marks.put(series, Objects.requireNonNull(trseq, "trseq"));
        }
    }

    /**
     * Registers the validation handlers on {@code registry}, and the case's begin hook for their marks.
     *
     * @throws IllegalStateException when one of the names already has a handler (a wiring error)
     */
    public static void register(ActionRegistry registry, ValidationContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        registry.register("VALIDATE_RECEIPT", new ReceiptHandler(context));
        registry.register("VALIDATE_TRANSACTION", new TransactionHandler(context));
        registry.register("VALIDATE_REPORT", new ReportHandler(context));
        PacketHandler packets = new PacketHandler(context);
        registry.register("RESET_PACKETS", packets::reset);
        registry.register("VALIDATE_PACKETS", packets::validate);
        registry.onBegin(run -> begin(run, context));
        registry.beforeAction((run, action) -> markPrints(run, action, context));
    }

    /**
     * The Python's {@code CaseRun.do} marks: before every action that can drive the POS (not {@link #passive}), in a
     * case that declares the POS, the printer's receipt count and the sales closed so far. The receipt check then
     * prefers a print made since that action began, and settles for a sale closed before it and not yet read.
     */
    static void markPrints(CaseRun run, RunnableAction action, ValidationContext context) {
        if (!run.declares(FixtureName.POS) || passive(action.name())) {
            return;
        }
        run.setScratch(PRINT_MARK, context.receipts().printCount());
        PosUiCaseState state = PosUiCaseState.of(run);
        state.setSalesAtMark(state.salesClosed());
    }

    /** The Python's {@code _passive}: an action that only reads or waits, so cannot finish a sale. */
    static boolean passive(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("SLEEP") || upper.contains("VALIDAT") || upper.startsWith("WAIT")
                || upper.equals("RESET_PACKETS") || upper.equals("STORE_TRANS_MAP");
    }

    /**
     * The marks a case takes before its first action (Python's {@code CaseRun.begin}): the DCR's print count for a
     * case that prints at the DCR, the POS's newest transaction for a case with a {@code VALIDATE_TRANSACTION}, and
     * the DCR registers' newest transactions for the {@code DCRn} validations. A mark is a refinement: where it
     * cannot be taken, the POS's mark is logged and dropped, and the Commander's listing is logged and the DCR marks
     * stay empty, as PATS's own read stands without them.
     */
    static void begin(CaseRun run, ValidationContext context) {
        if (context.dcr() != null && context.printsAtDcr().test(run.kase())) {
            run.setScratch(DCR_RECEIPT_MARK, context.dcr().printCount());
        }
        boolean readsTransactions = run.kase().actions().stream().anyMatch(a -> isTransactionValidation(a));
        if (run.declares(FixtureName.POS) && readsTransactions) {
            try {
                String number = context.pos().newestTransactionNumber();
                if (number != null) {
                    run.setScratch(TLOG_MARK, number);
                }
            } catch (RuntimeException e) {
                LOG.warn("{}: no transaction mark: {}", run.kase().key(), e.getMessage());
            }
        }
        Set<String> series = new TreeSet<>();
        for (RunnableAction action : run.kase().actions()) {
            String dcr = TransactionHandler.dcrSeries(action);
            if (dcr != null) {
                series.add(dcr);
            }
        }
        if (!series.isEmpty() && run.declares(FixtureName.COMMANDER_CONFIG)) {
            try {
                List<String> listed = context.commander().listTransactionNumbers();
                DcrMarks marks = new DcrMarks();
                for (String dcr : series) {
                    String newest = TransactionHandler.newestInSeries(listed, dcr);
                    marks.put(dcr, newest == null ? "" : newest);
                }
                run.setScratch(DCR_TLOG_MARKS, marks);
            } catch (RuntimeException e) {
                LOG.warn("{}: no DCR transaction mark: {}", run.kase().key(), e.getMessage());
            }
        }
    }

    private static boolean isTransactionValidation(RunnableAction action) {
        return "VALIDATE_TRANSACTION".equals(action.name().toUpperCase(Locale.ROOT));
    }
}
