package com.verifone.cat.devices.commander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

/** {@link CommanderSuiteInfo} over an in-memory transport: the Python's {@code test_suiteinfo.py} cases. */
class CommanderSuiteInfoTest {

    private static final String READ = "cat " + CommanderSuiteInfo.SUITEINFO_PATH;

    @Test
    void keysAndValuesAreStrippedAndCommentsSkipped() {
        Map<String, String> fields = CommanderSuiteInfo.parse(
                "# a comment=no\nSuite_Name     =Commander\nBase_Level     =057.02.00\n\nnot a pair\n"
                        + "Commit_Hash =0cb76ccfb492abcd\r\nTimestamp=2026-10-10T14:17:38\n");
        assertEquals(Map.of("Suite_Name", "Commander", "Base_Level", "057.02.00", "Commit_Hash", "0cb76ccfb492abcd",
                "Timestamp", "2026-10-10T14:17:38"), fields);
    }

    @Test
    void readsTheFileWithOneCatAndClosesTheTransport() {
        InMemoryTransport box = new InMemoryTransport("commander.invalid")
                .command(READ, new CommandResult(0, "Base_Level =057.02.00\nCommit_Hash=abc\n", ""));
        Map<String, String> fields = CommanderSuiteInfo.read(() -> box, Duration.ofSeconds(5));
        assertEquals("057.02.00", fields.get("Base_Level"));
        assertEquals(java.util.List.of(READ), box.ran());
        assertTrue(box.closed());
    }

    @Test
    void aMissingFileOrBaseLevelIsADeviceError() {
        InMemoryTransport missing = new InMemoryTransport("commander.invalid")
                .command(READ, new CommandResult(1, "", "cat: no such file"));
        DeviceError e = assertThrows(DeviceError.class, () -> CommanderSuiteInfo.read(() -> missing,
                Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("exit 1: cat: no such file"), e.getMessage());

        InMemoryTransport noLevel = new InMemoryTransport("commander.invalid")
                .command(READ, new CommandResult(0, "Suite_Name=x\n", ""));
        e = assertThrows(DeviceError.class, () -> CommanderSuiteInfo.read(() -> noLevel, Duration.ofSeconds(5)));
        assertTrue(e.getMessage().endsWith("has no Base_Level"), e.getMessage());
    }
}
