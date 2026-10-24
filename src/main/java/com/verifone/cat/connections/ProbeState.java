package com.verifone.cat.connections;

/**
 * A probe's answer. {@link #UNKNOWN} is not a weaker {@link #DOWN}: it means the question
 * could not be put to the OS or the network, and it must never be reported as a dead device.
 */
public enum ProbeState {
    UP,
    DOWN,
    UNKNOWN
}
