package com.verifone.cat.connections;

/**
 * The verdict on one {@link Target}: its state, and the one-line detail an operator reads.
 *
 * @param target the target's name, as the status row is labelled
 * @param state  up, down or unknown; unknown means the probe could not answer, not that
 *               the device is dead
 * @param detail why, in words an operator can act on
 * @param kind   the kind of probe that produced this result
 */
public record ProbeResult(String target, ProbeState state, String detail, Target.Kind kind) {}
