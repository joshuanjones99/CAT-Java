package com.verifone.cat.pats;

/**
 * A case names an element the locator catalogue does not hold. Port of {@code petrotest.pats.elements.UnknownElementError}.
 */
public class UnknownElementException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnknownElementException(String message) {
        super(message);
    }
}
