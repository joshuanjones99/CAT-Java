package com.verifone.cat.commander.links;

import java.util.List;

/**
 * What a check of the Commander found, and therefore what to do about it. A port of
 * {@code petrotest.commander_links.LinkPlan}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param present   the wanted links the Commander already holds
 * @param missing   the wanted links it does not
 * @param restarted set once a restart has actually been performed for this plan
 * @param notes     what was done, in order
 */
public record LinkPlan(List<CommanderLink> present, List<CommanderLink> missing, boolean restarted,
        List<String> notes) {

    public LinkPlan {
        present = List.copyOf(present);
        missing = List.copyOf(missing);
        notes = List.copyOf(notes);
    }

    /** An empty plan: nothing wanted, nothing missing. */
    public static LinkPlan empty() {
        return new LinkPlan(List.of(), List.of(), false, List.of());
    }

    /** Every wanted link is up. The Commander needs nothing, including no restart. */
    public boolean ready() {
        return missing.isEmpty();
    }

    /** Exactly one restart is owed, and only when something is actually missing. */
    public boolean restartNeeded() {
        return !missing.isEmpty();
    }

    /** The one line that says where the Commander stands. */
    public String summary() {
        if (ready()) {
            return "commander ready: " + present.size() + " payment link(s) up, no restart needed";
        }
        StringBuilder names = new StringBuilder();
        for (CommanderLink link : missing) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(link);
        }
        return "commander missing " + missing.size() + " payment link(s): " + names;
    }
}
