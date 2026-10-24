package com.verifone.cat.commander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * {@link CommanderTrust}: a simulator's certificate into PMC's {@code cacerts}, once, as the Python's
 * {@code test_commander_trust.py}. A fake Commander shell runs keytool; the two certificates are real
 * self-signed ones made with the JDK's keytool for these tests (no private key is kept). Evidence about this
 * repository, not about a Commander.
 */
class CommanderTrustTest {

    private static final String A = CommanderFixtures.resource("simulator-a.pem");
    private static final String B = CommanderFixtures.resource("simulator-b.pem");

    @TempDir
    Path dir;

    /** keytool on a {@code cacerts} holding {@code held} (alias to PEM). */
    private static class Shell implements Transport {

        final Map<String, String> held = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public String host() {
            return CommanderFixtures.HOST;
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            commands.add(command);
            String alias = command.contains("-alias ") ? command.split("-alias ", 2)[1].split(" ", 2)[0] : "";
            if (command.contains(" -list ")) {
                String pem = held.get(alias);
                return pem == null ? new CommandResult(1, "", "")
                        : new CommandResult(0, "SHA256: " + CommanderTrust.pemFingerprint(pem), "");
            }
            if (command.contains(" -delete ")) {
                held.remove(alias);
            }
            if (command.contains(" -importcert ")) {
                String path = command.split("-file ", 2)[1].split(" ", 2)[0];
                held.put(alias, new String(files.get(path), StandardCharsets.US_ASCII));
            }
            return new CommandResult(0, "", "");
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            files.put(remotePath, content);
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            return Optional.ofNullable(files.get(remotePath));
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {}

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            return files.containsKey(remotePath);
        }

        @Override
        public void close() {}
    }

    @Test
    void theFingerprintIsTheOneKeytoolPrints() {
        // keytool -printcert -file simulator-a.pem, JDK 17.0.5, 2026-10-24.
        assertEquals("49:E2:A8:96:71:A7:09:AB:45:C8:1B:5E:BD:11:8E:CF:01:65:20:8B:6A:A3:04:56:F9:1F:93:55:62:6A:2D:57",
                CommanderTrust.pemFingerprint(A));
    }

    @Test
    void aNewCertificateIsImportedUnderItsAlias() {
        Shell shell = new Shell();
        assertTrue(CommanderTrust.trustCertificate(shell, A, "cat-cullinan", new Say.Recorded()));
        assertEquals(A, shell.held.get("cat-cullinan"));
        assertTrue(shell.commands.stream().anyMatch(c -> c.contains(" -importcert ") && c.contains(CommanderTrust.CACERTS)));
    }

    @Test
    void theSameCertificateIsLeftAlone() {
        Shell shell = new Shell();
        shell.held.put("cat-cullinan", A);
        Say.Recorded say = new Say.Recorded();
        assertFalse(CommanderTrust.trustCertificate(shell, A, "cat-cullinan", say));
        assertTrue(shell.commands.stream().noneMatch(c -> c.contains(" -importcert ")));
        assertTrue(say.outText().startsWith("commander-trust: cat-cullinan already trusted (49:E2:A8:96:71:A7:09:AB"));
    }

    @Test
    void aRegeneratedCertificateReplacesTheOldOne() {
        // HTTPSIM makes a new key at every start: the alias must follow it.
        Shell shell = new Shell();
        shell.held.put("cat-cullinan", A);
        assertTrue(CommanderTrust.trustCertificate(shell, B, "cat-cullinan", new Say.Recorded()));
        assertEquals(B, shell.held.get("cat-cullinan"));
        assertTrue(shell.commands.stream().anyMatch(c -> c.contains(" -delete ")));
    }

    @Test
    void anImportThatDoesNotReadBackRaises() {
        Shell refusing = new Shell() {
            @Override
            public CommandResult run(String command, Duration timeout) {
                if (command.contains(" -importcert ")) {
                    commands.add(command);
                    return new CommandResult(1, "", "keystore locked");
                }
                return super.run(command, timeout);
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CommanderTrust.trustCertificate(refusing, A, "cat-cullinan", new Say.Recorded()));
        assertTrue(e.getMessage().contains("keystore locked"), e.getMessage());
    }

    @Test
    void keytoolIsQuotedAsTheShellReadsIt() {
        assertEquals("/usr/java/j2se/bin/keytool -list -alias cat-cullinan -keystore "
                + "/usr/java/j2se/lib/security/cacerts -storepass changeit",
                CommanderTrust.keytool("-list", "-alias", "cat-cullinan"));
        assertEquals("'it'\"'\"'s here'", CommanderTrust.shellQuote("it's here"));
        assertEquals("''", CommanderTrust.shellQuote(""));
    }

    @Test
    void theDerToPemFormIsPythonsAndRoundTrips() {
        String pem = CommanderTrust.derToPem(new byte[] {1, 2, 3});
        assertEquals("-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----\n", pem);
    }

    @Test
    void anEndpointThatIsNotHostAndPortIsRefusedBeforeAnythingIsReached() {
        Say.Recorded say = new Say.Recorded();
        CommanderAccess access = CommanderFixtures.access(CommanderFixtures.rig(dir), new Shell(), new HashMap<>());
        assertFalse(CommanderTrust.trustCli(access, "no-port", "cat-x", true, say));
        assertEquals("commander-trust: 'no-port' is not host:port", say.lastErr());
        assertFalse(CommanderTrust.trustCli(access, "sim.invalid:https", "cat-x", true, say));
    }
}
