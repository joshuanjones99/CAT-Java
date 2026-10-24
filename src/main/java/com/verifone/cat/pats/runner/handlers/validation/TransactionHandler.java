package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.validate.tlog.TlogOptions;
import com.verifone.cat.validate.tlog.TlogRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * {@code VALIDATE_TRANSACTION}: the sale's TLog against PATS's {@code -Tlog.xml}. Ported from the Python
 * {@code validation._validate_transaction}.
 *
 * <p>A {@code DEVICEID} of {@code DCRn} makes it the DCR's own sale: the newest transaction the Commander numbers
 * for register {@code 900+n}. Otherwise the POS's own newest committed sale is read. Either way the read waits
 * for a transaction newer than the mark: the case's start, then the transaction the previous read returned, so
 * a second validation is not handed the first sale while the second is still committing.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the per-validation mark and the DCR series wait.
 */
final class TransactionHandler implements ActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The wait ceiling for a sale to commit (PATS's {@code STATE_WAIT}, 117 s). */
    static final double STATE_WAIT = 117.0;

    /** The suffix of CAT's own TLog baseline. */
    static final String BASELINE_SUFFIX = "-CATTlog.xml";

    /** The rule file a validation reads when it names none. */
    static final String DEFAULT_RULE = "TLOG-RULE.txt";

    /** How often the Commander's listing is looked at while a newer transaction is awaited (Python: per second). */
    private static final long LOOK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final ValidationContext context;

    TransactionHandler(ValidationContext context) {
        this.context = context;
    }

    /**
     * Python's {@code _dcr_series}: register {@code 900+n}'s number prefix for a {@code VALIDATE_TRANSACTION} on
     * {@code DCRn}, else {@code null}.
     */
    static String dcrSeries(RunnableAction action) {
        if (!"VALIDATE_TRANSACTION".equals(action.name().toUpperCase(Locale.ROOT))) {
            return null;
        }
        String device = action.get("DEVICEID").toUpperCase(Locale.ROOT);
        if (device.startsWith("DCR") && device.length() > 3 && allDigits(device.substring(3))) {
            return Integer.toString(900 + Integer.parseInt(device.substring(3)));
        }
        return null;
    }

    private static boolean allDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Python's {@code _newest_in_series}: the highest of {@code numbers} in {@code series}, or {@code null}. */
    static String newestInSeries(List<String> numbers, String series) {
        String newest = null;
        for (String number : numbers) {
            if (number.startsWith(series) && (newest == null || Long.parseLong(number) > Long.parseLong(newest))) {
                newest = number;
            }
        }
        return newest;
    }

    /**
     * Python's {@code newer_transaction}: {@code newest()} once it is a number other than {@code after}, looked for
     * each second up to {@code timeoutSeconds}; {@code null} if none came. An empty {@code after} means any at all.
     */
    static String newerTransaction(Supplier<String> newest, String after, double timeoutSeconds) {
        long deadline = System.nanoTime() + (long) (timeoutSeconds * TimeUnit.SECONDS.toNanos(1));
        while (true) {
            String number = newest.get();
            if (number != null && !number.isEmpty() && !number.equals(after)) {
                return number;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            LockSupport.parkNanos(LOOK_NANOS);
        }
    }

    @Override
    public void handle(CaseRun run, RunnableAction action) throws Exception {
        String name = ValidationSupport.require(action, "SELECT_FILE");
        Path catPath = ValidationSupport.baselineDir(run).resolve(
                ValidationSupport.stem(name, "Tlog") + BASELINE_SUFFIX);
        boolean hasReference = context.references().reference("Tlog", name).isPresent();
        if (!Files.isRegularFile(catPath) && !hasReference) {
            throw new ValidationFailure("reference file " + name + " does not exist (PATS fails the same way)");
        }
        String series = dcrSeries(action);
        Element actual = series != null ? readDcr(run, action, series) : readPos(run);
        run.setScratch(ValidationFamily.LAST_TLOG, actual);
        run.record().recordTlog(ValidationSupport.serialise(actual), name);

        String rule = action.get("SELECT_RULE_FILE", DEFAULT_RULE);
        ValidationSupport.RuleLists lists = ValidationSupport.ruleLists(context, ValidationSupport.TLOG_RULES, rule);
        TlogOptions options = ValidationSupport.options(
                ValidationSupport.concat(lists.tags(), List.copyOf(TlogRule.CAT_IGNORE_TAGS)),
                ValidationSupport.concat(lists.attributes(), List.copyOf(TlogRule.CAT_IGNORE_ATTRIBUTES)));

        if (run.update()) {
            // CAT's own baseline, written only where this build differs from PATS's reference (rig owner, 2026-09-23);
            // PATS's -Tlog.xml itself is never rewritten.
            if (!hasReference || !ValidationSupport.xmlDiff(referenceBytes(name), actual, options).isEmpty()) {
                Files.write(catPath, ValidationSupport.serialiseBytes(actual));
                LOG.warn("{}: wrote {}; review it against {}", run.kase().key(), catPath, name);
            }
            return;
        }
        boolean own = Files.isRegularFile(catPath);
        byte[] reference = own ? Files.readAllBytes(catPath) : referenceBytes(name);
        String[] texts = ValidationSupport.xmlTexts(reference, actual, options);
        run.setCompared(texts[0], texts[1]);
        String difference = ValidationSupport.xmlDiff(reference, actual, options);
        if (difference.isEmpty()) {
            return;
        }
        // The discounts are also compared in key order: build 1097 writes a line's discounts in either order.
        byte[] ordered = orderedReference(reference);
        if (ordered.length > 0
                && ValidationSupport.xmlDiff(ordered, ValidationSupport.discountsSorted(actual), options).isEmpty()) {
            return;
        }
        throw new ValidationFailure("TLog differs from " + (own ? catPath.getFileName() : name) + ":\n" + difference);
    }

    /** The DCR's sale: the newest the Commander numbers for the register, after the case's mark for it. */
    private Element readDcr(CaseRun run, RunnableAction action, String series) {
        ValidationFamily.DcrMarks marks = run.scratchOrCreate(ValidationFamily.DCR_TLOG_MARKS,
                ValidationFamily.DcrMarks::new);
        Supplier<String> newest = () -> newestInSeries(context.commander().listTransactionNumbers(), series);
        String mark = marks.get(series);
        String trseq = null;
        if (mark != null) {
            trseq = newerTransaction(newest, mark, run.budget(STATE_WAIT));
        }
        if (trseq == null) {
            trseq = newest.get();
        }
        if (trseq == null) {
            throw new ValidationFailure("the Commander holds no transaction for "
                    + action.get("DEVICEID").toUpperCase(Locale.ROOT) + " (register " + series + ")");
        }
        marks.put(series, trseq);
        return context.commander().fetchTlogByTrseq(trseq);
    }

    /**
     * The POS's newest committed sale after the case's mark for it. The mark moves to the sale read, so the next
     * validation waits for one newer than this.
     */
    private Element readPos(CaseRun run) {
        Optional<String> mark = run.scratch(ValidationFamily.TLOG_MARK);
        ValidationContext.PosTlog read = context.pos().newestTlog(mark.orElse(null), run.budget(STATE_WAIT));
        if (mark.isPresent() && read.trseq() != null && !read.trseq().isEmpty()) {
            run.setScratch(ValidationFamily.TLOG_MARK, read.trseq());
        }
        return read.tlog();
    }

    private byte[] referenceBytes(String name) {
        return context.references().reference("Tlog", name).orElseThrow(
                () -> new PatsStepError("no Tlog reference named " + name));
    }

    /** The reference with its discounts in key order, or empty when it is not well-formed XML. */
    private static byte[] orderedReference(byte[] reference) {
        try {
            return ValidationSupport.serialiseBytes(ValidationSupport.discountsSorted(ValidationSupport.parse(reference)));
        } catch (RuntimeException e) {
            return new byte[0];
        }
    }
}
