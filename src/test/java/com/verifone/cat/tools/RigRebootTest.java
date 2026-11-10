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

    // ------------------------------------------------------------ Reboot Commander + C18

    private final InMemoryTransport commanderBox = new InMemoryTransport("commander.invalid");
    private final List<String> order = new ArrayList<>();

    /** One transport per box, each recording into {@link #order} as it runs a command. */
    private RigReboot.Seams together(boolean registerBack) {
        return new RigReboot.Seams(spec -> {
            InMemoryTransport t = spec.role() == TargetRole.COMMANDER ? commanderBox : box;
            return new RecordingTransport(t, spec.name(), order);
        }, (spec, at) -> {
            order.add("wait " + spec.name());
            sshWaits.add(spec.name());
            return spec.role() == TargetRole.COMMANDER || registerBack;
        }, host -> {
            cgilinkWaits.add(host);
            return true;
        });
    }

    @Test
    void theCommanderAndTheRegisterAreBothRebootedBeforeAnyWait() {
        assertTrue(RigReboot.rebootTogether(List.of(COMMANDER, REGISTER), together(true), say));

        assertEquals(List.of("commander: cat /proc/uptime", "Reg101: cat /proc/uptime", "commander: reboot",
                "Reg101: reboot", "wait commander", "wait Reg101"), order);
        assertEquals(List.of("commander.invalid"), cgilinkWaits);
        assertTrue(say.errLines().isEmpty(), say.errLines().toString());
    }

    @Test
    void ifOneBoxDoesNotAnswerNeitherIsRebooted() {
        box.command("cat /proc/uptime", new CommandResult(255, "", "denied"));

        assertFalse(RigReboot.rebootTogether(List.of(COMMANDER, REGISTER), together(true), say));

        assertEquals(List.of("cat /proc/uptime"), commanderBox.ran(), "the Commander answered but was not rebooted");
        assertEquals(List.of("cat /proc/uptime"), box.ran());
        assertEquals(List.of(), sshWaits);
        assertTrue(commanderBox.closed(), "the answered session is closed");
        assertTrue(last(say.errLines()).contains("nothing was rebooted, not even the boxes that answered"),
                say.errLines().toString());
    }

    @Test
    void aRegisterThatDoesNotComeBackFailsTheWholeButEveryBoxIsWaitedFor() {
        assertFalse(RigReboot.rebootTogether(List.of(REGISTER, COMMANDER), together(false), say));

        assertEquals(List.of("Reg101", "commander"), sshWaits, "the Commander is still waited for");
        assertTrue(say.errLines().stream().anyMatch(l -> l.contains("Reg101 (reg101.invalid) did not come back")),
                say.errLines().toString());
    }

    /** A transport that names each command it runs with its box, into one shared list. */
    private static final class RecordingTransport implements com.verifone.cat.provision.transport.Transport {
        private final InMemoryTransport inner;
        private final String name;
        private final List<String> order;

        RecordingTransport(InMemoryTransport inner, String name, List<String> order) {
            this.inner = inner;
            this.name = name;
            this.order = order;
        }

        @Override
        public CommandResult run(String command, java.time.Duration timeout) {
            order.add(name + ": " + command);
            return inner.run(command, timeout);
        }

        @Override
        public void close() {
            inner.close();
        }

        @Override
        public String host() {
            return inner.host();
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, java.time.Duration timeout) {
            inner.put(remotePath, content, mode, timeout);
        }

        @Override
        public java.util.Optional<byte[]> get(String remotePath, java.time.Duration timeout) {
            return inner.get(remotePath, timeout);
        }

        @Override
        public void mkdir(String remotePath, java.time.Duration timeout) {
            inner.mkdir(remotePath, timeout);
        }

        @Override
        public boolean exists(String remotePath, java.time.Duration timeout) {
            return inner.exists(remotePath, timeout);
        }
    }

    private static String last(List<String> lines) {
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }
}
