package com.verifone.cat.web;

import java.util.List;

import com.verifone.cat.tools.LabRegisters;
import com.verifone.cat.tools.RegisterTarget;

/**
 * Which registers the POS page checks, in the order it tries them (user, 2026-11-10): the rig's, when CAT was started
 * with a rig that loads; else the lab's registers 101 and 102 ({@link LabRegisters}). The operator enters nothing.
 */
@FunctionalInterface
interface PosRegister {

    /** The registers to check, or none with why (shown on the page). */
    record Resolved(List<RegisterTarget> targets, String why) {

        public Resolved {
            targets = targets == null ? List.of() : List.copyOf(targets);
            why = why == null ? "" : why;
        }

        static Resolved none(String why) {
            return new Resolved(List.of(), why);
        }

        /** The register with {@code host}, or null: only a register on this list may be pushed to or shown. */
        RegisterTarget byHost(String host) {
            return targets.stream().filter(t -> t.host().equals(host)).findFirst().orElse(null);
        }
    }

    Resolved current();

    /** None ever: CAT shows no register, and why. */
    static PosRegister none(String why) {
        Resolved none = Resolved.none(why);
        return () -> none;
    }

    /** These registers, always. */
    static PosRegister fixed(List<RegisterTarget> targets) {
        Resolved fixed = new Resolved(targets, "");
        return () -> fixed;
    }

    /** The rig's registers when it names any, else the lab's 101 and 102. */
    static PosRegister of(List<RegisterTarget> fromRig) {
        return fixed(fromRig == null || fromRig.isEmpty() ? LabRegisters.ALL : fromRig);
    }
}
