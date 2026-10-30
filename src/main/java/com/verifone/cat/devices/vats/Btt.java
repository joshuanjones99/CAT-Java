package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.verifone.cat.commander.Pauser;

/**
 * The Brand Test Tool: find the newest install, configure it, start its card server. A port of the Python CAT's
 * {@code petrotest.devices.vats.btt}.
 *
 * <p>PATS starts none of this; an operator opens BTT and picks Actions -> Start Card Simulation Server. CAT does it with
 * no clicks, in this order ({@link #startCardServer}):
 * <ol>
 *   <li><b>Already serving.</b> Something answers on the card server port (9050): use it. One that only accepts is
 *       hung (run 0b0b0aa2dd00, 2026-10-12): its BTT is closed and started again.</li>
 *   <li><b>BTT's automation server</b> ({@link BttAutomationClient}), when it answers.</li>
 *   <li><b>The menu item, from inside BTT</b> ({@link BttMenuAgent}), loaded into BTT's JVM through
 *       {@code JAVA_TOOL_OPTIONS}, unless the automation server answers within its grace period.</li>
 * </ol>
 * Ready means the card server port accepts a connection. Before a launch CAT writes the settings the run needs into
 * BTT's own profile files under {@code Application/resources/db/} (the files its General Settings dialog writes). A BTT
 * that is already open is never restarted unless its card server hung: if its card server is not running, CAT says
 * what to press.
 *
 * <p>Deviations: the menu agent's cache, options and status files live under CAT-Java's own
 * {@code ~/.cat/java/btt-agent/} (the Python's under {@code <tools root>/cat-btt-agent/}), and a cache path with
 * whitespace, which Java 8's {@code JAVA_TOOL_OPTIONS} cannot carry, leaves the menu agent out (the automation server
 * alone, as the Python does when the agent cannot be built). BTT's processes are listed with {@code tasklist}'s
 * image-name filter, as the Python lists them; that names only {@code Brand Test Tool*} images, never Java, and a hung
 * card server's BTT is closed by those PIDs, as the Python closes it. An automation server that accepts and then fails
 * to answer is a refusal ({@code "no reply (...)"}), and a launch that cannot start is the route {@code launch}, where
 * the Python would raise out of the step.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's menu-agent route started BTT 5.9.0's card server on the lab
 * bench on 2026-10-06 (18.5 s); this class has run only against loopback stand-ins.
 */
public final class Btt {

    /** This PC's loopback address, where BTT's servers listen. */
    static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    /** Where Fime's installer puts each version, then UL's older layout: (parent folder, name glob). */
    public static final List<InstallGlob> INSTALL_GLOBS = List.of(
            new InstallGlob(Path.of("C:\\ProgramData\\Fime\\BTT"), "*"),
            new InstallGlob(Path.of("C:\\UL Test Tools"), "BTT *"));

    /**
     * The menu item the agent presses, either spelling: BTT 5.9.0's Actions menu reads "Start Card Simulator Server"
     * (read from the live menu bar, 2026-10-06); the AQA page, written against an older BTT, says "Simulation".
     */
    public static final String MENU_LABEL = "Start Card Simulator Server|Start Card Simulation Server";

    /** What an operator does when BTT shows "Invalid License" with the dongle plugged in. */
    public static final String LICENCE_HINT = "BTT did not see its licence: check the dongle is attached and listed in "
            + "Fime License Manager, then press Continue in BTT's dialog (or Close Application, so CAT can start it "
            + "again)";

    /** How long one STOP may take before an accepting card server counts as hung: a live one answered in 0.12 s. */
    public static final Duration CARD_SERVER_ANSWER_WAIT = Duration.ofSeconds(5);

    /** The whole of BTT's start: launch, settle, card server up (18.5 s on 2026-10-06). The Python's 120 s. */
    public static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(120);

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Duration POLL = Duration.ofMillis(500);

    /** A parent folder and the glob its install folders match. */
    public record InstallGlob(Path base, String glob) {

        @Override
        public String toString() {
            return base + java.io.File.separator + glob; // a glob is not a path: '*' is not a legal file name
        }
    }

    /** A BTT version, newest-first comparable. */
    public record Version(int major, int minor, int patch) implements Comparable<Version> {

        @Override
        public int compareTo(Version o) {
            return Comparator.comparingInt(Version::major).thenComparingInt(Version::minor)
                    .thenComparingInt(Version::patch).compare(this, o);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    /** One installed BTT. */
    public record Install(Version version, Path root, Path exe) {

        public String label() {
            return "BTT " + version;
        }

        public Path db() {
            return root.resolve("Application").resolve("resources").resolve("db");
        }

        public Path images() {
            return root.resolve("Application").resolve("Suites").resolve("Images");
        }

        public Path logs() {
            return root.resolve("logs");
        }
    }

    /** What {@link #startCardServer} did. {@code ok} means the card server port answers. */
    public record CardServerStart(boolean ok, String route, String detail, Install install) {}

    /** BTT's processes on this PC: listed, closed and launched. Tests replace it; nothing real is touched then. */
    public interface Processes {

        /** PIDs of running Brand Test Tool processes; none off Windows, where BTT cannot run. */
        List<Long> running();

        /** Closes each of {@code pids} with its children. */
        void kill(List<Long> pids);

        /** Starts {@code argv} in {@code cwd} with {@code env} added, detached: CAT never stops BTT at run end. */
        void launch(List<String> argv, Path cwd, Map<String, String> env) throws IOException;
    }

    /** This PC's processes: {@code tasklist} by BTT's image name, {@code taskkill} by PID, a detached launch. */
    public static final Processes SYSTEM = new Processes() {
        @Override
        public List<Long> running() {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                return List.of();
            }
            String out = run(List.of("tasklist", "/fo", "csv", "/nh", "/fi", "IMAGENAME eq Brand Test Tool*"));
            return parseTasklist(out);
        }

        @Override
        public void kill(List<Long> pids) {
            for (long pid : pids) {
                run(List.of("taskkill", "/PID", String.valueOf(pid), "/T", "/F"));
            }
        }

        @Override
        public void launch(List<String> argv, Path cwd, Map<String, String> env) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(env);
            builder.start().getOutputStream().close();
        }

        private String run(List<String> argv) {
            try {
                Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(30, TimeUnit.SECONDS);
                return out;
            } catch (IOException e) {
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
    };

    private final List<InstallGlob> globs;
    private final Path agentCache;
    private final Path toolsRoot;
    private final Processes processes;
    private final Pauser pauser;
    private final Duration answerWait;

    /**
     * @param globs      where installs are looked for ({@link #INSTALL_GLOBS} in production)
     * @param agentCache the menu agent's jar, options and status files ({@code ~/.cat/java/btt-agent})
     * @param toolsRoot  where a {@code Java17/jdk*} is, should this JVM have no compiler for the menu agent
     * @param processes  BTT's processes ({@link #SYSTEM} in production)
     * @param pauser     the polls' waits
     * @param answerWait how long one STOP may take ({@link #CARD_SERVER_ANSWER_WAIT})
     */
    public Btt(List<InstallGlob> globs, Path agentCache, Path toolsRoot, Processes processes, Pauser pauser,
            Duration answerWait) {
        this.globs = List.copyOf(globs);
        this.agentCache = agentCache;
        this.toolsRoot = toolsRoot;
        this.processes = processes;
        this.pauser = pauser;
        this.answerWait = answerWait;
    }

    /** The production BTT: the real install folders, this PC's processes, the agent cache under {@code home}. */
    public static Btt forThisMachine(Path home, Path toolsRoot) {
        return new Btt(INSTALL_GLOBS, agentCacheUnder(home), toolsRoot, SYSTEM, Pauser.REAL, CARD_SERVER_ANSWER_WAIT);
    }

    /** {@code <home>/.cat/java/btt-agent}: CAT-Java's own folder for the menu agent. */
    public static Path agentCacheUnder(Path home) {
        return home.resolve(".cat").resolve("java").resolve("btt-agent");
    }

    // -- installs ----------------------------------------------------------------------

    /** Every BTT with its launcher present, newest first. */
    public List<Install> installs() {
        return installs(globs);
    }

    /** {@link #installs()} over {@code globs}. */
    public static List<Install> installs(List<InstallGlob> globs) {
        Map<Path, Install> found = new LinkedHashMap<>();
        for (InstallGlob glob : globs) {
            if (!Files.isDirectory(glob.base())) {
                continue;
            }
            try (DirectoryStream<Path> roots = Files.newDirectoryStream(glob.base(), glob.glob())) {
                for (Path root : roots) {
                    if (!Files.isDirectory(root)) {
                        continue;
                    }
                    Matcher m = VERSION.matcher(root.getFileName().toString());
                    Path exe = firstSorted(root, "Brand Test Tool*.exe");
                    if (exe == null) {
                        exe = firstSorted(root, "BTT*.exe");
                    }
                    if (m.find() && exe != null) {
                        Version version = new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                                Integer.parseInt(m.group(3)));
                        found.put(root, new Install(version, root, exe));
                    }
                }
            } catch (IOException e) {
                // an unreadable folder has no install to offer
            }
        }
        List<Install> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(Install::version).reversed());
        return out;
    }

    /** The installs, only {@code version} when one is pinned ({@code vats.btt_version}). */
    public List<Install> installs(String version) {
        List<Install> all = installs();
        if (version == null || version.isEmpty()) {
            return all;
        }
        return all.stream().filter(i -> i.version().toString().equals(version)).toList();
    }

    private static Path firstSorted(Path dir, String glob) {
        List<Path> hits = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
            files.forEach(hits::add);
        } catch (IOException e) {
            return null;
        }
        return hits.stream().sorted().findFirst().orElse(null);
    }

    /** The globs, joined for a message, as the Python's {@code ', '.join(INSTALL_GLOBS)}. */
    public String describeGlobs() {
        return String.join(", ", globs.stream().map(InstallGlob::toString).toList());
    }

    // -- settings -------------------------------------------------------------------------

    private static final String PROFILE = """
            <profile>
              <active>true</active>
              <name>%s</name>
              <description>crf general profile</description>
              <type>crf-general-profile</type>
              <identifier>%s</identifier>
              <collection>
              </collection>
            </profile>
            """;

    /** Writes the run's card server port and interface, and the automation settings; returns what changed. */
    public static List<String> ensureSettings(Install install, int cardPort, String interfaceKind,
            int automationPort) {
        Map<String, String> general = new LinkedHashMap<>();
        general.put("server_tcpip_port", String.valueOf(cardPort));
        general.put("server_interface", capitalize(interfaceKind));
        Map<String, String> automation = new LinkedHashMap<>();
        automation.put("start_automation_at_startup", "true");
        automation.put("server_control_port", String.valueOf(automationPort));
        List<String> changed = new ArrayList<>(setEntries(install.db().resolve("General Settings.profile.xml"),
                "General Settings", general));
        changed.addAll(setEntries(install.db().resolve("BTT Automation.profile.xml"), "BTT Automation", automation));
        return changed;
    }

    /** Python's {@code str.capitalize}: the first letter upper, the rest lower. */
    static String capitalize(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Sets {@code values} in a BTT profile file, creating it if absent. Returns what changed. */
    static List<String> setEntries(Path path, String name, Map<String, String> values) {
        boolean exists = Files.isRegularFile(path);
        Document doc;
        try {
            doc = exists ? Cards.parse(path)
                    : Cards.builder().parse(new InputSource(new java.io.StringReader(PROFILE.formatted(name, name))));
        } catch (IOException | SAXException e) {
            throw new IllegalStateException("cannot build a profile for " + name, e);
        }
        Element collection = null;
        for (Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals("collection")) {
                collection = e;
                break;
            }
        }
        if (collection == null) {
            throw new IllegalStateException(path + ": no <collection>");
        }
        Map<String, Element> current = new LinkedHashMap<>();
        for (Element entry : children(collection, "entry")) {
            List<Element> strings = children(entry, "string");
            if (strings.size() >= 2 && !strings.get(0).getTextContent().isEmpty()) {
                current.put(strings.get(0).getTextContent(), strings.get(1));
            }
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> value : values.entrySet()) {
            Element element = current.get(value.getKey());
            if (element == null) {
                Element entry = doc.createElement("entry");
                Element key = doc.createElement("string");
                key.setTextContent(value.getKey());
                Element text = doc.createElement("string");
                text.setTextContent(value.getValue());
                entry.appendChild(key);
                entry.appendChild(text);
                collection.appendChild(entry);
                changed.add(value.getKey() + "=" + value.getValue());
            } else if (!element.getTextContent().equals(value.getValue())) {
                changed.add(value.getKey() + ": " + element.getTextContent() + "->" + value.getValue());
                element.setTextContent(value.getValue());
            }
        }
        if (!changed.isEmpty() || !exists) {
            write(doc, path);
        }
        return changed;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e && e.getTagName().equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Writes {@code doc} indented by two spaces, with no XML declaration, as the Python's {@code ET.indent}. */
    private static void write(Document doc, Path path) {
        stripWhitespace(doc.getDocumentElement());
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            Files.createDirectories(path.getParent());
            Path staging = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(staging)) {
                transformer.transform(new DOMSource(doc), new StreamResult(out));
            }
            Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | TransformerException e) {
            throw new IllegalStateException("cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    private static void stripWhitespace(Node node) {
        NodeList nodes = node.getChildNodes();
        for (int i = nodes.getLength() - 1; i >= 0; i--) {
            Node child = nodes.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespace(child);
            }
        }
    }

    // -- start ------------------------------------------------------------------------------

    /** Whether the card server on loopback {@code port} replies to a STOP (harmless with no card loaded). */
    public boolean cardServerAnswers(int port) {
        return new CardServer(LOOPBACK, port, answerWait).ping();
    }

    /**
     * Brings BTT's card server up on {@code cardPort} by the first route that works.
     *
     * @param interfaceKind the rig's {@code vats.interface}
     * @param version       a pinned BTT ({@code vats.btt_version}), or null for the newest installed
     * @param timeout       the whole start's budget, {@link #DEFAULT_START_TIMEOUT} in a run
     * @param log           the progress lines an operator reads
     */
    public CardServerStart startCardServer(int cardPort, String interfaceKind, int automationPort, String version,
            Duration timeout, Consumer<String> log) {
        if (Ports.listening(cardPort)) {
            if (cardServerAnswers(cardPort)) {
                return new CardServerStart(true, "already running", "card server answers on " + cardPort, null);
            }
            List<Long> pids = processes.running();
            log.accept("BTT card server on " + cardPort + " accepts but does not answer; closing BTT " + pids);
            if (pids.isEmpty() || !close(pids) || !awaitClosed(cardPort, Duration.ofSeconds(30))) {
                return new CardServerStart(false, "hung", "BTT's card server on " + cardPort + " accepts but answers "
                        + "nothing, and CAT could not close BTT: close it (or press Stop in BTT's card simulation) and "
                        + "run again", null);
            }
        }

        if (Ports.listening(automationPort)) {
            BttAutomationClient.Reply reply = askAutomation(automationPort, interfaceKind);
            log.accept("BTT automation server: " + BttAutomationClient.START_CARD_SERVER + " -> " + reply.text());
            if (reply.ok() && await(cardPort, Duration.ofSeconds(30))) {
                return new CardServerStart(true, "automation server", "started via port " + automationPort, null);
            }
            return new CardServerStart(false, "automation server", "refused: " + reply.text(), null);
        }

        if (!processes.running().isEmpty()) {
            return new CardServerStart(false, "already open", "BTT is open but its card server is not running: in BTT "
                    + "pick Actions -> Start Card Simulation Server, or close BTT so CAT can start it", null);
        }

        List<Install> candidates = installs(version);
        if (candidates.isEmpty()) {
            String wanted = version != null && !version.isEmpty() ? "BTT " + version : "a Brand Test Tool install";
            return new CardServerStart(false, "not installed", wanted + " not found under " + describeGlobs(), null);
        }
        Install install = candidates.get(0);

        List<String> changed = ensureSettings(install, cardPort, interfaceKind, automationPort);
        if (!changed.isEmpty()) {
            log.accept(install.label() + " settings: " + String.join(", ", changed));
        }

        Map<String, String> env = new LinkedHashMap<>();
        try {
            Files.createDirectories(agentCache);
            clearStates();
            env.putAll(agentEnvironment(timeout, automationPort));
        } catch (IOException | RuntimeException e) {
            log.accept("BTT menu agent unavailable (" + e.getMessage() + "); relying on the automation server alone");
        }

        try {
            processes.launch(List.of(install.exe().toString(), "-automationPort=" + automationPort), install.root(),
                    env);
        } catch (IOException e) {
            return new CardServerStart(false, "launch", install.label() + ": could not start " + install.exe() + ": "
                    + e.getMessage(), install);
        }
        log.accept(install.label() + " launched from " + install.exe());

        AtomicBoolean sent = new AtomicBoolean();
        AtomicReference<CardServerStart> result = new AtomicReference<>();
        boolean settled = pauser.until(() -> {
            CardServerStart now = settled(install, cardPort, automationPort, interfaceKind, sent, log);
            result.set(now);
            return now != null;
        }, timeout, POLL);
        if (settled) {
            return result.get();
        }
        List<String> states = agentStates();
        String why = states.isEmpty() ? "no report from the menu agent" : String.join("; ", states);
        return new CardServerStart(false, "timed out", install.label() + ": no card server on " + cardPort + " after "
                + timeout.toSeconds() + "s (" + why + ")", install);
    }

    private CardServerStart settled(Install install, int cardPort, int automationPort, String interfaceKind,
            AtomicBoolean sent, Consumer<String> log) {
        if (Ports.listening(cardPort)) {
            String route = sent.get() ? "automation server" : "menu agent";
            return new CardServerStart(true, route, install.label() + " card server on " + cardPort, install);
        }
        if (!sent.get() && Ports.listening(automationPort)) {
            sent.set(true);
            BttAutomationClient.Reply reply = askAutomation(automationPort, interfaceKind);
            log.accept("BTT automation server: " + BttAutomationClient.START_CARD_SERVER + " -> " + reply.text());
        }
        for (String state : agentStates()) {
            if (state.startsWith("blocked")) {
                // BTT never builds its menu behind a licence dialog (Python HANDOFF.md section 1.133).
                return new CardServerStart(false, "licence", install.label() + ": " + state + " — " + LICENCE_HINT,
                        install);
            }
            if (state.startsWith("not-found") || state.startsWith("disabled") || state.startsWith("error")) {
                return new CardServerStart(false, "menu agent", install.label() + ": " + state, install);
            }
        }
        return null;
    }

    private static BttAutomationClient.Reply askAutomation(int port, String interfaceKind) {
        try {
            return new BttAutomationClient(port).startCardServer(interfaceKind);
        } catch (IOException | RuntimeException e) {
            return new BttAutomationClient.Reply(false, "no reply (" + e.getMessage() + ")");
        }
    }

    /**
     * Builds the menu agent and writes its options: {@code JAVA_TOOL_OPTIONS=-javaagent:<jar>=<options file>}. Java 8
     * splits that variable on whitespace with no quoting, so a path with a space cannot be carried.
     */
    Map<String, String> agentEnvironment(Duration timeout, int automationPort) throws IOException {
        if (agentCache.toString().chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("its folder " + agentCache + " has whitespace, which JAVA_TOOL_OPTIONS "
                    + "cannot carry");
        }
        Path jar = BttMenuAgent.build(agentCache, toolsRoot);
        Path options = agentCache.resolve("options.properties");
        String status = agentCache.resolve("status-{pid}.txt").toString().replace('\\', '/');
        Files.writeString(options, String.join("\n",
                "label=" + MENU_LABEL,
                "status=" + status,
                "automation_port=" + automationPort,
                "timeout_ms=" + timeout.toMillis()) + "\n", StandardCharsets.UTF_8);
        return Map.of("JAVA_TOOL_OPTIONS", "-javaagent:" + jar + "=" + options);
    }

    /** A previous launch's verdict is not this one's. */
    private void clearStates() throws IOException {
        try (DirectoryStream<Path> old = Files.newDirectoryStream(agentCache, "status-*.txt")) {
            for (Path p : old) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** What each menu agent reported, one line per BTT JVM. */
    public List<String> agentStates() {
        List<String> states = new ArrayList<>();
        if (!Files.isDirectory(agentCache)) {
            return states;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(agentCache, "status-*.txt")) {
            for (Path status : files) {
                if (!status.getFileName().toString().endsWith(".txt")) {
                    continue;
                }
                try {
                    states.add(Files.readString(status, StandardCharsets.UTF_8).strip());
                } catch (IOException e) {
                    // being rewritten: read again next pass
                }
            }
        } catch (IOException e) {
            return states;
        }
        return states;
    }

    /** {@code taskkill /T /F} each of {@code pids}; whether every BTT went within 15 s. */
    private boolean close(List<Long> pids) {
        processes.kill(pids);
        return pauser.until(() -> processes.running().isEmpty(), Duration.ofSeconds(15), POLL);
    }

    private boolean awaitClosed(int port, Duration timeout) {
        return pauser.until(() -> !Ports.listening(port), timeout, POLL);
    }

    private boolean await(int port, Duration timeout) {
        return pauser.until(() -> Ports.listening(port), timeout, POLL);
    }

    /** {@code tasklist /fo csv /nh} lines to their PIDs; the "INFO: No tasks" line has none. */
    static List<Long> parseTasklist(String out) {
        List<Long> pids = new ArrayList<>();
        for (String line : out.split("\\R")) {
            String[] cells = line.strip().split("\",\"");
            if (cells.length > 1) {
                String pid = cells[1].replace("\"", "");
                if (!pid.isEmpty() && pid.chars().allMatch(Character::isDigit)) {
                    pids.add(Long.parseLong(pid));
                }
            }
        }
        return pids;
    }
}
