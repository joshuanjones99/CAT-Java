package com.verifone.cat.commander.emv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Fakes for {@code emv-keys}: a rig whose names never resolve, the Commander's SSH as the Python tests'
 * {@code _Commander}, the register as their {@code _Register} (each press makes the Buypass host append its
 * answer to a real packet file), and a real {@code sh} for the shell text. Evidence about this repository, not
 * about a Commander or a register.
 */
final class EmvFakes {

    static final String MARKER_CHECK = "test -e " + CommanderTables.TABLES_MARKER;

    private EmvFakes() {}

    /** Writes the rig {@code bench} (with a cashier whose password is {@code CAT_TEST_PIN}) into {@code rigs}. */
    static Path rigs(Path dir) {
        String yaml = String.join("\n",
                "name: bench",
                "commander:",
                "  host: commander.invalid",
                "  ssh_user: root",
                "  eps_port: 41107",
                "registers:",
                "  - id: topaz101",
                "    host: register.invalid",
                "controller:",
                "  host: 127.0.0.1",
                "pinpad: viperpay",
                "pos_shim: true",
                "cashier:",
                "  id: \"1\"",
                "  password_env: CAT_TEST_PIN",
                "build:",
                "  expected_version: \"057.02\"",
                "");
        try {
            Path rigs = Files.createDirectories(dir.resolve("rigs"));
            Files.writeString(rigs.resolve("bench.yaml"), yaml, StandardCharsets.UTF_8);
            return rigs;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Rig rig(Path dir) {
        return RigLoader.loadFile(rigs(dir).resolve("bench.yaml")).rig();
    }

    /** The host's {@code AdminResponse} to one download, as BUYPASS Host logs it. */
    static String answer(String fileType, String code) {
        return "<GMF><AdminResponse><CommonGrp><TxnType>FileDownload</TxnType></CommonGrp>"
                + "<FileDLGrp><FileType>" + fileType + "</FileType></FileDLGrp>"
                + "<RespGrp><RespCode>" + code + "</RespCode></RespGrp></AdminResponse></GMF>\n";
    }

    /**
     * The Commander's SSH: an {@link InMemoryTransport} for the marker, one answer to the cashback edit whatever
     * its shell text, and, when {@code counts} is given, scripted {@code grep -c} answers and a restore.
     */
    static final class Commander implements Transport {

        final InMemoryTransport files = new InMemoryTransport("commander.invalid");
        final List<String> ran = new ArrayList<>();
        String cashback = "raised";
        Iterator<String> counts;
        boolean closed;

        Commander(boolean marker) {
            if (!marker) {
                files.command(MARKER_CHECK, new CommandResult(1, "", ""));
            }
        }

        @Override
        public String host() {
            return files.host();
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            if (command.contains(CommanderTables.CASHBACK_TABLE + " ")) {
                ran.add("cashback");
                return new CommandResult(0, cashback + "\n", "");
            }
            if (counts != null && command.startsWith("grep -c")) {
                ran.add("count");
                return new CommandResult(0, counts.next(), "");
            }
            if (counts != null && command.startsWith("set -e")) {
                ran.add("restore");
                return new CommandResult(0, "moved TBL0001.dat\nrestored CardTable.xml\n", "");
            }
            ran.add(command);
            return files.run(command, timeout);
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            files.put(remotePath, content, mode, timeout);
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            return files.get(remotePath, timeout);
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            files.mkdir(remotePath, timeout);
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            return files.exists(remotePath, timeout);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** The register: records each call into {@code pressed}; each download press makes the host answer. */
    static final class Register implements EmvKeys.Register {

        final Path packets;
        final List<String> pressed;
        String tableCode = "000";
        boolean atLogin;
        boolean hostAnswers = true;
        final List<String> signOns = new ArrayList<>();

        Register(Path packets, List<String> pressed) {
            this.packets = packets;
            this.pressed = pressed;
        }

        private void hostSends(String fileType, String code) {
            if (!hostAnswers) {
                return;
            }
            try {
                Files.writeString(packets, answer(fileType, code), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean atLogin() {
            return atLogin;
        }

        @Override
        public void signOnFromLogin(String cashierId, String password, String till, Duration timeout) {
            pressed.add("sign on");
            signOns.add(cashierId + "/" + password + "/" + till);
            atLogin = false;
        }

        @Override
        public void downloadTables() {
            pressed.add("tables");
            hostSends("TABLE", tableCode);
        }

        @Override
        public void downloadEmvCaPublicKeys() {
            pressed.add("emv keys");
            hostSends("EMV2KEY", "000");
        }

        @Override
        public void closeToMainMenu(Duration timeout) {
            pressed.add("close menus");
        }

        @Override
        public void signOff() {
            pressed.add("sign off");
        }

        @Override
        public void close() {
            pressed.add("closed");
        }
    }

    /** Runs each command as a script file with a real {@code sh}, as the Python tests' {@code _Shell}. */
    static class Shell implements Transport {

        final String sh;
        final Path scratch;

        Shell(String sh, Path scratch) {
            this.sh = sh;
            this.scratch = scratch;
        }

        /** A POSIX {@code sh} on this machine, or null. */
        static String find() {
            String path = System.getenv("PATH");
            List<Path> candidates = new ArrayList<>();
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    if (!dir.isBlank()) {
                        candidates.add(Path.of(dir.strip(), "sh.exe"));
                        candidates.add(Path.of(dir.strip(), "sh"));
                    }
                }
            }
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) {
                candidates.add(Path.of(programFiles, "Git", "usr", "bin", "sh.exe"));
                candidates.add(Path.of(programFiles, "Git", "bin", "sh.exe"));
            }
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
            return null;
        }

        @Override
        public String host() {
            return "sh";
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            try {
                Path script = Files.createTempFile(scratch, "cmd", ".sh");
                Files.writeString(script, command + "\n", StandardCharsets.UTF_8);
                Path out = Files.createTempFile(scratch, "out", ".txt");
                Path err = Files.createTempFile(scratch, "err", ".txt");
                ProcessBuilder builder = new ProcessBuilder(sh, script.toString().replace('\\', '/'))
                        .redirectOutput(out.toFile()).redirectError(err.toFile());
                // Git for Windows' bin\sh.exe finds no coreutils (mkdir, grep, sed) on a plain Windows PATH:
                // they live beside usr\bin\sh.exe. Put both ahead of the inherited PATH.
                Path home = Path.of(sh).toAbsolutePath().getParent();
                String extra = home + java.io.File.pathSeparator + home.resolveSibling("usr").resolve("bin");
                Map<String, String> environment = builder.environment();
                String pathKey = environment.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst()
                        .orElse("PATH");
                String inherited = environment.get(pathKey);
                environment.put(pathKey, inherited == null ? extra : extra + java.io.File.pathSeparator + inherited);
                Process process = builder.start();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("sh timed out: " + command);
                }
                return new CommandResult(process.exitValue(), Files.readString(out), Files.readString(err));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    /** Environment for a command test: the rigs folder, and the cashier's password when given. */
    static Map<String, String> env(Path rigs, String pin) {
        return pin == null ? Map.of("PETROTEST_RIGS_DIR", rigs.toString())
                : Map.of("PETROTEST_RIGS_DIR", rigs.toString(), "CAT_TEST_PIN", pin);
    }
}
