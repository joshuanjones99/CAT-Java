package com.verifone.cat.commander;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Fakes for the Commander bring-up's tests: a rig whose names are RFC 2606 {@code .invalid}, a recording
 * CGILink client, the Commander's shell as the Python tests' {@code _CommanderSsh}, and a pauser that
 * records and never waits long. Evidence about this repository, not about a Commander.
 */
final class CommanderFixtures {

    static final String HOST = "commander.invalid";

    private CommanderFixtures() {}

    /** A rig with two registers, {@code reg1} and {@code reg2}, written under {@code dir} and loaded. */
    static Rig rig(Path dir) {
        return rig(dir, "");
    }

    /** {@link #rig(Path)} with extra top-level YAML, such as a {@code timeouts:} block. */
    static Rig rig(Path dir, String extra) {
        String yaml = String.join("\n",
                "name: bench",
                "commander:",
                "  host: " + HOST,
                "  ssh_user: root",
                "  eps_port: 41107",
                "registers:",
                "  - id: reg1",
                "    host: r1.invalid",
                "  - id: reg2",
                "    host: r2.invalid",
                "controller:",
                "  host: 127.0.0.1",
                "pinpad: viperpay",
                "pos_shim: true",
                "build:",
                "  expected_version: \"057.02\"",
                extra,
                "");
        try {
            Path file = dir.resolve("bench.yaml");
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
            return RigLoader.loadFile(file).rig();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Access whose Commander transport is {@code commander} and whose registers get in-memory ones. */
    static CommanderAccess access(Rig rig, Transport commander, Map<String, InMemoryTransport> registers) {
        return new CommanderAccess(rig, target -> target.name().equals("commander") ? commander
                : registers.computeIfAbsent(target.name(), name -> new InMemoryTransport(target.host())), Map.of());
    }

    static String resource(String name) {
        try (InputStream in = CommanderFixtures.class.getResourceAsStream("/commander/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records each pause into {@code calls}; parks only for a short one, so a poll still moves on. */
    static Pauser recording(List<Object> calls) {
        return duration -> {
            calls.add(duration);
            if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
                Pauser.REAL.pause(duration);
            }
        };
    }

    /** The commands a client was sent, without its imports, pauses and close. */
    static List<String> sent(List<Object> calls) {
        List<String> out = new ArrayList<>();
        for (Object call : calls) {
            if (call instanceof String s && !s.startsWith("import:") && !s.equals("closed")
                    && !s.equals("restart_fcc")) {
                out.add(s);
            }
        }
        return out;
    }

    /** The Python tests' {@code _Commander}: records imports, commands and the close; faults on demand. */
    static class FakeClient implements CommanderClient {

        final List<Object> calls;
        final String failOn;

        FakeClient(List<Object> calls) {
            this(calls, null);
        }

        FakeClient(List<Object> calls, String failOn) {
            this.calls = calls;
            this.failOn = failOn;
        }

        @Override
        public Map<String, String> importConfigurationFolder(Path folder) {
            calls.add("import:" + folder.getFileName());
            return Map.of();
        }

        @Override
        public void controlFunction(String command) {
            calls.add(command);
            if (command.equals(failOn)) {
                throw new DeviceError(command + ": CGIPortal.InternalError");
            }
        }

        @Override
        public String sendCommand(String command) {
            calls.add("send:" + command);
            return "";
        }

        @Override
        public void close() {
            calls.add("closed");
        }
    }

    /**
     * The Commander over SSH, as the Python's {@code _CommanderSsh} and {@code _FuelLinkSsh}: its syslog, an FCC
     * whose JVM gets a new PID once restarted, and 9732 sockets that stay stale until {@code healsAfter}
     * restarts.
     */
    static final class CommanderSsh implements Transport {

        static final String FUEL_LINK_HELD =
                "tcp6 0 0 commander.invalid:9732 controller.invalid:49800 ESTABLISHED 3021/java\n";
        static final String FUEL_LINK_QUEUED =
                "tcp6 9 0 commander.invalid:9732 controller.invalid:52004 ESTABLISHED -\n";

        volatile String syslog;
        final List<String> commands = new ArrayList<>();
        int restarts;
        int healsAfter;
        Runnable onRestart = () -> {};
        private final Map<String, Boolean> closed = new ConcurrentHashMap<>();

        CommanderSsh(String syslog) {
            this.syslog = syslog;
        }

        @Override
        public String host() {
            return HOST;
        }

        @Override
        public synchronized CommandResult run(String command, Duration timeout) {
            commands.add(command);
            if (command.equals(CommanderInit.FCC_RESTART)) {
                restarts++;
                onRestart.run();
            }
            if (command.equals(CommanderInit.FCC_PID)) {
                return new CommandResult(0, (restarts == 0 ? "100" : "20" + restarts) + "\n", "");
            }
            if (command.equals(CommanderInit.FUEL_LINK_COMMAND)) {
                return new CommandResult(0, FUEL_LINK_HELD + (restarts < healsAfter ? FUEL_LINK_QUEUED : ""), "");
            }
            if (command.equals(CommanderInit.SYSLOG_ERRORS_COMMAND)) {
                return new CommandResult(0, syslog, "");
            }
            return new CommandResult(0, "", "");
        }

        synchronized long count(String command) {
            return commands.stream().filter(command::equals).count();
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            return Optional.empty();
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {}

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            return false;
        }

        @Override
        public void close() {
            closed.put("closed", Boolean.TRUE);
        }
    }

    /** A {@link Function} of the register to its transport, recording each one built. */
    static Function<Rig.Register, Transport> registers(Map<String, InMemoryTransport> built) {
        return register -> built.computeIfAbsent(register.id(), id -> new InMemoryTransport(register.host()));
    }
}
