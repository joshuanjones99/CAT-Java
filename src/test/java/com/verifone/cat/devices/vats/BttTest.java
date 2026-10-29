package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.commander.Pauser;

/**
 * The Brand Test Tool's start, with loopback stand-ins for its servers, BTT installs under a temp dir and its processes
 * replaced: the Python's {@code test_vats_parts.py} BTT tests. Nothing here launches, lists or closes a real process,
 * except the menu-agent test, which loads the built agent into a throwaway JVM of its own. Evidence about this
 * repository only.
 */
class BttTest {

    @TempDir
    Path dir;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String GENERAL = """
            <profile>
              <active>true</active>
              <name>General Settings</name>
              <type>crf-general-profile</type>
              <identifier>General Settings</identifier>
              <collection>
                <entry>
                  <string>server_interface</string>
                  <string>Contactless</string>
                </entry>
                <entry>
                  <string>server_tcpip_port</string>
                  <string>9040</string>
                </entry>
                <entry>
                  <string>contact_card_device</string>
                  <string>slbox</string>
                </entry>
              </collection>
            </profile>
            """;

    /** Processes that fail the test if BTT is looked for, closed or launched, unless told otherwise. */
    private static class NoProcesses implements Btt.Processes {
        final List<String> launched = new ArrayList<>();

        @Override
        public List<Long> running() {
            fail("looked for BTT processes");
            return List.of();
        }

        @Override
        public void kill(List<Long> pids) {
            fail("closed BTT");
        }

        @Override
        public void launch(List<String> argv, Path cwd, Map<String, String> env) {
            launched.add(String.join(" ", argv));
        }
    }

    private Btt btt(Btt.Processes processes, Duration answerWait) {
        return new Btt(List.of(new Btt.InstallGlob(dir.resolve("Fime"), "*")), dir.resolve("agent"), null, processes,
                Pauser.REAL, answerWait);
    }

    private Path install(Path root, String version) throws IOException {
        Path home = root.resolve(version);
        Files.createDirectories(home.resolve("Application").resolve("resources").resolve("db"));
        Files.write(home.resolve("Brand Test Tool " + version + ".exe"), new byte[0]);
        return home;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Test
    void installsAreFoundNewestFirst() throws IOException {
        for (String version : List.of("5.8.0", "5.10.1", "5.9.0")) {
            install(dir.resolve("Fime"), version);
        }
        Files.createDirectories(dir.resolve("Fime").resolve("5.7.0")); // no launcher: not an install
        List<Btt.Install> found = Btt.installs(List.of(new Btt.InstallGlob(dir.resolve("Fime"), "*"),
                new Btt.InstallGlob(dir.resolve("UL"), "BTT *")));
        assertEquals(List.of("BTT 5.10.1", "BTT 5.9.0", "BTT 5.8.0"), found.stream().map(Btt.Install::label).toList());
    }

    @Test
    void aPinnedVersionIsTheOnlyOneOffered() throws IOException {
        install(dir.resolve("Fime"), "5.8.0");
        install(dir.resolve("Fime"), "5.9.0");
        Btt btt = btt(new NoProcesses(), Btt.CARD_SERVER_ANSWER_WAIT);
        assertEquals(List.of("BTT 5.8.0"), btt.installs("5.8.0").stream().map(Btt.Install::label).toList());
        assertTrue(btt.installs("0.0.0").isEmpty());
    }

    @Test
    void settingsAreRewrittenInPlaceAndLeftAloneWhenRight() throws IOException {
        Path home = install(dir, "5.9.0");
        Path general = home.resolve("Application").resolve("resources").resolve("db")
                .resolve("General Settings.profile.xml");
        Files.writeString(general, GENERAL);
        Btt.Install install = Btt.installs(List.of(new Btt.InstallGlob(dir, "*"))).get(0);
        List<String> changed = Btt.ensureSettings(install, 9050, "contact", 1983);
        assertTrue(changed.contains("server_tcpip_port: 9040->9050"), changed.toString());
        assertTrue(changed.contains("server_interface: Contactless->Contact"), changed.toString());
        String text = Files.readString(general);
        assertTrue(text.contains("<string>slbox</string>"), "untouched entries survive:\n" + text);
        assertFalse(text.startsWith("<?xml"), "no declaration, as ElementTree writes it");
        String automation = Files.readString(install.db().resolve("BTT Automation.profile.xml"));
        assertTrue(automation.contains("start_automation_at_startup") && automation.contains("1983"), automation);
        assertEquals(List.of(), Btt.ensureSettings(install, 9050, "contact", 1983));
    }

    /** BTT's automation protocol on a free port; the start request opens the card server port. */
    private static final class Automation implements AutoCloseable {
        final ServerSocket server;
        final int cardPort;
        final List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
        CardServerStub opened;

        Automation() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            cardPort = freePort();
            Thread t = new Thread(this::serve, "automation-stub");
            t.setDaemon(true);
            t.start();
        }

        private void serve() {
            while (true) {
                try (Socket conn = server.accept()) {
                    DataInputStream in = new DataInputStream(conn.getInputStream());
                    byte[] head = new byte[4];
                    try {
                        in.readFully(head);
                    } catch (IOException probe) {
                        continue; // CAT's port probe: connect, close
                    }
                    byte[] body = new byte[ByteBuffer.wrap(head).getInt()];
                    in.readFully(body);
                    Map<String, Object> request = JSON.readValue(body, new TypeReference<>() {});
                    received.add(request);
                    if (BttAutomationClient.START_CARD_SERVER.equals(request.get("message_type"))) {
                        opened = new CardServerStub(List.of(), false, cardPort);
                    }
                    byte[] reply = "{\"message_type\": \"ACK\", \"version\": \"1.0\"}".getBytes(StandardCharsets.UTF_8);
                    OutputStream out = conn.getOutputStream();
                    out.write(ByteBuffer.allocate(4).putInt(reply.length).array());
                    out.write(reply);
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            if (opened != null) {
                opened.close();
            }
        }
    }

    @Test
    void theAutomationServerIsAskedFirstWhenItAnswers() throws IOException {
        try (Automation server = new Automation()) {
            List<String> log = new ArrayList<>();
            Btt.CardServerStart started = btt(new NoProcesses(), Btt.CARD_SERVER_ANSWER_WAIT).startCardServer(
                    server.cardPort, "contact", server.server.getLocalPort(), null, Duration.ofSeconds(10), log::add);
            assertTrue(started.ok(), started.toString());
            assertEquals("automation server", started.route());
            assertEquals(List.of(Map.of("message_type", BttAutomationClient.START_CARD_SERVER, "version", "1.0",
                    "payload", Map.of("interface", "contact"))), server.received);
            assertTrue(log.get(0).startsWith("BTT automation server: Start_CardSimulatorServer -> ACK"), log.toString());
        }
    }

    @Test
    void aCardServerAlreadyListeningAndAnsweringIsUsedAsItIs() throws IOException {
        // Never the real BTT: a live server is adopted without looking for one to close.
        try (CardServerStub server = CardServerStub.answering()) {
            Btt.CardServerStart started = btt(new NoProcesses(), Btt.CARD_SERVER_ANSWER_WAIT)
                    .startCardServer(server.port(), "contact", freePort(), null, Duration.ofSeconds(5), line -> { });
            assertTrue(started.ok());
            assertEquals("already running", started.route());
        }
    }

    @Test
    void aHungCardServerIsNotAdoptedAndSaysSoWhenBttCannotBeClosed() throws IOException {
        // Run 0b0b0aa2dd00 (2026-10-12): 9050 open, no replies, a card insert timed out.
        List<List<Long>> killed = new ArrayList<>();
        Btt.Processes stuck = new NoProcesses() {
            @Override
            public List<Long> running() {
                return List.of(99999L);
            }

            @Override
            public void kill(List<Long> pids) {
                killed.add(pids);
            }
        };
        try (CardServerStub silent = CardServerStub.silent()) {
            Btt btt = new Btt(List.of(), dir.resolve("agent"), null, stuck, quick(), Duration.ofMillis(500));
            List<String> log = new ArrayList<>();
            Btt.CardServerStart started = btt.startCardServer(silent.port(), "contact", freePort(), null,
                    Duration.ofSeconds(5), log::add);
            assertFalse(started.ok());
            assertEquals("hung", started.route());
            assertTrue(started.detail().contains("accepts but answers nothing"), started.detail());
            assertEquals(List.of(List.of(99999L)), killed);
            assertTrue(log.get(0).contains("closing BTT [99999]"), log.toString());
        }
    }

    @Test
    void anOpenBttWithoutItsCardServerSaysWhatToPress() throws IOException {
        Btt.Processes open = new NoProcesses() {
            @Override
            public List<Long> running() {
                return List.of(4242L);
            }
        };
        Btt.CardServerStart started = new Btt(List.of(), dir.resolve("agent"), null, open, quick(),
                Btt.CARD_SERVER_ANSWER_WAIT).startCardServer(freePort(), "contact", freePort(), null,
                        Duration.ofSeconds(5), line -> { });
        assertEquals("already open", started.route());
        assertTrue(started.detail().contains("Actions -> Start Card Simulation Server"), started.detail());
    }

    @Test
    void noInstallOfThePinnedVersionIsNamedAndNothingIsLaunched() throws IOException {
        NoProcesses none = new NoProcesses() {
            @Override
            public List<Long> running() {
                return List.of();
            }
        };
        Btt.CardServerStart started = btt(none, Btt.CARD_SERVER_ANSWER_WAIT).startCardServer(freePort(), "contact",
                freePort(), "0.0.0", Duration.ofSeconds(5), line -> { });
        assertFalse(started.ok());
        assertEquals("not installed", started.route());
        assertTrue(started.detail().startsWith("BTT 0.0.0 not found under "), started.detail());
        assertTrue(none.launched.isEmpty());
    }

    @Test
    void aLaunchWritesTheSettingsLoadsTheMenuAgentAndReadsItsLicenceReport() throws IOException {
        Path home = install(dir.resolve("Fime"), "5.9.0");
        List<Map<String, String>> envs = new ArrayList<>();
        Path agent = dir.resolve("agent");
        Btt.Processes launching = new Btt.Processes() {
            @Override
            public List<Long> running() {
                return List.of();
            }

            @Override
            public void kill(List<Long> pids) {
                fail("closed BTT");
            }

            @Override
            public void launch(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
                assertEquals(home, cwd);
                assertTrue(argv.get(1).startsWith("-automationPort="), argv.toString());
                envs.add(env);
                // the agent in BTT's JVM, behind a licence dialog
                Files.writeString(agent.resolve("status-1234.txt"), "blocked: 'Invalid License': No valid license");
            }
        };
        Btt btt = new Btt(List.of(new Btt.InstallGlob(dir.resolve("Fime"), "*")), agent, null, launching, quick(),
                Btt.CARD_SERVER_ANSWER_WAIT);
        List<String> log = new ArrayList<>();
        Btt.CardServerStart started = btt.startCardServer(freePort(), "contact", freePort(), null,
                Duration.ofSeconds(10), log::add);
        assertEquals("licence", started.route(), started.toString());
        assertTrue(started.detail().contains(Btt.LICENCE_HINT), started.detail());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("BTT 5.9.0 settings: server_tcpip_port=")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("BTT 5.9.0 launched from ")), log.toString());
        String options = envs.get(0).get("JAVA_TOOL_OPTIONS");
        assertTrue(options.startsWith("-javaagent:" + agent.resolve("cat-btt-menu-agent-")), options);
        String properties = Files.readString(agent.resolve("options.properties"));
        assertTrue(properties.contains("label=" + Btt.MENU_LABEL), properties);
        assertTrue(properties.contains("status=" + agent.toString().replace('\\', '/') + "/status-{pid}.txt"),
                properties);
    }

    @Test
    void theMenuAgentBuildsForJava8AndIsCachedPerRevision() throws IOException {
        Path jar = BttMenuAgent.build(dir, null);
        try (JarFile file = new JarFile(jar.toFile())) {
            assertEquals(BttMenuAgent.AGENT_CLASS, file.getManifest().getMainAttributes().getValue("Premain-Class"));
            byte[] cls = file.getInputStream(file.getEntry("cat/btt/MenuAgent.class")).readAllBytes();
            assertEquals(52, ((cls[6] & 0xFF) << 8) | (cls[7] & 0xFF), "Java 8: BTT runs on Fime's Java 8 runtime");
        }
        assertEquals(jar, BttMenuAgent.build(dir, null));
    }

    /** A stand-in for BTT 5.9.0 with no licence on the dongle (TPA-AUTOLAB2, 2026-10-10). */
    private static final String UNLICENSED_BTT = """
            import javax.swing.*;
            public class Unlicensed {
                public static void main(String[] a) throws Exception {
                    SwingUtilities.invokeAndWait(() -> {
                        JFrame f = new JFrame("Loading...");
                        f.setSize(200, 80);
                        f.setVisible(true);
                        JDialog d = new JDialog(f, "Invalid License", false);
                        d.add(new JLabel("<html>No valid license has been found for this product.</html>"));
                        d.pack();
                        d.setVisible(true);
                    });
                    Thread.sleep(20000);
                    System.exit(0);
                }
            }
            """;

    @Test
    void theMenuAgentReportsALicenceDialogAndStopsWaiting() throws Exception {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "needs a desktop to show a Swing dialog");
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(javac != null, "needs a JDK");
        // The child JVM holds the jar it loads until well after it exits on Windows: it gets a copy, deleted below.
        Path jar = Files.copy(BttMenuAgent.build(dir.resolve("cache"), null), dir.resolve("child-agent.jar"));
        Path source = dir.resolve("Unlicensed.java");
        Files.writeString(source, UNLICENSED_BTT);
        assertEquals(0, javac.run(null, null, null, "-d", dir.toString(), source.toString()));
        Path options = dir.resolve("options.properties");
        Files.writeString(options, "label=" + Btt.MENU_LABEL + "\nstatus="
                + dir.resolve("status-{pid}.txt").toString().replace('\\', '/') + "\ntimeout_ms=15000\n");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-javaagent:" + jar + "=" + options, "-cp",
                dir.toString(), "Unlicensed").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        Btt reader = new Btt(List.of(), dir, null, new NoProcesses(), Pauser.REAL, Btt.CARD_SERVER_ANSWER_WAIT);
        try {
            boolean blocked = Pauser.REAL.until(() -> reader.agentStates().stream()
                    .anyMatch(s -> s.startsWith("blocked")), Duration.ofSeconds(15), Duration.ofMillis(200));
            assertTrue(blocked, reader.agentStates().toString());
        } finally {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            Pauser.REAL.until(() -> {
                try {
                    Files.deleteIfExists(jar);
                    return true;
                } catch (IOException stillHeld) {
                    return false;
                }
            }, Duration.ofSeconds(15), Duration.ofMillis(250));
        }
        assertEquals(List.of("blocked: 'Invalid License': No valid license has been found for this product."),
                reader.agentStates().stream().filter(s -> s.startsWith("blocked")).toList());
    }

    @Test
    void tasklistLinesAreReadForTheirPids() {
        String out = "\"Brand Test Tool 5.9.0.exe\",\"4242\",\"Console\",\"1\",\"300,000 K\"\r\n"
                + "\"Brand Test Tool 5.9.0.exe\",\"77\",\"Console\",\"1\",\"3,000 K\"\r\n";
        assertEquals(List.of(4242L, 77L), Btt.parseTasklist(out));
        assertEquals(List.of(), Btt.parseTasklist("INFO: No tasks are running which match the specified criteria."));
    }

    @Test
    void theCapitalisationIsPythons() {
        assertEquals("Contact", Btt.capitalize("contact"));
        assertEquals("Contactless", Btt.capitalize("CONTACTLESS"));
    }

    /** A pauser whose polls look three times and never wait: a test's budget is never a real wait. */
    private static Pauser quick() {
        return new Pauser() {
            @Override
            public void pause(Duration duration) {
            }

            @Override
            public boolean until(java.util.function.BooleanSupplier check, Duration timeout, Duration interval) {
                for (int i = 0; i < 3; i++) {
                    if (check.getAsBoolean()) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
