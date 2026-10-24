package com.verifone.cat.collect;

/**
 * The repository or revision could not be read, or git could not be run. Nothing was collected.
 *
 * <p>The Python CAT's {@code petrotest.collect.CollectError}.
 */
public class CollectException extends RuntimeException {

    public CollectException(String message) {
        super(message);
    }

    public CollectException(String message, Throwable cause) {
        super(message, cause);
    }
}
