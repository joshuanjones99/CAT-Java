package com.verifone.cat.build;

/**
 * One folder of a nightly build that carries a dist's overlay: the Python's {@code nightly.DistFolder}.
 *
 * @param dist    as the folder spells it: {@code buypass}, {@code testFep}, {@code generic} (from
 *                {@code retail-generic})
 * @param flavour one of {@link BuildContents#FLAVOURS}
 * @param folder  the folder's name inside the build: {@code buypass}, {@code foundation-buypass}, {@code viper-hps}
 */
public record DistFolder(String dist, String flavour, String folder) {

    /** It holds the installer jars beside its overlay. A viper folder does not. */
    public boolean installable() {
        return !BuildContents.VIPER.equals(flavour);
    }
}
