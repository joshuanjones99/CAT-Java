package com.verifone.cat.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.build.ArtifactoryCredentials;
import com.verifone.cat.build.StoredArtifactoryCredentials;

/**
 * Where the two sign-ins live: the Verifone login for Artifactory (Monarch's DPAPI file format) and the Atlassian
 * credentials file. The Python CAT's {@code petrotest.build.credentials.save_credentials/load_credentials} and
 * {@code cat.atlassian.CREDENTIALS_PATH}, for CAT-Java.
 *
 * <p><b>Deviation from the Python (CLAUDE.md hard rule 6):</b> CAT-Java writes only under {@code ~/.cat/java/}. The
 * Python CAT saves the Verifone login to its own {@code .secrets/artifactory.credentials} and the Atlassian login to
 * the shared {@code ~/jira_api_token.properties}; CAT-Java saves them to {@code ~/.cat/java/artifactory.credentials}
 * and {@code ~/.cat/java/jira_api_token.properties} and reads its own first, then the Python's and Monarch's files
 * read-only, as {@link StoredArtifactoryCredentials} does. Until the rest of CAT-Java reads the two
 * {@code ~/.cat/java/} files first (a hook in the wave-16 report: {@code StoredArtifactoryCredentials.defaultFiles}
 * and {@code AtlassianCredentials.defaultPath}), a login saved here is used by the Setup page's own checks and by
 * the parts that take their credentials from here (the Config tab's Refresh, the downloads listings).
 *
 * <p>A password is never logged, never shown in a result, never written in plain text: the Artifactory file holds
 * a DPAPI blob of it, readable only by this Windows account on this PC.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the DPAPI round trip is tested with a fake protector.
 */
public final class SavedLogins {

    /** Encrypts bytes for this Windows account (DPAPI {@code CryptProtectData}). */
    @FunctionalInterface
    public interface Protector {
        byte[] protect(byte[] plain);
    }

    /** Windows' DPAPI, {@code CryptProtectData} under the current account, as PowerShell's {@code ConvertFrom-SecureString}. */
    public static final Protector DPAPI = plain -> com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain);

    /** CAT-Java's Artifactory login file, under {@code ~/.cat/java/}. */
    public static final String ARTIFACTORY_FILE = "artifactory.credentials";

    /** The Atlassian credentials file's name: the same name in {@code ~/.cat/java/} and in the home directory. */
    public static final String JIRA_FILE = "jira_api_token.properties";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> env;
    private final Path home;
    private final List<Path> artifactoryFiles;
    private final StoredArtifactoryCredentials.Decryptor decryptor;
    private final Protector protector;

    /**
     * @param env        read first for {@code ARTIFACTORY_USER}/{@code ARTIFACTORY_PASSWORD}
     * @param home       the user's home: {@code ~/.cat/java/} and the shared Atlassian file are under it
     * @param artifactoryFiles the Artifactory files read, in order (this class writes the first, {@link #ownArtifactoryFile})
     * @param decryptor  how a file's password is decrypted
     * @param protector  how a saved password is encrypted; null when this environment cannot save one
     */
    public SavedLogins(Map<String, String> env, Path home, List<Path> artifactoryFiles,
            StoredArtifactoryCredentials.Decryptor decryptor, Protector protector) {
        this.env = env == null ? Map.of() : env;
        this.home = home;
        this.artifactoryFiles = List.copyOf(artifactoryFiles);
        this.decryptor = decryptor;
        this.protector = protector;
    }

    /** The real stores: CAT-Java's own file, then the Python CAT's and Monarch's (read-only), with DPAPI. */
    public static SavedLogins system(Map<String, String> env, Path home) {
        List<Path> files = new ArrayList<>();
        files.add(home.resolve(".cat").resolve("java").resolve(ARTIFACTORY_FILE));
        files.addAll(StoredArtifactoryCredentials.defaultFiles(env));
        return new SavedLogins(env, home, files, StoredArtifactoryCredentials.DPAPI, DPAPI);
    }

    /** A test's store: the variables only; no file is read and nothing can be saved. */
    public static SavedLogins envOnly(Map<String, String> env, Path home) {
        return new SavedLogins(env, home, List.of(), blob -> {
            throw new IllegalStateException("no stored logins in this environment");
        }, null);
    }

    // ------------------------------------------------------------------ Artifactory (the Verifone login)

    /** {@code ~/.cat/java/artifactory.credentials}. */
    public Path ownArtifactoryFile() {
        return home.resolve(".cat").resolve("java").resolve(ARTIFACTORY_FILE);
    }

    /** The credentials every Artifactory client CAT-Java builds from here uses: the variables, then the files. */
    public ArtifactoryCredentials artifactoryCredentials() {
        return new StoredArtifactoryCredentials(env, artifactoryFiles, decryptor);
    }

    /** The saved Verifone login, or empty when none reads (the Python's {@code load_credentials() is None}). */
    public Optional<ArtifactoryCredentials.Login> artifactory() {
        try {
            return Optional.of(artifactoryCredentials().login());
        } catch (RuntimeException e) { // ArtifactoryAuthError: none saved
            return Optional.empty();
        }
    }

    /** Whether this store can save a login (the real one can; a test's env-only one cannot). */
    public boolean canSave() {
        return protector != null;
    }

    /**
     * Saves the login in Monarch's format, encrypted to this account: {@code {"Username": ..., "Password": <hex>}},
     * the hex being the DPAPI blob of the UTF-16LE password, as {@code ConvertFrom-SecureString} writes it.
     *
     * @return the file written
     * @throws IOException when this store cannot save, or the file cannot be written
     */
    public Path saveArtifactory(String user, String password) throws IOException {
        if (protector == null) {
            throw new IOException("this environment has no credential store to save to");
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("Username", StoredArtifactoryCredentials.normaliseUsername(user));
        data.put("Password", HexFormat.of().formatHex(protector.protect(password.getBytes(StandardCharsets.UTF_16LE))));
        Path file = ownArtifactoryFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(data), StandardCharsets.UTF_8);
        return file;
    }

    // ------------------------------------------------------------------ Atlassian

    /** {@code ~/.cat/java/jira_api_token.properties}: where CAT-Java saves the Atlassian login. */
    public Path ownJiraFile() {
        return home.resolve(".cat").resolve("java").resolve(JIRA_FILE);
    }

    /** {@code ~/jira_api_token.properties}: the file Monarch and the Python CAT share; read-only here. */
    public Path sharedJiraFile() {
        return home.resolve(JIRA_FILE);
    }

    /** The Atlassian file in use: CAT-Java's own once it exists, else the shared one. */
    public Path jiraFile() {
        Path own = ownJiraFile();
        return Files.isRegularFile(own) ? own : sharedJiraFile();
    }
}
