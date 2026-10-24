package com.verifone.cat.pats.runner.handlers.cloudsettle;

import java.util.Objects;

import com.verifone.cat.pats.runner.ActionRegistry;

/**
 * The cloud-settle family's registration. PATS has no action of its own for the cloud settle: its waits are taken
 * around the cloud writes the HTTP and configuration families send, through {@link CloudSettle}. So this registers
 * no action name. It exists so the orchestrator wires every family the same way; the context it is given is the
 * one {@link CloudSettle} is built from.
 */
public final class CloudSettleFamily {

    private CloudSettleFamily() {
    }

    /**
     * Registers the cloud-settle family's actions: none.
     *
     * @throws NullPointerException when either argument is null
     */
    public static void register(ActionRegistry registry, CloudSettleContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
    }
}
