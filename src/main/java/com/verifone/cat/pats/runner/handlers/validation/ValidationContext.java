package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.w3c.dom.Element;

/**
 * What the validation action family reads from the rig: the POS's receipt and TLog, the Commander's
 * transaction log and reports, the simulators' captured packets, the case's PATS references, and the
 * environment's rebase switches. Every seam is an interface here, so the handlers never reach a device
 * and a test drives them with fakes.
 *
 * <p>Ported from the Python {@code petrotest.pats.runner.validation}, which took these from {@code CaseRun}
 * ({@code run.pos}, {@code run.fixture("commander_config")}, {@code run.case.reference}) and from the
 * environment ({@code os.environ}). {@code environment} defaults to {@code System::getenv} in production.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the seams have no real implementation in this package.
 *
 * @param receipts     the POS receipt capture (the Python {@code pos.receipt()})
 * @param dcr          the DCR's receipt and print count, or {@code null} when the rig has no DCR path
 * @param printsAtDcr  whether a case prints at the DCR (the Python {@code registry.prints_at_dcr}); ignored when
 *                     {@code dcr} is null
 * @param pos          the POS's newest transaction and TLog reads
 * @param commander    the Commander's transaction listing, TLog fetch and report fetch
 * @param packets      the simulators' packet folders and their reset
 * @param references   the case's PATS reference files and the shared rule files
 * @param environment  reads an environment variable; the rebase switches are read through it
 */
public record ValidationContext(
        ReceiptSource receipts,
        DcrReceipts dcr,
        Predicate<RunnableCase> printsAtDcr,
        PosTransactions pos,
        CommanderReads commander,
        PacketSource packets,
        CaseReferences references,
        Function<String, String> environment) {

    /** {@code CAT_RECEIPT_REBASE=layout}: a receipt differing only by known layout drift rewrites its CAT baseline. */
    public static final String RECEIPT_REBASE_ENV = "CAT_RECEIPT_REBASE";

    /** {@code CAT_PACKET_REBASE=1}: every differing listed packet is written as its CAT baseline and passes. */
    public static final String PACKET_REBASE_ENV = "CAT_PACKET_REBASE";

    /** {@code CAT_REPORT_REBASE=1}: every differing end-of-plan report is written as its CAT baseline and passes. */
    public static final String REPORT_REBASE_ENV = "CAT_REPORT_REBASE";

    public ValidationContext {
        Objects.requireNonNull(receipts, "receipts");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(commander, "commander");
        Objects.requireNonNull(packets, "packets");
        Objects.requireNonNull(references, "references");
        printsAtDcr = printsAtDcr == null ? kase -> false : printsAtDcr;
        environment = environment == null ? System::getenv : environment;
    }

    /** The environment variable's value, or {@code null} when it is not set. */
    String env(String name) {
        return environment.apply(name);
    }

    /** The DCR's print count and receipts, as the case's DCR fixture gives them. */
    public interface DcrReceipts {

        /** The DCR's print count now: the mark a case takes before its first DCR action. */
        long printCount();

        /**
         * The DCR's first receipt printed since {@code mark}.
         *
         * @param timeoutSeconds the ceiling for the wait, from the case's budget
         */
        Receipt receiptSince(long mark, double timeoutSeconds);
    }

    /** One POS transaction read, with the transaction number it returned (the Python's {@code newest_read}). */
    public record PosTlog(Element tlog, String trseq) {
    }

    /** The POS's transaction reads: the sale the POS last committed, and its TLog. */
    public interface PosTransactions {

        /** The POS's newest transaction number now, or {@code null} when it has none. */
        String newestTransactionNumber();

        /**
         * The TLog of the newest sale committed after {@code after}, waiting up to {@code timeoutSeconds}.
         *
         * @param after the mark to look past, or {@code null} for no mark
         * @throws RuntimeException when no such sale is read in time (the case stops, as PATS's does)
         */
        PosTlog newestTlog(String after, double timeoutSeconds);
    }

    /**
     * The Commander's transaction and report reads (CGILink: {@code vtransnumlist}, {@code vtransdetails},
     * and the report commands). A {@code null} or empty argument is passed through as the handler has it.
     */
    public interface CommanderReads {

        /** Every transaction sequence number the Commander lists for period 1 (Current Shift), newest first. */
        List<String> listTransactionNumbers();

        /** One transaction's TLog element, by sequence number ({@code vtransdetails}). */
        Element fetchTlogByTrseq(String trseq);

        /** A report's root element, as the Commander replies with it ({@code vrubyrept} or {@code vviperrept}). */
        Element fetchReport(String cmd, String report, String period, String filename, String cashier,
                String register);
    }

    /**
     * A simulator's captured packets, by device. Where a device keeps its packets in a folder, {@link #captured}
     * lists its files; where the simulator holds them in memory, the implementation says so.
     */
    public interface PacketSource {

        /** The files the device has captured and not yet reset, in any order. */
        List<Path> captured(String device);

        /** Empties the device's capture, so the next validation sees only what the next action sends. */
        void reset(String device);
    }

    /**
     * The case's PATS reference files and the shared rule files. Names are matched case-insensitively by the
     * implementation, as PATS matches them.
     */
    public interface CaseReferences {

        /** The case's reference file {@code filename} under {@code rule} ({@code "Receipt"}, {@code "Tlog"}, ...). */
        Optional<byte[]> reference(String rule, String filename);

        /** A shared asset or rule file by folder and basename, e.g. {@code ("AutomationTests/rulefiles/tlog", "TLOG-RULE.txt")}. */
        Optional<byte[]> shared(String folder, String filename);
    }
}
