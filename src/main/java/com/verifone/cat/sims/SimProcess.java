package com.verifone.cat.sims;

/**
 * A supervised simulator: its row, its last status, and the process CAT spawned for it.
 *
 * <p>{@code process} is null for an adopted simulator (D9: CAT never stops it) and for a row
 * that never launched. Only a non-null process is ever terminated, by {@link Supervisor#stop}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param entry the registry row
 * @param status the status as of the pass that produced this handle
 * @param process the child this supervisor spawned, or null
 * @param stopLog ends the {@code log:} file tail, or null when the row has none
 */
public record SimProcess(SimEntry entry, SimStatus status, Process process, Runnable stopLog) {

    /** True when this supervisor spawned the process, so may stop it. */
    public boolean spawned() {
        return process != null;
    }
}
