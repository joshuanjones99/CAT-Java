package com.verifone.cat.commander.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.verifone.cat.cloudtunnel.CloudTunnel;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.RigConfigException;

/**
 * {@code commander-connect}: validation and the order of what it sends. A port of the Python
 * {@code tests/unit/test_commander_connect.py}; the shim's own calls are {@code PdkShimTest.CloudConnect}.
 *
 * <p>Fake-backed: evidence about this repository's orchestration, not about a Commander. Nothing here opens a socket
 * or runs a process.
 */
class CommanderConnectTest {

    static final CommanderConnect GOOD = new CommanderConnect("CST", "a@b.com", "1234567", "hunter2");

    /** Every wait shortened; the settle at zero, so a failed state is believed at once. */
    static final ConnectTiming FAST = new ConnectTiming(Duration.ofSeconds(5), Duration.ZERO, Duration.ofMillis(50),
            Duration.ofMillis(1), Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(1), Duration.ZERO,
            Duration.ofSeconds(5), Duration.ofMillis(1), Duration.ofMillis(1));

    /** Answers {@code cat <path>} from {@link #files}; records every command. */
    static final class FakeTransport implements Transport {
        final Map<String, String> files = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        Function<String, CommandResult> override = command -> null;

        @Override
        public String host() {
            return "fake-commander";
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            commands.add(command);
            CommandResult special = override.apply(command);
            if (special != null) {
                return special;
            }
            if (command.startsWith("cat ")) {
                String text = files.get(command.substring(4));
                return new CommandResult(text == null ? 1 : 0, text == null ? "" : text, "");
            }
            return new CommandResult(0, "", "");
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            files.put(remotePath, new String(content, StandardCharsets.UTF_8));
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            String text = files.get(remotePath);
            return text == null ? Optional.empty() : Optional.of(text.getBytes(StandardCharsets.UTF_8));
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

    /** The Commander's CGILink side, shared by every client the service opens. */
    static final class Commander {
        final FakeTransport transport = new FakeTransport();
        final List<List<String>> calls = new ArrayList<>();
        boolean registered;
        /** Null: the profile is written as the handler writes it. */
        Function<String, RuntimeException> onProfile;
        boolean ignoreProfile;
        Function<Commander, ConnectClient.Status> status = c -> new ConnectClient.Status(c.registered, "x");
        String bridgeBefore = ConnectService.ONLINE;
        String bridgeAfter = ConnectService.ONLINE;
        String afterCoresvcs = ConnectService.ONLINE;

        Commander() {
            transport.files.put(ConnectService.PROFILE_FILE, "profile=prod\n");
        }

        long connects() {
            return calls.stream().filter(c -> c.get(0).equals("connect")).count();
        }

        ConnectClient client() {
            return new ConnectClient() {
                @Override
                public void setCloudConnectProfile(String profile) {
                    if (onProfile != null) {
                        throw onProfile.apply(profile);
                    }
                    if (ignoreProfile) {
                        return;
                    }
                    calls.add(List.of("profile", profile));
                    transport.files.put(ConnectService.PROFILE_FILE, "profile=" + profile.toLowerCase() + "\n");
                }

                @Override
                public void cloudConnect(String email, String password) {
                    calls.add(List.of("connect", email, password));
                    registered = true;
                }

                @Override
                public Status cloudConnectStatus() {
                    return status.apply(Commander.this);
                }

                @Override
                public String cloudConnectivity() {
                    List<String> commands = transport.commands;
                    int broker = -1;
                    for (int i = 0; i < commands.size(); i++) {
                        if (commands.get(i).contains("killMqttBroker -r")) {
                            broker = i;
                            break;
                        }
                    }
                    if (broker < 0) {
                        return bridgeBefore;
                    }
                    for (String command : commands.subList(broker, commands.size())) {
                        if (command.contains("killCS -r")) {
                            return afterCoresvcs;
                        }
                    }
                    return bridgeAfter;
                }

                @Override
                public void close() {}
            };
        }

        ConnectService service(List<String> said) {
            return new ConnectService(() -> transport, this::client, FAST, Duration.ofSeconds(5), said::add);
        }

        /** {@code commander_connect_cli}: the exit code, with the streams captured. */
        int cli(CommanderConnect settings, ByteArrayOutputStream err) {
            PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
            PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            return CommanderConnectCommand.cli(service(new ArrayList<>()), settings, out, errStream);
        }

        int cli(CommanderConnect settings) {
            return cli(settings, new ByteArrayOutputStream());
        }
    }

    private static boolean anyContains(List<String> commands, String text) {
        return commands.stream().anyMatch(c -> c.contains(text));
    }

    @Test
    void labsMatchTheConfigTabsDropdown() {
        assertEquals(List.of("Prod", "CST", "QAT", "DEV"), CommanderConnect.LABS);
    }

    @Test
    void badValuesAreNamed() {
        assertEquals(4, new CommanderConnect("X", "nope", "12", null).problems().size());
        assertEquals(List.of(), GOOD.problems());
    }

    @Test
    void passwordStaysOutOfToString() {
        assertFalse(GOOD.toString().contains("hunter2"));
    }

    @Test
    void aFreshCommanderGetsServiceIdLabRestartThenOnboarding() {
        Commander c = new Commander();
        assertEquals(0, c.cli(GOOD));
        assertEquals("1234567\n", c.transport.files.get(ConnectService.SERVICE_ID_FILE));
        assertEquals(List.of(List.of("profile", "CST"), List.of("connect", "a@b.com", "hunter2")), c.calls);
        assertTrue(anyContains(c.transport.commands, "killCS -r"));
        assertTrue(anyContains(c.transport.commands, "chown --reference=/home/rsd/config "));
    }

    @Test
    void aCommanderAlreadySetUpIsLeftAlone() {
        Commander c = new Commander();
        c.transport.files.put(ConnectService.SERVICE_ID_FILE, "1234567\n");
        c.transport.files.put(ConnectService.PROFILE_FILE, "profile=cst\n");
        c.registered = true;
        assertEquals(0, c.cli(GOOD));
        assertEquals(List.of(), c.calls);
        assertFalse(anyContains(c.transport.commands, "killCS"));
    }

    @Test
    void aLabTheCommanderRefusesFails() {
        Commander c = new Commander();
        c.ignoreProfile = true; // the Commander ignores QAT: not test-signed
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, c.cli(new CommanderConnect("QAT", "a@b.com", "1234567", "p"), err));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("test-signed"));
    }

    @Test
    void aRoleWithoutUcloudconnectprofileGetsTheFileWritten() {
        Commander c = new Commander();
        c.onProfile = profile -> new DeviceError("CGILink fault: CGIPortal.InternalError");
        assertEquals(0, c.cli(new CommanderConnect("QAT", "a@b.com", "1234567", "p")));
        assertEquals("profile=qat", c.transport.files.get(ConnectService.PROFILE_FILE));
        assertTrue(anyContains(c.transport.commands, "killCS -r"));
        assertEquals(List.of(List.of("connect", "a@b.com", "p")), c.calls);
    }

    @Test
    void aFailedSignInIsRetriedAfterTheTunnelHasHadTime() {
        Commander c = new Commander();
        c.transport.files.put(ConnectService.PROFILE_FILE, "profile=cst\n");
        c.status = x -> x.connects() < 2
                ? new ConnectClient.Status(false, "INITIATE_CLOUD_CONNECT: Communication error")
                : new ConnectClient.Status(true, "registered");
        List<String> said = new ArrayList<>();
        c.service(said).apply(new CommanderConnect("CST", "a@b.com", "1234567", "p"));
        assertEquals(2, c.connects());
        // The relay was checked between the sign-ins: nothing logged unreached, so the retry went at once.
        assertTrue(said.contains("the tunnel relays every host the sign-in needed"), said.toString());
    }

    @Test
    void aSignInThatKeepsFailingGivesUpAfterThreeAttempts() {
        Commander c = new Commander();
        c.transport.files.put(ConnectService.PROFILE_FILE, "profile=cst\n");
        c.status = x -> new ConnectClient.Status(false, "INITIATE_CLOUD_CONNECT: Communication error");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, c.cli(GOOD, err));
        assertEquals(ConnectService.ONBOARD_ATTEMPTS, c.connects());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("onboarding failed 3 times"));
    }

    @Test
    void anOnlineCommanderKeepsItsBroker() {
        Commander c = new Commander();
        assertEquals(0, c.cli(GOOD));
        assertFalse(anyContains(c.transport.commands, "killMqttBroker"));
    }

    @Test
    void registeredButOfflineRestartsTheBrokerOnce() {
        Commander c = new Commander();
        c.registered = true;
        c.bridgeBefore = "Offline";
        assertEquals(0, c.cli(GOOD));
        List<String> restarts = c.transport.commands.stream().filter(x -> x.contains("killMqttBroker -r")).toList();
        assertEquals(1, restarts.size());
        assertTrue(restarts.get(0).startsWith("su - coresvcs -c"));
    }

    @Test
    void offlineAfterBothRestartsFailsAndSaysSo() {
        Commander c = new Commander();
        c.bridgeBefore = "Offline";
        c.bridgeAfter = "Offline";
        c.afterCoresvcs = "Offline";
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, c.cli(GOOD, err));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cloud connectivity still Offline"));
    }

    @Test
    void offlineAfterTheBrokerRestartGetsACoresvcsRestart() {
        Commander c = new Commander();
        c.registered = true;
        c.bridgeBefore = "Offline";
        c.bridgeAfter = "Offline";
        assertEquals(0, c.cli(GOOD));
        List<String> commands = c.transport.commands;
        int broker = -1;
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).contains("killMqttBroker -r")) {
                broker = i;
                break;
            }
        }
        assertTrue(broker >= 0);
        assertTrue(anyContains(commands.subList(broker, commands.size()), "killCS -r"));
    }

    @Test
    void invalidValuesSendNothing() {
        Commander c = new Commander();
        assertEquals(1, c.cli(new CommanderConnect("CST", "a@b.com", "1", "")));
        assertEquals(List.of(), c.calls);
        assertEquals(List.of(), c.transport.commands);
    }

    @Test
    void aStatusFaultWhileTheTunnelReplansIsWaitedOut() {
        Commander c = new Commander();
        c.transport.files.put(ConnectService.PROFILE_FILE, "profile=cst\n");
        int[] polls = {0};
        c.status = x -> {
            if (x.connects() >= 2) {
                return new ConnectClient.Status(true, "registered");
            }
            if (x.calls.isEmpty()) {
                return new ConnectClient.Status(false, "INITIATE_CLOUD_CONNECT");
            }
            if (polls[0]++ == 0) {
                throw new DeviceError("CGILink fault: CGIPortal.InternalError");
            }
            return new ConnectClient.Status(false, "INITIATE_CLOUD_CONNECT: Communication error");
        };
        assertEquals(0, c.cli(new CommanderConnect("CST", "a@b.com", "1234567", "p")));
        assertEquals(2, c.connects());
    }

    @Test
    void aRetryWaitsUntilTheTunnelRelaysEveryUnreachedHost() {
        FakeTransport transport = new FakeTransport();
        String[] hosts = {""};
        transport.override = command -> {
            if (command.startsWith("grep -o")) {
                return new CommandResult(0, "Couldn't connect to https://uis.login.example.com\n", "");
            }
            if (command.startsWith("grep '")) {
                return new CommandResult(0, hosts[0], "");
            }
            return null;
        };
        assertEquals(java.util.Set.of("uis.login.example.com"),
                CloudTunnel.unrelayedHosts(transport, Duration.ofSeconds(5)));
        hosts[0] = "loopback-alias uis.login.example.com  # cloud-tunnel\n";
        assertEquals(java.util.Set.of(), CloudTunnel.unrelayedHosts(transport, Duration.ofSeconds(5)));
    }

    @Test
    void aTransportThatCannotOpenFallsBackToTheWholeRetryWait() {
        Commander c = new Commander();
        List<String> said = new ArrayList<>();
        ConnectService service = new ConnectService(() -> {
            throw new DeviceError("no route to the Commander");
        }, c::client, FAST, Duration.ofSeconds(5), said::add);
        service.waitForRelay();
        assertTrue(said.get(0).startsWith("could not check the relay (no route to the Commander)"), said.toString());
    }

    // --- the command line --------------------------------------------------------------------------------------

    /** Runs the command with {@code commander}'s fake wiring; {@code [exit, out, err]}. */
    private static String[] command(Commander commander, Map<String, String> env, String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CommanderConnectCommand command = new CommanderConnectCommand((rig, environ, say) -> {
            if (rig.equals("missing")) {
                throw new RigConfigException("no rig file for 'missing'");
            }
            return new ConnectService(() -> commander.transport, commander::client, FAST, Duration.ofSeconds(5), say);
        });
        int code = command.run(argv, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new String[] {String.valueOf(code), out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)};
    }

    private static final Map<String, String> PASSWORD = Map.of(CommanderConnect.PASSWORD_ENV, "hunter2");

    @Test
    void theCommandOnboardsWithThePasswordFromTheEnvironment() {
        Commander c = new Commander();
        String[] result = command(c, PASSWORD, "--rig", "lab", "--lab", "CST", "--email=a@b.com", "--service-id",
                "1234567");
        assertEquals("0", result[0], result[2]);
        assertTrue(result[1].contains("commander-connect: onboarded to CST as a@b.com"), result[1]);
        assertTrue(result[1].contains("commander-connect: service id set to 1234567"), result[1]);
        assertEquals(List.of("connect", "a@b.com", "hunter2"), c.calls.get(c.calls.size() - 1));
        assertFalse(result[1].contains("hunter2"));
    }

    @Test
    void noPasswordIsAValueProblemNotAUsageError() {
        Commander c = new Commander();
        String[] result = command(c, Map.of(), "--rig", "lab", "--lab", "CST", "--email", "a@b.com", "--service-id",
                "1234567");
        assertEquals("1", result[0]);
        assertTrue(result[2].contains("password is empty"));
        assertEquals(List.of(), c.transport.commands);
    }

    @Test
    void usageErrorsExitTwo() {
        Commander c = new Commander();
        assertEquals("2", command(c, PASSWORD, "--lab", "CST", "--email", "a@b.com", "--service-id", "1234567")[0]);
        assertEquals("2", command(c, PASSWORD, "--rig", "lab", "--bogus")[0]);
        assertEquals("2", command(c, PASSWORD, "--rig")[0]);
        assertEquals("2", command(c, PASSWORD, "--rig", "missing", "--lab", "CST", "--email", "a@b.com",
                "--service-id", "1234567")[0]);
        assertEquals(List.of(), c.transport.commands);
    }
}
