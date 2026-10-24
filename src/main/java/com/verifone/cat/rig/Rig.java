package com.verifone.cat.rig;

import java.util.List;

/**
 * The part of a CAT rig file ({@code rigs/<name>.yaml}) this proof of concept reads.
 *
 * <p>The rig files stay in the Python CAT's checkout, one source of truth for both:
 * {@link RigLoader} reads them from there. Every other key in the file is ignored.
 */
public record Rig(String name, List<Register> registers, boolean posShim) {

    /** A Topaz register. {@code id} is stable across runs; {@code host} is where its shim answers. */
    public record Register(String id, String host) {}

    /**
     * The register called {@code id}, or the rig's first register (its primary) when
     * {@code id} is null.
     */
    public Register register(String id) {
        if (id == null) {
            return registers.get(0);
        }
        return registers.stream()
                .filter(r -> id.equals(r.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Rig '" + name + "' has no register '" + id
                        + "'; it has " + registers.stream().map(Register::id).toList() + "."));
    }
}
