package com.verifone.cat.preflight;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Rig contexts and resources shared by the preflight tests. No rig, no network. */
final class Fixtures {

    private Fixtures() {}

    /** A context whose POS is {@code pos} for both surfaces; the EPS and config surfaces have no source. */
    static RigContext rig(Surfaces.PosStatus pos, Surfaces.PosControl control) {
        return new RigContext("testrig", pos, control,
                Surfaces.unavailable(Surfaces.EpsProbe.class, "EPS probe", "not under test"),
                Surfaces.unavailable(Surfaces.ConfigStatus.class, "config", "not under test"),
                Surfaces.unavailable(Surfaces.ConfigControl.class, "config", "not under test"),
                new Expectations("057.00", "BP-UAT", "PATS", "x", List.of(1, 2)));
    }

    static RigContext rig(FakePos pos) {
        return rig(pos, pos);
    }

    /** A resource under src/test/resources/preflight/. */
    static String resource(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/preflight/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No preflight fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
