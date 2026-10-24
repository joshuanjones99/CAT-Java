package com.verifone.cat.pos.driver;

import java.nio.file.Path;

/**
 * Where rung-0 evidence goes: {@code artifacts/<run>/<test-id>/} (DESIGN.md 6.3). Port of the
 * Python {@code ArtifactSink} protocol.
 */
public interface ArtifactSink {

    Path pathFor(String name);
}
