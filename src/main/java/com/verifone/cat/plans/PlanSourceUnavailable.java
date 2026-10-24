package com.verifone.cat.plans;

/**
 * A plan source could not produce its rows: no credentials, no network, an HTTP error, or
 * a page that moved.
 *
 * <p>{@link PlanCatalogue} catches this and falls back to the cache, then the shipped seed,
 * so an outage degrades the picker instead of emptying it.
 */
public final class PlanSourceUnavailable extends RuntimeException {

    public PlanSourceUnavailable(String message) {
        super(message);
    }
}
