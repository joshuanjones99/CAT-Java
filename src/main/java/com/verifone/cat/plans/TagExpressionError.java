package com.verifone.cat.plans;

/** A tag expression Cucumber would not parse. */
public final class TagExpressionError extends IllegalArgumentException {

    public TagExpressionError(String message) {
        super(message);
    }
}
