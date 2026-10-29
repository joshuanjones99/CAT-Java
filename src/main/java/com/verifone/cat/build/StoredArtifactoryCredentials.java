package com.verifone.cat.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Artifactory login as the Python CAT finds it ({@code petrotest.build.credentials.load_credentials}): first
 * {@value EnvArtifactoryCredentials#USER_ENV} and {@value EnvArtifactoryCredentials#PASSWORD_ENV}, else the first of
 * the stored files that reads and decrypts: CAT-Java's {@code ~/.cat/java/artifactory.credentials} (written by
 * {@code cat-java artifactory-login}, {@link ArtifactoryLogin}), the Python CAT's {@code .secrets/artifactory.credentials}
 * (written by {@code petrotest artifactory-login}), then Monarch's own {@code .credentials}.
 *
 * <p>Deviation: the Python has no CAT-Java file. CAT-Java writes only under {@code ~/.cat/java/}, never into the
 * Python checkout's {@code .secrets/}, so its login lives there, and is read first: what the operator last typed into
 * CAT-Java wins over an older save of the Python CAT's.
 *
 * <p>The files are Monarch's format: {@code {"Username": ..., "Password": <hex>}}, where the hex is PowerShell's keyless
 * {@code ConvertFrom-SecureString} output, a DPAPI blob of the UTF-16LE password, tied to the Windows account that
 * wrote it. A file that is missing, malformed or will not decrypt is skipped, not fatal, as the Python's
 * {@code _read_file}. The user is normalised as the Python's {@code normalise_username} (everything from the first
 * {@code @} dropped). CAT-Java writes only its own file, and only from {@link ArtifactoryLogin}.
 *
 * <p>The files are read only for the process's own environment, as the Python's {@code ArtifactoryClient._auth}
 * falls back to them only when its {@code environ is os.environ}: {@link #forEnvironment} with any other map (a
 * test's) is environment-only, so no test reaches a real credential.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the DPAPI decrypt ({@link #DPAPI}) has not been run against the
 * controller's file from CAT-Java; the tests pass a fake decryptor.
 */
public final class StoredArtifactoryCredentials implements ArtifactoryCredentials {

    /** The Python CAT's checkout, {@code $CAT_PYTHON_ROOT} or this default, as {@code provision.ProvisionPaths}. */
    public static final Path DEFAULT_PYTHON_ROOT = Path.of("C:\\gitrepos\\CAT");

    /** Monarch's credential files, the Python's {@code MONARCH_CREDENTIALS_FILES}. */
    public static final List<Path> MONARCH_FILES = List.of(
            Path.of("C:\\CICD\\Scripts\\Overarch\\nightly-agent\\.credentials"),
            Path.of("C:\\CICD\\Scripts\\nightly-agent\\.credentials"));

    /** Turns a DPAPI blob back into its plaintext bytes; throws when it will not decrypt under this account. */
    @FunctionalInterface
    public interface Decryptor {
        byte[] unprotect(byte[] blob);
    }

    /** Windows' DPAPI, {@code CryptUnprotectData} under the current account (JNA's {@code Crypt32Util}). */
    public static final Decryptor DPAPI = blob -> com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(blob);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> env;
    private final List<Path> files;
    private final Decryptor decryptor;

    /**
     * @param env       the environment, read first
     * @param files     the stored files, in order
     * @param decryptor how a file's password is decrypted
     */
    public StoredArtifactoryCredentials(Map<String, String> env, List<Path> files, Decryptor decryptor) {
        this.env = env == null ? Map.of() : env;
        this.files = List.copyOf(files);
        this.decryptor = decryptor;
    }

    /**
     * The credentials a run uses: with the process's own environment ({@code System.getenv()}, the same instance), the
     * environment then the stored files; with any other map, the environment only ({@link EnvArtifactoryCredentials}).
     */
    public static ArtifactoryCredentials forEnvironment(Map<String, String> env) {
        if (env != null && env == System.getenv()) {
            return new StoredArtifactoryCredentials(env, defaultFiles(env), DPAPI);
        }
        return new EnvArtifactoryCredentials(env == null ? Map.of() : env);
    }

    /** The file {@code cat-java artifactory-login} writes: {@code <home>\.cat\java\artifactory.credentials}. */
    public static Path catJavaFile(String userHome) {
        return Path.of(userHome, ".cat", "java", CREDENTIALS_FILE_NAME);
    }

    /** The name both CATs give their saved login. */
    public static final String CREDENTIALS_FILE_NAME = "artifactory.credentials";

    /**
     * CAT-Java's own file ({@link #catJavaFile}), then the Python CAT's {@code .secrets/artifactory.credentials}, then
     * Monarch's files.
     */
    public static List<Path> defaultFiles(Map<String, String> env) {
        return defaultFiles(env, System.getProperty("user.home"));
    }

    /** {@link #defaultFiles(Map)} for the given home folder. */
    public static List<Path> defaultFiles(Map<String, String> env, String userHome) {
        String root = env == null ? null : env.get("CAT_PYTHON_ROOT");
        Path python = root == null || root.isBlank() ? DEFAULT_PYTHON_ROOT : Path.of(root);
        List<Path> all = new java.util.ArrayList<>();
        all.add(catJavaFile(userHome));
        all.add(python.resolve(".secrets").resolve(CREDENTIALS_FILE_NAME));
        all.addAll(MONARCH_FILES);
        return List.copyOf(all);
    }

    @Override
    public Login login() {
        String user = env.getOrDefault(EnvArtifactoryCredentials.USER_ENV, "");
        String password = env.getOrDefault(EnvArtifactoryCredentials.PASSWORD_ENV, "");
        if (!user.isBlank() && !password.isBlank()) {
            return new Login(normaliseUsername(user), password);
        }
        for (Path file : files) {
            Optional<Login> found = read(file, decryptor);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new ArtifactoryAuthError(EnvArtifactoryCredentials.USER_ENV, EnvArtifactoryCredentials.PASSWORD_ENV);
    }

    /**
     * One file in Monarch's format, or empty when it is missing, malformed, or will not decrypt (the Python's
     * {@code _read_file}). Never says why in a way that carries the password.
     */
    static Optional<Login> read(Path file, Decryptor decryptor) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) { // utf-8-sig, as PowerShell writes it
                text = text.substring(1);
            }
            JsonNode data = JSON.readTree(text);
            JsonNode user = data == null ? null : data.get("Username");
            JsonNode hex = data == null ? null : data.get("Password");
            if (user == null || !user.isTextual() || hex == null || !hex.isTextual()) {
                return Optional.empty();
            }
            byte[] plain = decryptor.unprotect(HexFormat.of().parseHex(hex.asText().strip()));
            String password = new String(plain, StandardCharsets.UTF_16LE);
            String name = normaliseUsername(user.asText());
            if (name.isEmpty() || password.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Login(name, password));
        } catch (IOException | RuntimeException e) {
            // A file another Windows account saved will not decrypt here (HANDOFF.md §1.56): skipped, as the Python.
            return Optional.empty();
        }
    }

    /** The Verifone username in {@code user}: {@code jdoe@verifone.com} becomes {@code jdoe}. */
    public static String normaliseUsername(String user) {
        String stripped = user == null ? "" : user.strip();
        int at = stripped.indexOf('@');
        return (at < 0 ? stripped : stripped.substring(0, at)).strip();
    }
}
