package com.verifone.cat.provision.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.SftpException;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What can be tested about {@link JschTransport} without a server: argument handling, the host-key policy,
 * key loading, the error mapping, and the bounded wait. No test here opens a socket.
 */
class JschTransportTest {

    @TempDir
    Path tmp;

    private static SshTarget target(String keyFilename) {
        return new SshTarget("commander", "commander.invalid", 22, "root", keyFilename);
    }

    @Test
    void hostKeyPolicyAcceptsUnknownKeysAndOffersOnlyPublickey() {
        var config = JschTransport.sessionConfig();
        assertEquals("no", config.getProperty("StrictHostKeyChecking"));
        assertEquals("publickey", config.getProperty("PreferredAuthentications"));
    }

    @Test
    void timeoutsConvertToMillisAndRefuseZeroAndNegative() {
        assertEquals(15_000, JschTransport.millis(Duration.ofSeconds(15)));
        // JSch reads a zero timeout as "wait forever", so zero is refused rather than passed through.
        assertThrows(IllegalArgumentException.class, () -> JschTransport.millis(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> JschTransport.millis(Duration.ofSeconds(-1)));
    }

    @Test
    void runRefusesAZeroTimeoutBeforeAnySocketIsOpened() {
        var transport = new JschTransport(target(null));
        assertThrows(IllegalArgumentException.class, () -> transport.run("true", Duration.ZERO));
        transport.close();
    }

    @Test
    void parentOfWalksRemotePathsLikePurePosixPath() {
        assertEquals("/a/b", JschTransport.parentOf("/a/b/c.jar"));
        assertEquals("/", JschTransport.parentOf("/x"));
        assertEquals("", JschTransport.parentOf("c"));
        assertEquals("/a", JschTransport.parentOf("/a/b/"));
        assertEquals("/", JschTransport.parentOf("/"));
    }

    @Test
    void theHostIsTheTargetsHost() {
        var transport = new JschTransport(target(null));
        assertEquals("commander.invalid", transport.host());
        transport.close();
    }

    @Test
    void aRefusedLoginIsADeviceErrorNotAnUnreachableBox() {
        var refused = JschTransport.connectFailure(target(null), new JSchException("Auth fail"));
        assertFalse(refused instanceof DeviceUnreachable, "a refused key is not a dead box");
        assertTrue(refused.getMessage().contains("refused"));
        assertTrue(refused.getMessage().contains("no-pw patch"));
        assertTrue(refused.getMessage().contains("commander.ssh_key_path_env is unset"));
    }

    @Test
    void aRefusedLoginNamesTheKeyFileItUsed() {
        var refused = JschTransport.connectFailure(target("k.pem"), new JSchException("Auth cancel"));
        assertTrue(refused.getMessage().contains("(k.pem)"), refused.getMessage());
    }

    @Test
    void aNetworkFailureOnConnectIsUnreachable() {
        var failure = JschTransport.connectFailure(target(null),
                new JSchException("java.net.ConnectException: Connection refused"));
        assertInstanceOf(DeviceUnreachable.class, failure);
    }

    @Test
    void onlyJschsOwnAuthWordingCountsAsARefusal() {
        assertTrue(JschTransport.isAuthRefusal(new JSchException("Auth fail")));
        assertFalse(JschTransport.isAuthRefusal(new JSchException("timeout: socket is not established")));
        assertFalse(JschTransport.isAuthRefusal(new IOException("Auth fail")));
    }

    @Test
    void aDroppedConnectionDuringAnOperationIsUnreachable() {
        var mapped = JschTransport.operationFailure("ssh commander", "run 'true'", new IOException("Connection reset"));
        assertInstanceOf(DeviceUnreachable.class, mapped);
    }

    @Test
    void aServerStatusDuringAnOperationIsADeviceErrorBecauseTheBoxAnswered() {
        var mapped = JschTransport.operationFailure("ssh commander", "put /x",
                new SftpException(3, "Permission denied"));
        assertFalse(mapped instanceof DeviceUnreachable);
        assertInstanceOf(DeviceError.class, mapped);
    }

    @Test
    void anOperationThatOutrunsItsTimeoutIsUnreachableAndDoesNotHang() {
        var transport = new JschTransport(target(null));
        var never = new CountDownLatch(1);
        long started = System.nanoTime();
        var timedOut = assertThrows(DeviceUnreachable.class, () -> transport.bounded(() -> {
            never.await();
            return null;
        }, Duration.ofMillis(200), "stuck op"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(timedOut.getMessage().contains("timed out"), timedOut.getMessage());
        assertTrue(elapsedMs < 10_000, "the wait must end at the bound, took " + elapsedMs + " ms");
        transport.close();
    }

    @Test
    void aFailureInsideABoundedOperationIsMappedNotWrapped() {
        var transport = new JschTransport(target(null));
        assertInstanceOf(DeviceUnreachable.class, assertThrows(DeviceError.class,
                () -> transport.bounded(() -> {
                    throw new IOException("Connection reset");
                }, Duration.ofSeconds(5), "run 'true'")));
        transport.close();
    }

    @Test
    void aGeneratedOpenSshKeyLoadsAsAnIdentity() throws Exception {
        var jsch = new JSch();
        KeyPair pair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048);
        Path key = tmp.resolve("test-key.pem");
        pair.writePrivateKey(key.toString());
        pair.dispose();
        assertDoesNotThrow(() -> JschTransport.loadIdentity(new JSch(), key.toString()));
    }

    @Test
    void aMissingKeyFileIsADeviceErrorNamingTheFile() {
        String missing = tmp.resolve("no-such-key.pem").toString();
        var error = assertThrows(DeviceError.class, () -> JschTransport.loadIdentity(new JSch(), missing));
        assertTrue(error.getMessage().contains("cannot be loaded"), error.getMessage());
    }

    @Test
    void aPuttyKeyIsRefusedWithTheConversionHint() throws IOException {
        Path ppk = tmp.resolve("automation.ppk");
        Files.writeString(ppk, "PuTTY-User-Key-File-2: ssh-rsa\nEncryption: none\n");
        var error = assertThrows(DeviceError.class, () -> JschTransport.loadIdentity(new JSch(), ppk.toString()));
        assertTrue(error.getMessage().contains("must be converted"), error.getMessage());
    }

    @Test
    void aMissingKeyFileFailsBeforeAnySocketIsOpened() {
        // The key is loaded before the session is built, so this never reaches commander.invalid.
        var transport = new JschTransport(target(tmp.resolve("absent.pem").toString()));
        assertThrows(DeviceError.class, () -> transport.connect(Duration.ofSeconds(1)));
        transport.close();
    }
}
