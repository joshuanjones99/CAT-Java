package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class PowerShellProcessOpsTest {

    @Test
    void theCommandReachesPowerShellWithItsDoubleQuotesIntact() {
        String command = "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\"";
        String decoded = new String(Base64.getDecoder().decode(PowerShellProcessOps.encode(command)),
                StandardCharsets.UTF_16LE);
        assertEquals("$ProgressPreference = 'SilentlyContinue'; " + command, decoded);
    }

    @Test
    void theListingNamesEveryPortAndEveryImage() {
        String command = PowerShellProcessOps.listingCommand(List.of("java.exe", "javaw.exe"), List.of(9001, 41107));
        assertTrue(command.contains("-LocalPort 9001,41107"), command);
        assertTrue(command.contains("Name='java.exe' or Name='javaw.exe'"), command);
    }

    @Test
    void theListingWithNoNamesAndNoPortsSaysSoRatherThanQueryingEverything() {
        assertEquals("exit 0", PowerShellProcessOps.listingCommand(List.of(), List.of()));
    }
}
