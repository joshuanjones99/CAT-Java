package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

class VerificationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static Manifest registerManifest() {
        return Manifest.build(TargetRole.REGISTER, ManifestTest.contentOfSource(), Artifacts.ARTIFACTS);
    }

    /** Answers {@code sha256sum} for every path from the manifest, as a register's shell would. */
    private static InMemoryTransport honestTarget(Manifest manifest) {
        InMemoryTransport target = new InMemoryTransport("topaz101.invalid");
        for (Manifest.Entry entry : manifest.entries()) {
            target.command("sha256sum " + RemotePaths.shellQuote(entry.remotePath()),
                    new CommandResult(0, entry.sha256() + "  " + entry.remotePath() + "\n", ""));
        }
        return target;
    }

    @Test
    void aTargetThatHashesItsFilesPassesWithoutReadingAnyBack() {
        Manifest manifest = registerManifest();
        InMemoryTransport target = honestTarget(manifest);
        manifest.remotePaths().forEach(target::failReadsOf);

        Verification.Report report = Verification.verifyContent(manifest, target, TIMEOUT, null);

        assertTrue(report.ok(), report.problems().toString());
        assertEquals(15, report.checked());
    }

    @Test
    void aTargetHashMismatchIsCaughtAndNamesTheFile() {
        Manifest manifest = registerManifest();
        InMemoryTransport target = honestTarget(manifest);
        String path = manifest.entries().get(0).remotePath();
        target.command("sha256sum " + path, new CommandResult(0, "0".repeat(64) + "  " + path + "\n", ""));

        Verification.Report report = Verification.verifyContent(manifest, target, TIMEOUT, null);

        assertFalse(report.ok());
        assertTrue(report.problems().get(0).contains("digest mismatch after push"), report.problems().toString());
    }

    @Test
    void aSilentlyDroppedFileIsAbsentAfterPush() {
        Manifest manifest = Manifest.build(TargetRole.COMMANDER, ManifestTest.contentOfSource(), Artifacts.ARTIFACTS);
        InMemoryTransport target = new InMemoryTransport("commander.invalid")
                .silentlyDrop("/home/common/lib/jacocoagent.jar");
        target.put("/home/common/lib/jacocoagent.jar", new byte[0], 0644, TIMEOUT);

        Verification.Report report = Verification.verifyContent(manifest, target, TIMEOUT, null);

        assertTrue(report.problems().stream().anyMatch(p -> p.contains("absent after push")), report.problems().toString());
    }

    @Test
    void aFileWithNoDigestOnTargetIsReadBackAndHashedHere() {
        Manifest manifest = Manifest.build(TargetRole.COMMANDER, ManifestTest.contentOfSource(), Artifacts.ARTIFACTS);
        InMemoryTransport target = new InMemoryTransport("commander.invalid");
        for (Manifest.Entry entry : manifest.entries()) {
            target.put(entry.remotePath(), ManifestTest.contentOfSource().read(
                    new ArtifactFile(sourceOf(entry, manifest), entry.remotePath())), entry.mode(), TIMEOUT);
        }

        Verification.Report report = Verification.verifyContent(manifest, target, TIMEOUT, null);

        assertTrue(report.ok(), report.problems().toString());
        assertEquals(3, report.checked());
    }

    /** The payload source a manifest entry was built from: the table's source for that remote path. */
    private static String sourceOf(Manifest.Entry entry, Manifest manifest) {
        for (Artifact artifact : Artifacts.ARTIFACTS) {
            for (ArtifactFile file : artifact.filesFor(manifest.role())) {
                if (file.remotePath().equals(entry.remotePath())) {
                    return file.source();
                }
            }
        }
        throw new AssertionError("no source for " + entry.remotePath());
    }

    @Test
    void theShimIsAskedBeforeTheAgent() {
        List<String> asked = new ArrayList<>();
        AgentProbe probe = new AgentProbe() {
            @Override
            public boolean shimAnswers(Duration timeout) {
                asked.add("shim");
                return false;
            }

            @Override
            public boolean rmiAgentAnswers(Duration timeout) {
                asked.add("rmi");
                return true;
            }
        };
        Verification.Report report = new Verification.Report("register", "topaz101.invalid");

        Verification.verifyAgents(probe, TIMEOUT, report);

        assertEquals(List.of("shim"), asked);
        assertTrue(report.problems().get(0).contains("HTTP shim did not answer"));
    }

    @Test
    void aDeadRmiAgentBehindALiveShimIsReportedDistinctly() {
        AgentProbe probe = new AgentProbe() {
            @Override
            public boolean shimAnswers(Duration timeout) {
                return true;
            }

            @Override
            public boolean rmiAgentAnswers(Duration timeout) {
                return false;
            }
        };
        Verification.Report report = new Verification.Report("register", "topaz101.invalid");

        Verification.verifyAgents(probe, TIMEOUT, report);

        assertFalse(report.agentsOk());
        assertTrue(report.problems().get(0).contains("RMI agent did not answer"));
    }

    @Test
    void theCommanderProbeIsNotApplicableAndAnswersYes() {
        AgentProbe none = new AgentProbe.Null();
        assertFalse(none.applicable());
        assertTrue(none.shimAnswers(TIMEOUT) && none.rmiAgentAnswers(TIMEOUT));
    }
}
