package com.verifone.cat.pos.inspect;

/**
 * The register's screen could not be shown or driven: no DevTools endpoint, no page, or the page went away. The
 * message is what the POS page shows the operator.
 */
public class InspectError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InspectError(String message) {
        super(message);
    }
}
