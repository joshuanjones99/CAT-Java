package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.provision.transport.InMemoryTransport;

/**
 * The {@code provision} and {@code render-payload} commands, driven through {@link ProvisionCommand#run}. Every host
 * is an in-memory fake, so no real box is reached. UNVERIFIED AGAINST HARDWARE (Java port).
 */
class ProvisionCommandTest {

    @TempDir
    Path dir;

    /**
     * The bench rig without {@code pos_shim: true}: the production checklist would otherwise probe the shim at a real
     * register address, and these tests reach no host.
     */
    private Path rigsDir() throws IOException {
        Path rigs = Files.createDirectories(dir.resolve("rigs"));
        Files.writeString(rigs.resolve("bench.yaml"), RigFixture.RIG.replace("pos_shim: true\n", ""));
        return rigs;
    }

    private Map<String, String> env(Path rigs, Path python) {
        Map<String, String> env = new HashMap<>();
        env.put("PETROTEST_RIGS_DIR", rigs.toString());
        env.put("CAT_PYTHON_ROOT", python.toString());
        return env;
    }

    private static final class Run {
        final int exit;
        final String out;
        final String err;

        Run(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    private static Run run(ProvisionCommand command, String[] argv, Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = command.run(argv, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noRigIsAUsageErrorWithTheUsage() throws IOException {
        Run run = run(new ProvisionCommand(), new String[] {}, env(rigsDir(), dir));

        assertEquals(ProvisionCommand.EXIT_USAGE, run.exit);
        assertTrue(run.err.contains("--rig is required") && run.err.contains("usage: cat-java provision"), run.err);
    }

    @Test
    void anUnknownFlagIsAUsageError() throws IOException {
        Run run = run(new ProvisionCommand(), new String[] {"--rig", "bench", "--bogus"}, env(rigsDir(), dir));

        assertEquals(ProvisionCommand.EXIT_USAGE, run.exit);
        assertTrue(run.err.contains("unknown argument: --bogus"), run.err);
    }

    @Test
    void aRigThatDoesNotLoadIsAUsageErrorNamingIt() throws IOException {
        Run run = run(new ProvisionCommand(), new String[] {"provision", "--rig", "nope"}, env(rigsDir(), dir));

        assertEquals(ProvisionCommand.EXIT_USAGE, run.exit);
        assertTrue(run.err.contains("cannot load rig nope"), run.err);
    }

    @Test
    void renderPayloadWithoutALoggingHostExitsFailedAndPrintsTheReport() throws IOException {
        Path python = dir.resolve("python");
        Path templates = python.resolve("src/petrotest/provision/templates");
        for (String name : new String[] {"filebeat/commander/filebeat.yml", "filebeat/pos/filebeat.yml"}) {
            Files.createDirectories(templates.resolve(name).getParent());
            Files.writeString(templates.resolve(name), "hosts: [\"${logging_host}:5142\"]\n");
        }
        Path payload = dir.resolve("payload");

        Run run = run(new ProvisionCommand(), new String[] {"render-payload", "--rig", "bench", "--payload-root",
                payload.toString()}, env(rigsDir(), python));

        assertEquals(Render.EXIT_FAILED, run.exit);
        assertTrue(run.out.contains("render-payload bench") && run.out.contains("UNRESOLVED"), run.out);
        assertTrue(Files.exists(payload.resolve("filebeat/commander/filebeat.yml")));
    }

    @Test
    void provisionPushesEveryTargetAndExitsOkWhenEveryTargetIsVerified() throws IOException {
        Path payload = dir.resolve("payload");
        for (var artifact : Artifacts.ARTIFACTS) {
            for (ArtifactFile file : artifact.files()) {
                Path path = payload.resolve(file.source());
                Files.createDirectories(path.getParent());
                Files.writeString(path, "content-of-" + file.source());
            }
        }
        Map<String, InMemoryTransport> boxes = new HashMap<>();
        ProvisionCommand command = new ProvisionCommand(
                spec -> boxes.computeIfAbsent(spec.name(), n -> new InMemoryTransport(spec.host())),
                spec -> spec.role() == TargetRole.REGISTER ? new AlwaysAnswers() : new AgentProbe.Null());

        Run run = run(command, new String[] {"provision", "--rig", "bench", "--no-stage", "--payload-root",
                payload.toString()}, env(rigsDir(), dir));

        assertEquals(ProvisionCommand.EXIT_OK, run.exit, run.out + run.err);
        assertTrue(run.out.contains("commander (commander)") && run.out.contains("3 file(s) pushed and verified"),
                run.out);
        assertTrue(run.out.contains("topaz101 (register)") && run.out.contains("15 file(s) pushed and verified"),
                run.out);
        assertTrue(run.out.contains(" - OK"), run.out);
        assertTrue(boxes.get("topaz101").exists("/home/topaz/config/.petrotest-provision.json", Duration.ofSeconds(1)));
    }

    @Test
    void aMissingPayloadFailsTheTargetByNameAndExitsFailed() throws IOException {
        Map<String, InMemoryTransport> boxes = new HashMap<>();
        ProvisionCommand command = new ProvisionCommand(
                spec -> boxes.computeIfAbsent(spec.name(), n -> new InMemoryTransport(spec.host())),
                spec -> new AgentProbe.Null());

        Run run = run(command, new String[] {"--rig", "bench", "--no-stage", "--payload-root",
                dir.resolve("empty").toString()}, env(rigsDir(), dir));

        assertEquals(ProvisionCommand.EXIT_FAILED, run.exit);
        assertTrue(run.out.contains("MissingPayloadException: payload file missing"), run.out);
        assertTrue(boxes.values().stream().allMatch(box -> box.writes().isEmpty()), "nothing pushed");
    }

    /** A register's shim and agent that always answer. */
    private static final class AlwaysAnswers implements AgentProbe {
        @Override
        public boolean shimAnswers(Duration timeout) {
            return true;
        }

        @Override
        public boolean rmiAgentAnswers(Duration timeout) {
            return true;
        }
    }

}
