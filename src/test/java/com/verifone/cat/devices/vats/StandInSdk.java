package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A stand-in for Verifone's VATS SDK and KM4 relay, written into a temp dir, for running the shipped {@code agent.py}
 * as a real subprocess: the Python CAT's {@code tests/unit/vats_fake_sdk.py} (its four modules are the test resources
 * {@code /vats/fake_sdk/*.py}) and a stand-in {@code vats_connector.py} that only listens on its {@code --km4_port}.
 * {@code screen.json} is what the "terminal" shows; {@code calls.log} has every SDK call, one JSON line each.
 *
 * <p>It needs a Python 3.9 or newer: {@link #python()} finds one ({@code CAT_TEST_PYTHON}, else a {@code python.exe}
 * on the PATH outside the Windows Store stub, else the Python CAT's own venv) or the tests that need it are skipped.
 * Evidence about this repository only: what the real terminal does is not modelled.
 */
final class StandInSdk {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A relay that listens on {@code --km4_port} until killed, or exits with {@code --exit} first. */
    static final String RELAY = String.join("\n",
            "import argparse, socket, sys, time",
            "p = argparse.ArgumentParser()",
            "p.add_argument('--tcpip'); p.add_argument('--tcpport'); p.add_argument('--km4_port', type=int)",
            "p.add_argument('--ipc_comm'); p.add_argument('--workingdir'); p.add_argument('--autoreconnection',"
                    + " action='store_true')",
            "a = p.parse_args()",
            "if a.tcpip == 'exit.invalid': sys.exit(3)",
            "s = socket.socket(); s.bind(('127.0.0.1', a.km4_port)); s.listen(4)",
            "print('relay up on', a.km4_port, flush=True)",
            "while True:",
            "    c, _ = s.accept(); c.close()",
            "");

    final Path root;
    final Path vatsRunner;

    private StandInSdk(Path root) {
        this.root = root;
        this.vatsRunner = root.resolve("app").resolve("vats_runner");
    }

    /** Writes the stand-in SDK under {@code <dir>/terminalConnector-V4}. */
    static StandInSdk write(Path dir) throws IOException {
        StandInSdk sdk = new StandInSdk(dir.resolve(VatsSdk.CONNECTOR_DIR));
        Files.createDirectories(sdk.vatsRunner);
        for (String name : List.of("terminal.py", "user.py", "vats_html.py", "adkcom.py")) {
            try (InputStream in = StandInSdk.class.getResourceAsStream("/vats/fake_sdk/" + name)) {
                Files.write(sdk.vatsRunner.resolve(name), in.readAllBytes());
            }
        }
        Files.writeString(sdk.vatsRunner.resolve("vats_connector.py"), RELAY);
        Path python = sdk.root.resolve("python-3.9.11-embed-amd64").resolve("python.exe");
        Files.createDirectories(python.getParent());
        Files.write(python, new byte[0]); // the staged interpreter's place; the tests run python() instead
        sdk.show(List.of(), List.of(), -1);
        return sdk;
    }

    VatsSdk sdk(Path interpreter) {
        return new VatsSdk(root, interpreter);
    }

    /** What the "terminal" shows; {@code cardin} its reader (-1: does not say). */
    void show(List<String> lines, List<String> buttons, int cardin) throws IOException {
        Map<String, Object> screen = new LinkedHashMap<>();
        screen.put("lines", lines);
        screen.put("buttons", buttons);
        screen.put("pending_keys", "");
        screen.put("cardin", cardin);
        Files.writeString(vatsRunner.resolve("screen.json"), JSON.writeValueAsString(screen));
    }

    /** Every SDK call so far, as [name, [args]]. */
    List<List<Object>> calls() throws IOException {
        Path log = vatsRunner.resolve("calls.log");
        List<List<Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(log)) {
            return out;
        }
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            out.add(JSON.readValue(line, new TypeReference<List<Object>>() {}));
        }
        return out;
    }

    List<String> names() throws IOException {
        return calls().stream().map(c -> String.valueOf(c.get(0))).toList();
    }

    private static Path found;
    private static boolean looked;

    /** A Python 3.9+ on this machine, or null. */
    static synchronized Path python() {
        if (looked) {
            return found;
        }
        looked = true;
        List<Path> candidates = new ArrayList<>();
        String configured = System.getenv("CAT_TEST_PYTHON");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured.strip()));
        }
        for (String entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank() && !entry.contains("WindowsApps")) {
                candidates.add(Path.of(entry.strip(), "python.exe"));
            }
        }
        candidates.add(Path.of("C:\\gitrepos\\CAT\\.venv\\Scripts\\python.exe"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && atLeast39(candidate)) {
                found = candidate;
                return found;
            }
        }
        return null;
    }

    private static boolean atLeast39(Path python) {
        try {
            Process p = new ProcessBuilder(python.toString(), "-I", "-c",
                    "import sys; print(sys.version_info >= (3, 9))").redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && out.endsWith("True");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
