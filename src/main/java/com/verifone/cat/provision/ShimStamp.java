package com.verifone.cat.provision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The pos-shim's build stamp, as the payload carries it. A port of the reading half of
 * {@code petrotest.provision.shim_stamp}: {@code tools/build_pos_shim.py} (Python) writes
 * {@code payload/pos/petrotest-pos-shim.build}, and the shim reports the same value as {@code GET /health}'s
 * {@code build}. Computing the stamp from the sources stays the Python CAT's, with the build.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ShimStamp {

    /** The stamp's file name, in {@code payload/pos/} and at the root of the shim jar. */
    public static final String STAMP_NAME = "petrotest-pos-shim.build";

    private ShimStamp() {}

    /** The stamp a build wrote into {@code payloadPos}; empty when there is none, or it is blank. */
    public static Optional<String> payloadStamp(Path payloadPos) {
        try {
            String text = Files.readString(payloadPos.resolve(STAMP_NAME), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + payloadPos.resolve(STAMP_NAME) + ": " + e.getMessage(), e);
        }
    }
}
