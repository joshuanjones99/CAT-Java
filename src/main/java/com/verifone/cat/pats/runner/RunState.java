package com.verifone.cat.pats.runner;

/**
 * What the runner carries from one case to the next within a run: whether the last case failed.
 * After a failure the recovery ladder has pressed on the POS, so the next case's first press must wait
 * for the POS to read Ready (Python {@code core._last_case_failed}). One instance per run, shared by
 * the run's cases; it is not a global.
 */
public final class RunState {

    private boolean lastCaseFailed;

    /**
     * Called as a case begins: returns whether the case before it failed, and clears the mark for the
     * case now starting.
     */
    public synchronized boolean beginCase() {
        boolean previous = lastCaseFailed;
        lastCaseFailed = false;
        return previous;
    }

    /** Called when the running case fails, so the next case knows recovery has pressed on the POS. */
    public synchronized void markCaseFailed() {
        lastCaseFailed = true;
    }
}
