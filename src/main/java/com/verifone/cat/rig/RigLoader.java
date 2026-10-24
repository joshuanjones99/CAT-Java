package com.verifone.cat.rig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Finds and reads a rig file.
 *
 * <p>A rig is named ({@code lab}) or given as a path to a {@code .yaml} file. A name is
 * looked up in the rigs directory: {@code $PETROTEST_RIGS_DIR} when set (the variable
 * the Python CAT's {@code petrotest.rig.loader} reads, so both find the same files),
 * otherwise the Python CAT checkout's {@code rigs/}. This repository commits no rig file,
 * so no rig's addresses are copied here.
 */
public final class RigLoader {

    public static final String RIGS_DIR_ENV = "PETROTEST_RIGS_DIR";
    public static final Path DEFAULT_RIGS_DIR = Path.of("C:\\gitrepos\\CAT\\rigs");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RigLoader() {}

    public static Path rigsDir(Map<String, String> env) {
        String fromEnv = env.get(RIGS_DIR_ENV);
        return fromEnv == null || fromEnv.isBlank() ? DEFAULT_RIGS_DIR : Path.of(fromEnv);
    }

    /** Resolves {@code nameOrPath} against {@code rigsDir} and reads it. */
    public static Rig load(String nameOrPath, Path rigsDir) {
        Path file = nameOrPath.endsWith(".yaml") || nameOrPath.endsWith(".yml")
                ? Path.of(nameOrPath)
                : rigsDir.resolve(nameOrPath + ".yaml");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No rig file at " + file + ". Name a rig in " + rigsDir
                    + " (set $" + RIGS_DIR_ENV + " to look elsewhere), or pass a path to a .yaml file.");
        }
        Rig rig;
        try {
            rig = YAML.readValue(file.toFile(), Rig.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read rig file " + file + ": " + e.getMessage(), e);
        }
        return validated(rig, file);
    }

    private static Rig validated(Rig rig, Path file) {
        if (rig == null || rig.name() == null || rig.name().isBlank()) {
            throw new IllegalArgumentException("Rig file " + file + " has no name.");
        }
        if (rig.registers() == null || rig.registers().isEmpty()) {
            throw new IllegalArgumentException("Rig '" + rig.name() + "' lists no registers.");
        }
        for (Rig.Register register : rig.registers()) {
            if (register.id() == null || register.host() == null || register.host().isBlank()) {
                throw new IllegalArgumentException("Rig '" + rig.name() + "' has a register without an id and host.");
            }
        }
        return rig;
    }
}
