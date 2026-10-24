package com.verifone.cat.pats.feature;

/**
 * A step line that is not exactly one PATS action phrase. Port of the {@code LookupError} that
 * {@code petrotest.pats.feature.build_line} raises.
 */
public class PhraseLookupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PhraseLookupException(String message) {
        super(message);
    }
}
