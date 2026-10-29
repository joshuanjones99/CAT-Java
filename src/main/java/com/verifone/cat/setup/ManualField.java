package com.verifone.cat.setup;

/**
 * One text field a setup item's sign-in card collects: the Python CAT's {@code cat.setup.base.ManualField}.
 *
 * <p>Opt-in: most manual items (install a JDK, stage the simulators) have nothing a person could type. An item that
 * does (a token, a login) declares its fields and a {@link SetupItem.FieldSaver}; the Setup page renders an input per
 * field and hands the values back as {@code {key: text}}.
 *
 * @param key    the key in the map the saver receives, e.g. {@code email}
 * @param label  the prompt shown above the input
 * @param secret true masks the input (a token or password), with a Show control
 */
public record ManualField(String key, String label, boolean secret) {

    public ManualField {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a manual field needs a key");
        }
        label = label == null ? key : label;
    }
}
