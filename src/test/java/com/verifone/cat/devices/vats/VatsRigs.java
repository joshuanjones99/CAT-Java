package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * A test rig file with a {@code vats:} block, loaded through the real strict loader. Its addresses are never a real
 * device: the Commander, register and pinpad are RFC 2606 {@code .invalid} names unless a test passes loopback.
 */
public final class VatsRigs {

    private VatsRigs() {}

    /**
     * Writes and loads {@code <dir>/vatsbench.yaml}.
     *
     * @param pinpad   {@code vats} or {@code viperpay}
     * @param vatsBody the {@code vats:} block's lines, each indented two spaces; null for none
     */
    public static Rig load(Path dir, String pinpad, String vatsBody) throws IOException {
        String yaml = String.join("\n",
                "name: vatsbench",
                "commander:",
                "  host: commander.invalid",
                "  ssh_user: root",
                "registers:",
                "  - id: topaz101",
                "    host: register.invalid",
                "    cdp_port: 9222",
                "controller:",
                "  host: 127.0.0.1",
                "pinpad: " + pinpad,
                "pos_shim: true",
                vatsBody == null ? "" : "vats:\n" + vatsBody,
                "");
        Path file = dir.resolve("vatsbench.yaml");
        Files.writeString(file, yaml);
        return RigLoader.load(file.toString(), dir);
    }

    /** A {@code pinpad: vats} rig whose terminal is {@code host}{@code :port}, the other ports the defaults. */
    public static Rig vats(Path dir, String host, int port) throws IOException {
        return load(dir, "vats", "  host: " + host + "\n  port: " + port + "\n  km4_port: 11999");
    }
}
