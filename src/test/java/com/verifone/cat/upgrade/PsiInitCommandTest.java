package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.rig.Rig;

/** {@code cat-java psi-init}: its flags and exit codes, over a fake CGILink. No test reaches a device. */
class PsiInitCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> sent = new ArrayList<>();

    private String rigFile() throws URISyntaxException {
        return Path.of(getClass().getResource("/upgrade/rigs/psi.yaml").toURI()).toString();
    }

    private int run(boolean fails, String... argv) {
        PsiInitCommand command = new PsiInitCommand((Rig rig, Map<String, String> env, com.verifone.cat.commander.Say say) ->
                new PsiInit((host, password, expired) -> (cmd, payload) -> {
                    sent.add(cmd);
                    if (fails) {
                        throw new com.verifone.cat.devices.DeviceError("no route");
                    }
                    return "<r>true</r>";
                }, new PsiInit.Registers() {
                    @Override
                    public PsiInit.PairingScreen open(Rig r, Rig.Register register) {
                        throw new AssertionError("pairing not expected");
                    }

                    @Override
                    public boolean atLoginScreen(Rig r, Rig.Register register) {
                        return true;
                    }
                }, dir.resolve("psi_message.txt"), d -> { }, say));
        return command.run(argv, Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void noRigIsAUsageError() {
        assertEquals(PsiInitCommand.EXIT_USAGE, run(false));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--rig is required"));
    }

    @Test
    void anUnknownFlagIsAUsageError() {
        assertEquals(PsiInitCommand.EXIT_USAGE, run(false, "--rig", "x", "--pair"));
    }

    @Test
    void anUnloadableRigIsAUsageError() {
        assertEquals(PsiInitCommand.EXIT_USAGE, run(false, "--rig", dir.resolve("nope.yaml").toString()));
    }

    @Test
    void noPairingWithNoMessageFileExitsZeroAndLogsInNowhere() throws Exception {
        assertEquals(PsiInitCommand.EXIT_OK, run(false, "psi-init", "--rig", rigFile(), "--no-pairing"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void aFailedResetExitsOne() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("psi_message.txt"), "Password : Ab1");
        assertEquals(PsiInitCommand.EXIT_FAILED, run(true, "--rig", rigFile(), "--no-pairing"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("psi-init failed: no route"));
    }
}
