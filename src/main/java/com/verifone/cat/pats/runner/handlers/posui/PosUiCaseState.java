package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.devices.DevicesFamily;
import com.verifone.cat.pats.runner.handlers.sale.TouchPacer;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One case's state for the POS UI family: what its presses leave for its next wait (the touch, the quick tender, the
 * message it pressed under, the OK-only message a wait gave up on). Kept in the case's scratch under {@link #KEY}.
 *
 * <p>The sale counts are the receipt family's too, which is why they are public: a press that closes a sale counts it
 * ({@code countSale}), and the receipt check reads the count.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PosUiCaseState {

    /** The key the family's state lives under in a case's scratch. */
    public static final ScratchKey<PosUiCaseState> KEY = ScratchKey.of("pos-ui", PosUiCaseState.class);

    /** The prints and the sales closed when a quick tender was pressed. */
    record TenderMark(int prints, int sales) {
    }

    /** An OK-only message a wait saw, and when it first saw it. */
    record DialogSeen(List<String> shown, long sinceNanos) {
    }

    private long startedNanos = Long.MIN_VALUE;
    private OptionalLong tenderedAt = OptionalLong.empty();
    private TenderMark tenderMark;
    private int salesClosed;
    private int salesRead;
    private OptionalLong unreadFrom = OptionalLong.empty();
    private int salesAtMark;
    private List<String> underMessage;
    private DialogSeen dialogSeen;
    private List<String> deadEnd;

    /** The state for {@code run}, created at the case's first use. */
    public static PosUiCaseState of(CaseRun run) {
        return run.scratchOrCreate(KEY, PosUiCaseState::new);
    }

    /** When the case began, on the family's clock; {@code Long.MIN_VALUE} until the case's begin hook sets it. */
    long startedNanos() {
        return startedNanos;
    }

    void started(long nanos) {
        startedNanos = nanos;
    }

    /**
     * Whether the case has touched the POS yet: the flag the sale family's {@link TouchPacer} sets too, so it is one
     * flag in the case's scratch, not one per family.
     */
    public static boolean touched(CaseRun run) {
        return run.scratch(TouchPacer.TOUCHED).orElse(false);
    }

    /** Marks the case as having touched the POS (the shared {@link TouchPacer#TOUCHED} flag). */
    static void touch(CaseRun run) {
        run.setScratch(TouchPacer.TOUCHED, Boolean.TRUE);
    }

    /**
     * When the case last keyed the pinpad, if it has. The devices family records it under {@link DevicesFamily#KEYED_AT}
     * ({@code GET_ANY_KEY}, {@code GET_DECIMAL}), and the next touch waits out PATS's pause after it.
     */
    public static OptionalLong keyedAt(CaseRun run) {
        return run.scratch(DevicesFamily.KEYED_AT).map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    /** Records that the case keyed the pinpad at {@code nanos}, so the next touch waits out PATS's pause after it. */
    public static void markKeyed(CaseRun run, long nanos) {
        run.setScratch(DevicesFamily.KEYED_AT, nanos);
    }

    static void clearKeyed(CaseRun run) {
        run.clearScratch(DevicesFamily.KEYED_AT);
    }

    OptionalLong tenderedAt() {
        return tenderedAt;
    }

    void tendered(long nanos, int prints, int sales) {
        tenderedAt = OptionalLong.of(nanos);
        tenderMark = new TenderMark(prints, sales);
    }

    void clearTendered() {
        tenderedAt = OptionalLong.empty();
    }

    TenderMark tenderMark() {
        return tenderMark;
    }

    /** Sales this case has closed, as counted by its presses. */
    public int salesClosed() {
        return salesClosed;
    }

    /** Sales the receipt check has read so far. */
    public int salesRead() {
        return salesRead;
    }

    /** Set by the receipt family when it reads sales. */
    public void setSalesRead(int value) {
        salesRead = value;
    }

    /** The printer count where the prints owed to the receipt check begin, once a sale is unread. */
    public OptionalLong unreadFrom() {
        return unreadFrom;
    }

    /** The sale count at the last quick tender's mark. */
    public int salesAtMark() {
        return salesAtMark;
    }

    void setSalesAtMark(int value) {
        salesAtMark = value;
    }

    /** One more sale closed, by a press begun at printer count {@code mark}. */
    void countSale(int mark) {
        if (salesClosed == salesRead) {
            unreadFrom = OptionalLong.of(mark);
        }
        salesClosed++;
    }

    /** The message the case pressed under, to learn from once the press is judged; null when none. */
    List<String> underMessage() {
        return underMessage;
    }

    void underMessage(List<String> message) {
        underMessage = message;
    }

    DialogSeen dialogSeen() {
        return dialogSeen;
    }

    void dialogSeen(DialogSeen seen) {
        dialogSeen = seen;
    }

    /** The OK-only message a wait gave up on, which the next case may press OK on; empty when none. */
    public Optional<List<String>> deadEnd() {
        return Optional.ofNullable(deadEnd);
    }

    void deadEnd(List<String> message) {
        deadEnd = message;
    }
}
