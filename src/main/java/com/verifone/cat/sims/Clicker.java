package com.verifone.cat.sims;

import java.util.Optional;

/**
 * What the {@link Supervisor} needs to start a {@code click:} row and to tidy a {@code windowed:}
 * one: the Python CAT's {@code _do_clicks} and {@code _minimise_when_shown}
 * ({@code cat.sims.supervisor}). The production one is {@link AccessBridgeClicker}; tests give the
 * supervisor that class over fake bindings, or a fake of this.
 */
public interface Clicker {

    /**
     * How a row's buttons went.
     *
     * @param clicked true only when every button was pressed
     * @param detail one line for the operator
     */
    record Clicked(boolean clicked, String detail) {

        public Clicked {
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Why this controller cannot press any button for {@code ctx}'s simulators, with the line that fixes
     * it; empty when it can try. Asked before a {@code click:} row is spawned, so a row that cannot be
     * clicked is never left running half-started.
     */
    Optional<String> unavailable(SimContext ctx);

    /**
     * Finds {@code process}'s main window by PID before {@code deadline}, presses the row's buttons in
     * order, and minimises the window. Bounded by {@code deadline} (at least a second past now); never
     * throws for a click that went wrong.
     *
     * @param deadline a {@link System#nanoTime} deadline: the row's start budget
     */
    Clicked click(SimEntry entry, Process process, SimContext ctx, long deadline);

    /**
     * Minimises each top-level window {@code process} shows until {@code deadline}, once each, on a
     * thread of its own. Best effort: a window never found is cosmetic, never a row.
     */
    void minimiseWhenShown(Process process, long deadline);
}
