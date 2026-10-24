package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetSpecTest {

    @TempDir
    Path dir;

    @Test
    void theCommanderComesFirstThenEachRegisterWithTheCommandersAccount() {
        List<TargetSpec> targets = TargetSpec.fromRig(RigFixture.load(dir, RigFixture.RIG),
                Map.of("PETROTEST_TEST_SSH_KEY", "C:/keys/commander.pem"));

        assertEquals(List.of("commander (commander)", "topaz101 (register)"),
                targets.stream().map(TargetSpec::describe).toList());
        TargetSpec register = targets.get(1);
        assertEquals("root", register.sshUser());
        assertEquals(8801, register.shimPort());
        assertEquals(22, register.sshPort());
        assertEquals("C:/keys/commander.pem", register.sshKeyFilename());
    }

    @Test
    void aKeyVariableThatIsUnsetResolvesToNoKey() {
        List<TargetSpec> targets = TargetSpec.fromRig(RigFixture.load(dir, RigFixture.RIG), Map.of());

        assertNull(targets.get(0).sshKeyFilename());
        assertNull(targets.get(1).sshKeyFilename());
    }

    @Test
    void everyRegisterOfTheRigIsATargetNotJustTheFirst() {
        List<TargetSpec> targets = TargetSpec.fromRig(RigFixture.load(dir, RigFixture.TWO_REGISTERS), Map.of());

        assertEquals(List.of("commander", "topaz101", "topaz102"),
                targets.stream().map(TargetSpec::name).toList());
    }

    @Test
    void aSpecNamesItsHostAndAddressesNothingElse() {
        TargetSpec spec = new TargetSpec(TargetRole.REGISTER, "topaz101", "register.invalid", "root", 22, 8801, null);

        assertEquals("register.invalid", spec.toSshTarget().host());
        assertEquals("topaz101", spec.toSshTarget().name());
        assertThrows(IllegalArgumentException.class,
                () -> new TargetSpec(TargetRole.REGISTER, "x", "h", "root", 0, 8801, null));
    }
}
