/**
 * The init action family: CONTROL_FUNCTION, and the reply pause before each action that is not a SLEEP. The waits
 * are {@link com.verifone.cat.pats.runner.handlers.inits.InitsState}; the SLEEP handler (the POS family) calls its
 * {@code afterInit} and {@code beforeInit}. Registered by
 * {@link com.verifone.cat.pats.runner.handlers.inits.InitsFamily#register}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
package com.verifone.cat.pats.runner.handlers.inits;
