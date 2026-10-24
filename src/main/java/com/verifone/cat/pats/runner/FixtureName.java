package com.verifone.cat.pats.runner;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixtures a PATS case's test can declare, in the order a test lists them (the Python's
 * {@code registry.fixtures_for} order). A case's actions name the fixtures they need; the runner
 * fetches a fixture only through {@link FixtureSource}, so an action never builds one it did not declare.
 */
public enum FixtureName {
    /** The POS page (the driver, the page objects). */
    POS("pos"),
    /** PATS's own RMI read of the pushed overlay; the pos-shim replaces it on this port. */
    LEGACY_RMI("legacy_rmi"),
    /** The pinpad (the physical pinpad or the in-process double). */
    PINPAD("pinpad"),
    /** The DCR's session listener and its print count. */
    DCR("dcr"),
    /** The fuel pumps. */
    FUEL_PUMPS("fuel_pumps"),
    /** The Commander's configuration client (transaction listing, validation and CGILink). */
    COMMANDER_CONFIG("commander_config"),
    /** The configuration files the case's {@code IMPORT_CONFIGURATION} copies from. */
    CONFIGURATIONS("configurations");

    private final String key;

    FixtureName(String key) {
        this.key = key;
    }

    /** The name the Python's pytest fixture and its requirement table use. */
    public String key() {
        return key;
    }

    /** The fixture with that name, or empty. */
    public static Optional<FixtureName> ofKey(String key) {
        return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
    }
}
