package com.verifone.cat.pos.fake;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The fake POS's state: whether a transaction is active, and the items rung up. Ported from the
 * Python {@code _DoubleState} and the state transitions in {@code _DoubleRequestHandler}.
 *
 * <p>Transitions, as the Python: item select changes nothing; PAYMENT_GATEWAY activates the
 * transaction; CASH, CREDIT and OTHER end it, but only while it is active, and CASH and CREDIT
 * first call their hook (the seam to the pinpad double). Items persist through the payment, so a
 * receipt can still be read after it.
 *
 * <p>Every method is synchronized: the server runs on one thread, and a test reads from another.
 *
 * <p>Evidence about this repository only. UNVERIFIED AGAINST HARDWARE: the transitions are the
 * fake POS's, not the Topaz's.
 */
public final class FakePosState {

    /** The four MOP buttons, by their PLU-style item id. */
    public enum Mop {
        /** Starts the transaction. */
        PAYMENT_GATEWAY(145397),
        /** Ends the transaction, through the cash hook. */
        CASH(145394),
        /** Ends the transaction, through the credit hook. */
        CREDIT(145395),
        /** Ends the transaction with no hook (no screen is simulated for it). */
        OTHER(145398);

        private final int id;

        Mop(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Optional<Mop> of(int id) {
            for (Mop mop : values()) {
                if (mop.id == id) {
                    return Optional.of(mop);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What a client sees at one moment. {@code selectedItems} is a copy, so a reader cannot change
     * the state through it.
     */
    public record Snapshot(boolean transactionActive, List<Integer> selectedItems) {

        public Snapshot {
            selectedItems = List.copyOf(selectedItems);
        }
    }

    private boolean transactionActive;
    private final List<Integer> selectedItems = new ArrayList<>();

    /** The item id appended to the sale. Item selection never changes the transaction state. */
    public synchronized void selectItem(int itemId) {
        selectedItems.add(itemId);
    }

    /**
     * Applies a MOP tap. {@code cashHook} and {@code creditHook} may be null. They run before the
     * transaction is ended, as the Python does, and only while it is active.
     */
    public synchronized void tender(int mopId, Runnable cashHook, Runnable creditHook) {
        Optional<Mop> mop = Mop.of(mopId);
        if (mop.isEmpty()) {
            return;
        }
        switch (mop.get()) {
            case PAYMENT_GATEWAY -> transactionActive = true;
            case CASH -> {
                if (transactionActive) {
                    runIfPresent(cashHook);
                    transactionActive = false;
                }
            }
            case CREDIT -> {
                if (transactionActive) {
                    runIfPresent(creditHook);
                    transactionActive = false;
                }
            }
            case OTHER -> {
                if (transactionActive) {
                    transactionActive = false;
                }
            }
            default -> throw new IllegalStateException("unhandled MOP " + mop.get());
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(transactionActive, selectedItems);
    }

    private static void runIfPresent(Runnable hook) {
        if (hook != null) {
            hook.run();
        }
    }
}
