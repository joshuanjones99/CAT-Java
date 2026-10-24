package com.verifone.cat.atlassian;

/**
 * Confluence or Jira (or Xray) could not be read: no credentials, a transport failure, a
 * non-2xx status, a body that is not JSON, or Xray reporting errors.
 *
 * <p>The message never carries the token or the Xray secret. {@link AtlassianPlanSource}
 * turns this into a {@link com.verifone.cat.plans.PlanSourceUnavailable} so the catalogue
 * falls back to its cache and the shipped seed.
 */
public final class AtlassianUnavailable extends RuntimeException {

    public AtlassianUnavailable(String message) {
        super(message);
    }

    public AtlassianUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
