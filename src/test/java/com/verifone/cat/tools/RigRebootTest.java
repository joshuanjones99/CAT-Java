package com.verifone.cat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

/** {@link RigReboot} over an in-memory transport and recorded waits. Evidence about this repository only. */
class RigRebootTest {

    private static final TargetSpec REGISTER = new TargetSpec(TargetRole.REGISTER, "Reg101", "reg101.invalid",
            "root", 22, 8801, null);
    private static final TargetSpec COMMANDER = new TargetSpec(TargetRole.COMMANDER, "commander",
            "commander.invalid", "root", 22, 8801, null);

    private final InMemoryTransport box = new InMemoryTransport("reg101.invalid");
    private final List<String> sshWaits = new ArrayList<>();
    private final List<String> cgilinkWaits = new ArrayList<>();
    private final Say.Recorded say = new Say.Recorded();

    private RigReboot.Seams seams(boolean sshBack, boolean cgilinkBack) {
        return new RigReboot.Seams(spec -> box, (spec, at) -> {
            sshWaits.add(spec.name());
            return sshBack;
        }, host -> {
            cgilinkWaits.add(host);
            return cgilinkBack;
        });
    }

    @Test
    void aRegisterIsCheckedRebootedAndWaitedForOnSshOnly() {
        assertTrue(RigReboot.reboot(REGISTER, seams(true, true), say));

        assertEquals(List.of("cat /proc/uptime", "reboot"), box.ran());
        assertEquals(List.of("Reg101"), sshWaits);
        assertEquals(List.of(), cgilinkWaits, "a register has no CGILink to wait for");
        assertTrue(say.errLines().isEmpty(), say.errLines().toString());
    }

    @Test
    void theCommanderIsAlsoWaitedForOnCgilink() {
        assertTrue(RigReboot.reboot(COMMANDER, seams(true, true), say));

        assertEquals(List.of("commander.invalid"), cgilinkWaits);
        assertTrue(say.outLines().stream().anyMatch(l -> l.contains("CGILink answers")), say.outLines().toString());
    }

    @Test
    void aBoxThatCannotBeReachedIsNeverRebooted() {
        box.failRunsWith(new DeviceError("connection refused"));

        assertFalse(RigReboot.reboot(REGISTER, seams(true, true), say));

        assertEquals(List.of("cat /proc/uptime"), box.ran(), "no reboot after a failed reachability check");
        assertEquals(List.of(), sshWaits);
        assertTrue(last(say.errLines()).contains("nothing was rebooted"), say.errLines().toString());
    }

    @Test
    void aSessionThatCannotOpenIsNeverRebooted() {
        RigReboot.Seams unreachable = new RigReboot.Seams(spec -> {
            throw new DeviceError("no route to host");
        }, (spec, at) -> true, host -> true);

        assertFalse(RigReboot.reboot(REGISTER, unreachable, say));
        assertTrue(last(say.errLines()).contains("no route to host"), say.errLines().toString());
    }

    @Test
    void aFailingUptimeIsNotTakenAsReachable() {
        box.command("cat /proc/uptime", new CommandResult(255, "", "denied"));

        assertFalse(RigReboot.reboot(REGISTER, seams(true, true), say));
        assertEquals(List.of("cat /proc/uptime"), box.ran());
    }

    @Test
    void aBoxThatNeverComesBackFails() {
        assertFalse(RigReboot.reboot(REGISTER, seams(false, true), say));
        assertTrue(last(say.errLines()).contains("did not come back on SSH"), say.errLines().toString());
    }

    @Test
    void aCommanderWhoseCgilinkNeverAnswersFails() {
        assertFalse(RigReboot.reboot(COMMANDER, seams(true, false), say));
        assertTrue(last(say.errLines()).contains("CGILink did not answer"), say.errLines().toString());
    }

    private static String last(List<String> lines) {
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }
}
