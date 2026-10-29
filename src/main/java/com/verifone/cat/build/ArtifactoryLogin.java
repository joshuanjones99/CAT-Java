package com.verifone.cat.build;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code cat-java artifactory-login}: ask once for the Artifactory username and password (or API key), and save them
 * DPAPI-encrypted to this Windows account. The Python's {@code petrotest artifactory-login}
 * ({@code petrotest.build.credentials.login} and {@code save_credentials}).
 *
 * <p>The file is Monarch's format, as the Python writes it: {@code {"Username": <user>, "Password": <hex>}}, where the
 * hex is a DPAPI blob of the UTF-16LE password (PowerShell's keyless {@code ConvertFrom-SecureString}), so the Python
 * CAT and Monarch read it too, and it decrypts only under the account that saved it. The user is saved as
 * {@link StoredArtifactoryCredentials#normaliseUsername} has it: an email loses its domain. The password is never
 * echoed, printed or written in the clear.
 *
 * <p>Deviation: the file is {@code ~/.cat/java/artifactory.credentials} ({@link StoredArtifactoryCredentials#catJavaFile}),
 * not the Python checkout's {@code .secrets/artifactory.credentials}: CAT-Java writes only under {@code ~/.cat/java/}.
 * {@link StoredArtifactoryCredentials} reads it first. With no console (input piped), the password is read as a line of
 * standard input, with a warning that it may be echoed, as the Python's {@code getpass} falls back.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the DPAPI encrypt has not been read back by the Python CAT or Monarch;
 * the tests pass a fake protector.
 */
public final class ArtifactoryLogin {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;

    /** Encrypts plaintext bytes to this Windows account. */
    @FunctionalInterface
    public interface Protector {
        byte[] protect(byte[] plain);
    }

    /** Windows' DPAPI, {@code CryptProtectData} under the current account (JNA's {@code Crypt32Util}). */
    public static final Protector DPAPI = plain -> com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain);

    /** Where the two answers come from. */
    public interface Prompt {
        /** One line, echoed; null at end of input. */
        String line(String prompt);

        /** One line, not echoed where a console allows; null at end of input. */
        String secret(String prompt);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Prompt prompt;
    private final Protector protector;
    private final Path file;

    /** The real thing: the console, DPAPI, and {@code ~/.cat/java/artifactory.credentials}. */
    public ArtifactoryLogin() {
        this(consolePrompt(), DPAPI, StoredArtifactoryCredentials.catJavaFile(System.getProperty("user.home")));
    }

    public ArtifactoryLogin(Prompt prompt, Protector protector, Path file) {
        this.prompt = prompt;
        this.protector = protector;
        this.file = file;
    }

    public int run(String[] argv) {
        return run(argv, System.out, System.err);
    }

    public int run(String[] argv, PrintStream out, PrintStream err) {
        for (String arg : argv) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                out.println("usage: cat-java artifactory-login");
                out.println();
                out.println("Save Artifactory credentials, DPAPI-encrypted to this Windows account, in " + file + ".");
                return EXIT_OK;
            }
            if (!"artifactory-login".equals(arg)) {
                err.println("cat-java artifactory-login: unrecognized arguments: " + arg);
                return 2;
            }
        }
        String user = StoredArtifactoryCredentials.normaliseUsername(orEmpty(prompt.line("Artifactory username: ")));
        String password = orEmpty(prompt.secret("Artifactory password / API key: "));
        if (user.isEmpty() || password.isEmpty()) {
            err.println("nothing saved: username and password are both required");
            return EXIT_FAILED;
        }
        try {
            out.println("saved to " + save(user, password) + " (DPAPI, this Windows account only)");
        } catch (IOException | RuntimeException e) {
            // Never the password: only what failed.
            err.println("nothing saved: could not write " + file + " (" + e.getClass().getSimpleName() + ")");
            return EXIT_FAILED;
        }
        return EXIT_OK;
    }

    /** Writes the file in Monarch's format, encrypted to this account; returns its path. */
    Path save(String user, String password) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] blob = protector.protect(password.getBytes(StandardCharsets.UTF_16LE));
        Files.writeString(file, json(StoredArtifactoryCredentials.normaliseUsername(user),
                HexFormat.of().formatHex(blob)), StandardCharsets.UTF_8);
        return file;
    }

    /** {@code json.dumps({"Username": ..., "Password": ...})}: the Python's separators, no trailing newline. */
    static String json(String user, String hex) throws JsonProcessingException {
        return "{\"Username\": " + JSON.writeValueAsString(user) + ", \"Password\": "
                + JSON.writeValueAsString(hex) + "}";
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** The console when there is one; else standard input, warning that the password may be echoed. */
    static Prompt consolePrompt() {
        Console console = System.console();
        if (console != null) {
            return new Prompt() {
                @Override
                public String line(String text) {
                    return console.readLine("%s", text);
                }

                @Override
                public String secret(String text) {
                    char[] chars = console.readPassword("%s", text);
                    return chars == null ? null : new String(chars);
                }
            };
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new Prompt() {
            @Override
            public String line(String text) {
                System.out.print(text);
                System.out.flush();
                return read(in);
            }

            @Override
            public String secret(String text) {
                System.err.println("Warning: Password input may be echoed.");
                System.out.print(text);
                System.out.flush();
                return read(in);
            }
        };
    }

    private static String read(BufferedReader in) {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
