package com.verifone.cat.rig;

/**
 * The rig was still leased by another shard when the bounded acquire window ran out.
 *
 * <p>The Python {@code RigBusyError} subclasses {@code TimeoutError} so that callers already
 * handling the bounded-wait family catch it without a special case. Here it is unchecked, so
 * callers do not have to declare it on every method that might wait on a rig.
 */
public class RigBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RigBusyException(String message) {
        super(message);
    }
}
