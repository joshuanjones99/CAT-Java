package com.verifone.cat.provision.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.jcraft.jsch.JSchException;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.provision.transport.SshLogin.Problem;

import org.junit.jupiter.api.Test;

/** The Connections step's login check, driven through the transport seam with the in-memory fake. */
class SshLoginTest {

    private static final SshTarget TARGET =
            new SshTarget("commander", "commander.invalid", 22, "root", "C:\\keys\\key.pem");

    @Test
    void aLoginThatWorksIsNoProblemAndTheTransportIsClosed() {
        var fake = new InMemoryTransport("commander.invalid");
        Optional<Problem> problem = SshLogin.check(TARGET, t -> fake);
        assertFalse(problem.isPresent());
        assertTrue(fake.ran().contains("true"), "the check runs `true`");
        assertTrue(fake.closed(), "the check always signs out");
    }

    @Test
    void aRefusedKeyIsAProblemThatEndsTheRun() {
        var fake = new InMemoryTransport("commander.invalid")
                .failRunsWith(JschTransport.connectFailure(TARGET, new JSchException("Auth fail")));
        Problem problem = SshLogin.check(TARGET, t -> fake).orElseThrow();
        assertTrue(problem.refused());
        assertTrue(problem.message().contains("commander (commander.invalid) refused CAT's SSH key"),
                problem.message());
        assertTrue(fake.closed());
    }

    @Test
    void aBoxThatCannotBeReachedIsReportedButDoesNotCountAsARefusal() {
        var fake = new InMemoryTransport("commander.invalid")
                .failRunsWith(JschTransport.connectFailure(TARGET, new JSchException("connect timed out")));
        Problem problem = SshLogin.check(TARGET, t -> fake).orElseThrow();
        assertFalse(problem.refused());
        assertTrue(problem.message().startsWith("SSH to commander (commander.invalid) failed:"), problem.message());
    }

    @Test
    void anUnmappedFailureIsReportedWithItsOwnMessage() {
        var fake = new InMemoryTransport("commander.invalid")
                .failRunsWith(new DeviceUnreachable("ssh commander", "boom"));
        Problem problem = SshLogin.check(TARGET, t -> fake).orElseThrow();
        assertFalse(problem.refused());
        assertTrue(fake.closed());
    }
}
