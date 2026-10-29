package com.verifone.cat.sims;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Petro Simulator Suite's tank level simulator (TLS): on only when the rig names its port. The Python CAT's
 * {@code cat.sims.petro_tls}.
 *
 * <p>The suite ships its TLS simulator off ({@code Simulators:} in {@code config/simulator_common_config.properties}
 * has no {@code TLS}) and pointed at {@code SerialPort=COM1} ({@code config/TLS_config.properties}), which on the lab
 * controller is the register's receipt-printer cable. Before each launch CAT writes the rig's
 * {@code controller.tls_port} into {@code SerialPort} and adds {@code TLS} to the list; with no port declared it takes
 * {@code TLS} out, so a copy left enabled never opens the printer's port.
 *
 * <p>Headless, Petro's TLS simulator also lacks RXTX's serial drivers and its tank list, which only its UI supplies.
 * With the simulator on, CAT launches Petro with the Python CAT's {@code RxtxAgent} as a java agent that supplies both
 * ({@link #agentJar}). The agent's source ships as a resource of this package
 * ({@code petro_agent/RxtxAgent.java}, the Python's file unchanged) and is compiled at first use with the simulators'
 * own {@code javac --release 8}, as the Python does: no Maven dependency, and the agent's reflective calls into Petro
 * and RXTX need nothing on CAT-Java's class path. Both are Petro defects, open on petroapps master on 2026-10-13.
 *
 * <p>Deviation: the agent jar is cached under {@code ~/.cat/java/cat-petro-agent/} (CAT-Java's own folder), not the
 * Python's {@code <tools_root>/cat-petro-agent}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's own record: on the lab rig, 2026-10-13, Petro on COM2
 * answered the Commander's setup commands and its poll; tank levels in Commander Connect not yet seen.
 */
public final class PetroTls {

    /** The name {@code Simulators:} uses for the tank level simulator. */
    public static final String TLS = "TLS";

    /** The agent's source, a resource of this package. */
    static final String AGENT_RESOURCE = "petro_agent/RxtxAgent.java";

    /** The agent's {@code Premain-Class}. */
    static final String AGENT_CLASS = "cat.petro.RxtxAgent";

    /** The bound on one {@code javac} run. */
    static final Duration JAVAC_TIMEOUT = Duration.ofSeconds(120);

    private PetroTls() {
    }

    private static Pattern line(String key) {
        return Pattern.compile("^([ \\t]*" + Pattern.quote(key) + "[ \\t]*[=:][ \\t]*)(.*?)([ \\t]*\\r?)$",
                Pattern.MULTILINE);
    }

    /** {@code key}'s value in Java {@code .properties} text ({@code =} or {@code :} separator), or null. */
    public static String getProperty(String text, String key) {
        Matcher match = line(key).matcher(text);
        return match.find() ? match.group(2) : null;
    }

    /** {@code text} with {@code key} set to {@code value}, keeping the line's own separator; appended if absent. */
    public static String setProperty(String text, String key, String value) {
        Matcher match = line(key).matcher(text);
        if (match.find()) {
            return text.substring(0, match.start()) + match.group(1) + value + match.group(3)
                    + text.substring(match.end());
        }
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        String sep = text.endsWith(newline) || text.isEmpty() ? "" : newline;
        return text + sep + key + "=" + value + newline;
    }

    /** The {@code Simulators:} list with TLS added ({@code on}) or removed; the others kept in order. */
    public static String simulatorsWith(String value, boolean on) {
        List<String> names = new ArrayList<>();
        for (String name : value.split(",")) {
            String stripped = name.strip();
            if (!stripped.isEmpty() && !stripped.toUpperCase(Locale.ROOT).equals(TLS)) {
                names.add(stripped);
            }
        }
        if (on) {
            names.add(TLS);
        }
        return String.join(", ", names);
    }

    /** Sets {@code key} in the file at {@code path}; true when the file changed. Java reads it as ISO-8859-1. */
    static boolean rewrite(Path path, String key, String value) throws IOException {
        String text = Files.exists(path) ? new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1) : "";
        String fresh = setProperty(text, key, value);
        if (fresh.equals(text)) {
            return false;
        }
        Files.write(path, fresh.getBytes(StandardCharsets.ISO_8859_1));
        return true;
    }

    /**
     * The serial ports Windows has right now (the registry's {@code SERIALCOMM} map), upper case, or null off Windows.
     * No key is no serial port at all.
     */
    public static Set<String> serialPorts() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return null;
        }
        Set<String> ports = new TreeSet<>();
        try {
            Map<String, Object> values = com.sun.jna.platform.win32.Advapi32Util.registryGetValues(
                    com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, "HARDWARE\\DEVICEMAP\\SERIALCOMM");
            for (Object value : values.values()) {
                ports.add(String.valueOf(value).toUpperCase(Locale.ROOT));
            }
        } catch (RuntimeException | LinkageError e) {
            // No key: no serial port at all.
        }
        return ports;
    }

    /** {@link #configure(Path, SimEntry.Tls, String, Set)} with this controller's serial ports. */
    public static String configure(Path cwd, SimEntry.Tls spec, String port) throws IOException {
        return configure(cwd, spec, port, null);
    }

    /**
     * Points Petro's TLS simulator at {@code port} and starts it, or turns it off when {@code port} is "". Returns one
     * line for the simulator's log, which says so when {@code port} is not among {@code present} (the controller's
     * serial ports; {@link #serialPorts()} when null): Petro then logs only "NoSuchPortException".
     */
    public static String configure(Path cwd, SimEntry.Tls spec, String port, Set<String> present) throws IOException {
        Path common = cwd.resolve(spec.simulators());
        String current = getProperty(new String(Files.readAllBytes(common), StandardCharsets.ISO_8859_1),
                "Simulators");
        String wanted = port == null ? "" : port;
        rewrite(common, "Simulators", simulatorsWith(current == null ? "" : current, !wanted.isEmpty()));
        if (wanted.isEmpty()) {
            return "tank level simulator off: the rig declares no controller.tls_port";
        }
        rewrite(cwd.resolve(spec.port()), "SerialPort", wanted);
        String line = "tank level simulator on, serial port " + wanted + " (controller.tls_port)";
        Set<String> have = present == null ? serialPorts() : present;
        if (have != null && !have.contains(wanted.toUpperCase(Locale.ROOT))) {
            String listed = have.isEmpty() ? "none" : String.join(", ", new TreeSet<>(have));
            line += "; " + wanted + " is not on this controller (it has " + listed + "): is the adapter plugged in?";
        }
        return line;
    }

    /** Where CAT-Java keeps the compiled agent: {@code ~/.cat/java/cat-petro-agent}. */
    public static Path defaultAgentCache() {
        return Path.of(System.getProperty("user.home"), ".cat", "java", "cat-petro-agent");
    }

    /** The agent's source, from this package's resources. */
    static byte[] agentSource() throws IOException {
        try (InputStream in = PetroTls.class.getResourceAsStream(AGENT_RESOURCE)) {
            if (in == null) {
                throw new IOException("the RXTX agent's source is not in CAT-Java's resources (" + AGENT_RESOURCE + ")");
            }
            return in.readAllBytes();
        }
    }

    /**
     * Compiles {@code RxtxAgent.java} into a java agent jar under {@code cache}, once per source revision, with
     * {@code javac} (the simulators' JDK's).
     *
     * @throws IOException when it cannot be built ("building the RXTX agent failed: ...")
     */
    public static Path agentJar(Path javac, Path cache) throws IOException {
        byte[] source = agentSource();
        Path jar = cache.resolve("cat-rxtx-agent-" + sha256(source).substring(0, 12) + ".jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        Files.createDirectories(cache);
        Path tmp = Files.createTempDirectory(cache, "build-");
        try {
            Path src = tmp.resolve("src");
            Path classes = tmp.resolve("classes");
            Files.createDirectories(src);
            Files.createDirectories(classes);
            Path file = src.resolve("RxtxAgent.java");
            Files.write(file, source);
            StringBuilder err = new StringBuilder();
            int code = run(List.of(javac.toString(), "--release", "8", "-d", classes.toString(), file.toString()),
                    err);
            if (code != 0) {
                throw new IOException("building the RXTX agent failed: " + err.toString().strip());
            }
            Path staging = cache.resolve(jar.getFileName() + ".tmp");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), AGENT_CLASS);
            try (OutputStream out = Files.newOutputStream(staging);
                    JarOutputStream zip = new JarOutputStream(out, manifest);
                    Stream<Path> walk = Files.walk(classes)) {
                for (Path cls : walk.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    zip.putNextEntry(new JarEntry(classes.relativize(cls).toString().replace('\\', '/')));
                    zip.write(Files.readAllBytes(cls));
                    zip.closeEntry();
                }
            }
            Files.move(staging, jar, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            com.verifone.cat.sims.base.Trees.deleteQuietly(tmp);
        }
        return jar;
    }

    private static int run(List<String> argv, StringBuilder err) throws IOException {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!process.waitFor(JAVAC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("building the RXTX agent failed: javac took over "
                        + JAVAC_TIMEOUT.toSeconds() + " s");
            }
            try {
                err.append(output.get(5, TimeUnit.SECONDS));
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // The exit code still says how it went.
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("building the RXTX agent was interrupted", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
