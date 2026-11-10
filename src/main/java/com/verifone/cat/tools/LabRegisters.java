package com.verifone.cat.tools;

import java.util.List;

import com.verifone.cat.posshim.PosShimClient;

/**
 * The lab's registers, which the POS page checks when CAT was started without a rig (user, 2026-11-10: "101 and 102
 * will always be a register. So, just check for 101 and 102.").
 *
 * <p>LAB CARVE-OUT from the no-IP-literals rule (CLAUDE.md rule 3, {@code NoIpLiteralsTest}): this file, and only
 * this file, may name these two addresses; the gate fails on any other address here or on these anywhere else. A
 * rig, when one loads, still names its own registers and wins over this list.
 */
public final class LabRegisters {

    /** Register 101 and register 102, on the lab's register subnet, in the order the POS page tries them. */
    public static final List<RegisterTarget> ALL = List.of(
            new RegisterTarget("Reg101", "192.168.31.101", PosShimClient.DEFAULT_SHIM_PORT,
                    RegisterTarget.DEFAULT_CDP_PORT),
            new RegisterTarget("Reg102", "192.168.31.102", PosShimClient.DEFAULT_SHIM_PORT,
                    RegisterTarget.DEFAULT_CDP_PORT));

    private LabRegisters() {}
}
