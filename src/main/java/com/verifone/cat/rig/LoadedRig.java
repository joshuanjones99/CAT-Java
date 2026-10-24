package com.verifone.cat.rig;

import java.nio.file.Path;

/**
 * A rig together with the file it was read from.
 *
 * <p>The file path is not part of the YAML schema, so it is not a component of {@link Rig}: a
 * rig file that said {@code source_path:} is refused like any other unknown key. It lives here,
 * beside the rig, because the WireMock mapping directory is relative to the file.
 */
public record LoadedRig(Rig rig, Path source) {

    /**
     * {@code wiremock.mappings} resolved against the rig file's own directory, the way Python's
     * {@code Rig.wiremock_mappings} does it: a relative path is joined to the file's directory and
     * {@code ..} collapsed lexically. Null when the rig has no WireMock block.
     *
     * <p>Lexical only. The mapping directory may not exist on the machine that is validating the
     * rig file, so nothing here touches the disk.
     */
    public String wiremockMappings() {
        if (rig.wiremock() == null) {
            return null;
        }
        String raw = rig.wiremock().mappings();
        Path candidate = Path.of(raw);
        if (candidate.isAbsolute() || source == null) {
            return raw;
        }
        Path base = source.toAbsolutePath().getParent();
        return base.resolve(candidate).normalize().toString();
    }
}
