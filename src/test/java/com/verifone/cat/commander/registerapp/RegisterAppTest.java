package com.verifone.cat.commander.registerapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.Say;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code petrotest register-app}: a register left in the SCO app is switched back to POS. A port of
 * {@code tests/unit/test_register_app.py}. The SSH transport and the POS page are the fakes; the decision, the
 * fallback from the App Selector touch to the selector's own writes, and the wait are the real code.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class RegisterAppTest {

    @TempDir
    Path dir;

    /** A pauser that never waits: a check is tried up to 50 times. */
    static final Pauser INSTANT = new Pauser() {
        @Override
        public void pause(Duration duration) {}

        @Override
        public boolean until(java.util.function.BooleanSupplier check, Duration timeout, Duration interval) {
            for (int i = 0; i < 50; i++) {
                if (check.getAsBoolean()) {
                    return true;
                }
            }
            return false;
        }
    };

    /** One register's SSH shell over its {@code config_system.prop}: the app it runs, and every command sent. */
    static final class RegisterShell implements Transport {

        String app;
        final List<String> commands = new ArrayList<>();

        RegisterShell(String app) {
            this.app = app;
        }

        @Override
        public String host() {
            return "topaz101.test";
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            commands.add(command);
            if (command.startsWith("grep")) {
                return new CommandResult(0, RegisterApp.ACTIVE_APP_PROP + "=" + app + "\n", "");
            }
            if (command.equals(RegisterApp.SWITCH_TO_POS)) {
                app = "pos";
            }
            return new CommandResult(0, "", "");
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    /** A rig with two registers ({@code reg1}, {@code reg2}) written under {@code dir} and loaded. */
    private Rig rig() throws Exception {
        Files.createDirectories(dir);
        String yaml = String.join("\n", "name: bench", "commander:", "  host: commander.invalid", "  ssh_user: root",
                "  eps_port: 41107", "registers:", "  - id: reg1", "    host: r1.invalid", "  - id: reg2",
                "    host: r2.invalid", "controller:", "  host: 127.0.0.1", "pinpad: viperpay", "pos_shim: true",
                "build:", "  expected_version: \"057.02\"", "");
        Path file = dir.resolve("bench.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return RigLoader.loadFile(file).rig();
    }

    /** The POS seams: the hosted app as given, and a press that either works or fails with a message. */
    private static RegisterApp.PosSeams seams(Map<String, String> hosted, RuntimeException press,
            Map<String, RegisterShell> shells) {
        return new RegisterApp.PosSeams() {
            @Override
            public Optional<String> hostedApp(Rig.Register register) {
                String app = hosted.containsKey(register.id()) ? hosted.get(register.id())
                        : shells.get(register.id()).app;
                return Optional.ofNullable(app);
            }

            @Override
            public void pressPosPanel(Rig.Register register) {
                if (press != null) {
                    throw press;
                }
            }
        };
    }

    @Test
    void thePropertyIsReadAndDefaultsToPos() {
        RegisterShell sco = new RegisterShell("SCO");
        assertEquals("sco", RegisterApp.activeApp(sco, Duration.ofSeconds(5)));
        Transport absent = new Transport() {
            @Override
            public String host() {
                return "x";
            }

            @Override
            public CommandResult run(String command, Duration timeout) {
                return new CommandResult(1, "", "");
            }

            @Override
            public void put(String remotePath, byte[] content, int mode, Duration timeout) {}

            @Override
            public Optional<byte[]> get(String remotePath, Duration timeout) {
                return Optional.empty();
            }

            @Override
            public void mkdir(String remotePath, Duration timeout) {}

            @Override
            public boolean exists(String remotePath, Duration timeout) {
                return false;
            }

            @Override
            public void close() {}
        };
        assertEquals("pos", RegisterApp.activeApp(absent, Duration.ofSeconds(5)));
    }

    @Test
    void aRegisterInPosIsLeftAlone() throws Exception {
        Map<String, RegisterShell> shells = new HashMap<>();
        shells.put("reg1", new RegisterShell("pos"));
        shells.put("reg2", new RegisterShell("pos"));
        Say.Recorded say = new Say.Recorded();
        boolean ok = RegisterApp.run(rig(), r -> shells.get(r.id()), seams(Map.of("reg1", "pos", "reg2", "pos"),
                new IllegalStateException("touched"), shells), INSTANT, Duration.ofMillis(50), say);
        assertTrue(ok);
        assertFalse(shells.get("reg1").commands.contains(RegisterApp.SWITCH_TO_POS));
        assertTrue(shells.get("reg2").commands.stream().noneMatch(RegisterApp.SWITCH_TO_POS::equals));
        assertTrue(say.outText().contains("register-app: reg1 runs the POS app"), say.outText());
    }

    @Test
    void withNoSwitcherDrawnTheSelectorsOwnWritesSwitchIt() throws Exception {
        // Reg101, 2026-10-20: the SCO welcome screen drew no App Selector switcher.
        Map<String, RegisterShell> shells = new HashMap<>();
        shells.put("reg1", new RegisterShell("sco"));
        shells.put("reg2", new RegisterShell("pos"));
        Say.Recorded say = new Say.Recorded();
        boolean ok = RegisterApp.run(rig(), r -> shells.get(r.id()), seams(Map.of(), new IllegalStateException(
                "the switcher to be present"), shells), INSTANT, Duration.ofSeconds(5), say);
        assertTrue(ok, say.errText());
        assertTrue(shells.get("reg1").commands.contains(RegisterApp.SWITCH_TO_POS));
        assertEquals("pos", shells.get("reg1").app);
    }

    @Test
    void theHostedPageNotTheProperty_SaysWhichAppRuns() throws Exception {
        // A restart that failed left the property pos and the SCO app running (14:00): the page decides, and it
        // never reports the POS app, so the register is not back.
        Map<String, RegisterShell> shells = new HashMap<>();
        shells.put("reg1", new RegisterShell("pos"));
        shells.put("reg2", new RegisterShell("pos"));
        Say.Recorded say = new Say.Recorded();
        boolean ok = RegisterApp.run(rig(), r -> shells.get(r.id()),
                seams(Map.of("reg1", "sco", "reg2", "pos"), null, shells), INSTANT, Duration.ofMillis(50), say);
        assertFalse(ok);
        assertTrue(say.errText().contains("reg1 is not back in the POS app"), say.errText());
    }

    @Test
    void theRestartRunsAsTopazInALoginShell() {
        assertTrue(RegisterApp.SWITCH_TO_POS.contains("su - topaz -c"));
        assertTrue(RegisterApp.SWITCH_TO_POS.indexOf("config_system.prop")
                < RegisterApp.SWITCH_TO_POS.indexOf("restartapp"));
    }

    @Test
    void theHostedFrameUrlNamesItsApp() {
        assertEquals(Optional.of("pos"), RegisterApp.appForFrame("https://reg1/posuiapp/index.html"));
        assertEquals(Optional.of("sco"), RegisterApp.appForFrame("https://reg1/scouiapp/"));
        assertEquals(Optional.empty(), RegisterApp.appForFrame("https://reg1/other/"));
    }
}
