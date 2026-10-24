package com.verifone.cat.devices.eps;

import java.util.List;
import java.util.stream.Collectors;

/**
 * What a look at the Commander's sockets found: the wanted links it holds and the ones it does not.
 * The read-only half of the Python CAT's {@code petrotest.commander_links.LinkPlan}; the import and
 * the one PMC restart ({@code ensure_payment_links}) are not ported here, because preflight never
 * restarts the Commander.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record LinkPlan(List<CommanderLink> present, List<CommanderLink> missing) {

    public LinkPlan {
        present = List.copyOf(present);
        missing = List.copyOf(missing);
    }

    /** Every wanted link is up: the Commander needs nothing, including no restart. */
    public boolean ready() {
        return missing.isEmpty();
    }

    /** Exactly one restart is owed, and only when something is actually missing. */
    public boolean restartNeeded() {
        return !missing.isEmpty();
    }

    /** The Python's {@code LinkPlan.summary()}, word for word. */
    public String summary() {
        if (ready()) {
            return "commander ready: " + present.size() + " payment link(s) up, no restart needed";
        }
        String names = missing.stream().map(CommanderLink::toString).collect(Collectors.joining(", "));
        return "commander missing " + missing.size() + " payment link(s): " + names;
    }
}
