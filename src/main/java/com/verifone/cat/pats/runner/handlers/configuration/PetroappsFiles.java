package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.commander.CommanderInit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The petroapps checkout's files, read live at the moment a handler needs them: the configuration folders and
 * the simulator files under {@code AutomationTests/configurations/EndPoints/}. Nothing is copied, so a harness
 * never runs against a snapshot of petroapps (CLAUDE.md, the configurations rule).
 *
 * <p>Paths given to {@link #shared} are relative to the checkout's root, as the Python's {@code CaseStore.shared}
 * takes them ({@code AutomationTests/configurations/EndPoints/...}).
 */
public final class PetroappsFiles {

    /** The configurations folder, relative to the checkout's root. */
    public static final String CONFIGURATIONS = "AutomationTests/configurations";

    private final Path root;

    /** @param root the petroapps checkout's root, the folder that holds {@code AutomationTests} */
    public PetroappsFiles(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * The checkout the environment names ({@code PETROAPPS_ROOT}), else the default the Python uses.
     */
    public static PetroappsFiles fromEnvironment(Map<String, String> environ) {
        String value = environ == null ? null : environ.get(CommanderInit.PETROAPPS_ENV);
        Path root = value == null || value.isBlank() ? CommanderInit.DEFAULT_PETROAPPS_ROOT : Path.of(value.strip());
        return new PetroappsFiles(root);
    }

    /** The checkout's root. */
    public Path root() {
        return root;
    }

    /** {@code AutomationTests/configurations}, read live. */
    public Path configurations() {
        return root.resolve(CONFIGURATIONS);
    }

    /**
     * The bytes of {@code name} in {@code folder}, a path relative to the checkout's root.
     *
     * @throws java.nio.file.NoSuchFileException when the file is not there
     * @throws IOException                       when it cannot be read, or the name leaves the checkout
     */
    public byte[] shared(String folder, String name) throws IOException {
        Path file = root.resolve(folder).resolve(name).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("the file " + name + " is outside the petroapps checkout");
        }
        return Files.readAllBytes(file);
    }
}
