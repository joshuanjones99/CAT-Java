package com.verifone.cat.commander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * {@link CommanderAccess}: the one place the harness builds its Commander access, as the Python's
 * {@code test_devices_commander.py}. Fakes only: a {@code .invalid} host and in-memory transports.
 */
class CommanderAccessTest {

    @TempDir
    Path dir;

    @Test
    void theRigsOwnTimeoutsSetTheSshBudget() {
        assertEquals(Timeouts.defaults().ssh(), CommanderAccess.sshTimeout(CommanderFixtures.rig(dir)));
        Rig slow = CommanderFixtures.rig(dir, "timeouts:\n  default_wait: 45s");
        assertEquals(Duration.ofSeconds(45), CommanderAccess.sshTimeout(slow));
    }

    @Test
    void theOtpReaderConnectsOnlyWhenCalled() {
        Rig rig = CommanderFixtures.rig(dir);
        List<String> opened = new ArrayList<>();
        List<InMemoryTransport> built = new ArrayList<>();
        CommanderAccess access = new CommanderAccess(rig, target -> {
            opened.add(target.host());
            InMemoryTransport t = new InMemoryTransport(target.host())
                    .command(CommanderAccess.OTP_COMMAND, new CommandResult(0, "123456\n", ""));
            built.add(t);
            return t;
        }, Map.of());

        var readOtp = access.otpReader();
        assertTrue(opened.isEmpty());
        assertEquals("123456", readOtp.get());
        assertEquals(List.of(CommanderFixtures.HOST), opened);
        assertEquals(List.of(CommanderAccess.OTP_COMMAND), built.get(0).ran());
        assertTrue(built.get(0).closed());
    }

    @Test
    void theClientHasPatsReadBudgetAndTheOtpReader() {
        PdkShim client = new CommanderAccess(CommanderFixtures.rig(dir), target -> new InMemoryTransport(target.host()),
                Map.of()).pdkShim();
        assertEquals(PdkShim.COMMANDER_READ_TIMEOUT, client.timeout());
        assertNotNull(client.otpReader());
        assertEquals(CommanderFixtures.HOST, client.commanderHost());
    }

    @Test
    void registersAreReachedWithTheCommandersAccount() {
        Rig rig = CommanderFixtures.rig(dir);
        CommanderAccess access = new CommanderAccess(rig, target -> new InMemoryTransport(target.host()), Map.of());
        Rig.Register reg2 = rig.register("reg2");
        assertEquals("r2.invalid", access.registerTransport(reg2).host());
        assertEquals("root", access.registerTarget(reg2).user());
        assertEquals("commander", access.target().name());
    }
}
