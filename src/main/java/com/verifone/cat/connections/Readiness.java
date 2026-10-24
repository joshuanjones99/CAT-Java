package com.verifone.cat.connections;

/**
 * One System Checks line. A failed {@code required} line stops the run; an optional one is shown
 * and does not.
 *
 * @param ok       whether the check passed
 * @param label    the line's name, quoted bare in the step summary
 * @param detail   why it passed or failed, with the hint an operator needs
 * @param required whether a failure here stops the run
 */
public record Readiness(boolean ok, String label, String detail, boolean required) {}
