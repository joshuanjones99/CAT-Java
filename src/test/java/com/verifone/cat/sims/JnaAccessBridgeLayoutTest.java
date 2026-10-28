package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The JNA structures against the sizes of the JDK's {@code AccessBridgePackages.h} on 64-bit Windows
 * (two-byte {@code wchar_t}, four-byte {@code jint}/{@code BOOL}). Only JNA's own layout code runs:
 * no Access Bridge DLL is loaded and no bridge call is made.
 */
class JnaAccessBridgeLayoutTest {

    @Test
    void accessibleContextInfoIsSixStringsAndElevenInts() {
        // name, description: 1024 wchar each; role, role_en_US, states, states_en_US: 256 each; 11 ints.
        assertEquals(2 * (2 * 1024 + 4 * 256) + 11 * 4, new JnaAccessBridge.AccessibleContextInfo().size());
    }

    @Test
    void theActionStructuresHoldTheirFixedArrays() {
        assertEquals(2 * 256, new JnaAccessBridge.AccessibleActionInfo().size());
        assertEquals(4 + 256 * 2 * 256, new JnaAccessBridge.AccessibleActions().size());
        assertEquals(4 + 32 * 2 * 256, new JnaAccessBridge.AccessibleActionsToDo().size());
    }
}
