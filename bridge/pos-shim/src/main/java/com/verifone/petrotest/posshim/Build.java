package com.verifone.petrotest.posshim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Which build of the shim this JVM runs: the stamp {@code tools/build_pos_shim.py}
 * compiles into the jar's root as {@code petrotest-pos-shim.build}.
 *
 * <p>The stamp is one hash of everything the build compiles and ships (the shim's
 * sources and launcher, the retained agent, the TopazERP class, the build script;
 * {@code petrotest.provision.shim_stamp}). {@code GET /health} reports it as
 * {@code build}, and {@code provision} compares it with the payload's own stamp to
 * decide whether the shim running on the register is the one it just pushed
 * (found 2026-10-16: a shim 50 hours old, from older sources, kept serving because
 * the launcher's {@code start} saw it "already running").
 *
 * <p>{@code null} when the classpath carries no stamp: a jar built before
 * 2026-10-16, or the shim's classes run outside a jar. {@code provision} reads that
 * as a different build, which it is.
 */
final class Build {

    /** Absolute: at the jar's root, beside {@code META-INF/}, not under this package. */
    static final String RESOURCE = "/petrotest-pos-shim.build";

    private static final String ID = read();

    private Build() {}

    /** The stamp, or {@code null} when this shim was built without one. */
    static String id() {
        return ID;
    }

    private static String read() {
        try (InputStream in = Build.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            // A stamp that cannot be read is reported as none: provision then replaces
            // the shim, which costs a restart rather than leaving a stale one running.
            return null;
        }
    }
}
