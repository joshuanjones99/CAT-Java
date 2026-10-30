package com.verifone.cat.atlassian;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The Atlassian credentials file, read the way the Python CAT's {@code cat.atlassian} reads it.
 *
 * <p>The lookup is the file {@code %USERPROFILE%\jira_api_token.properties}: the same file
 * Monarch reads, holding {@code email} and {@code jira.token}, plus {@code xray.client.id} and
 * {@code xray.client.secret} for Xray. It is read, never written: writing belongs to the
 * setup step, which is not in this port. The Python CAT does not read environment variables
 * for this; the file is the lookup (see the port's README).
 *
 * <p>Each record's {@code toString} is redacted, so a credential that reaches a log line or a
 * debugger shows its type and nothing else.
 */
public final class AtlassianCredentials {

    /** The setup-audit item that fixes a missing credentials file; named in every error. */
    public static final String SETUP_STEP = "jira_api_token";

    static final String EMAIL_KEY = "email";
    static final String TOKEN_KEY = "jira.token";
    static final String XRAY_ID_KEY = "xray.client.id";
    static final String XRAY_SECRET_KEY = "xray.client.secret";

    /** The Jira or Confluence basic-auth pair. */
    public record Jira(String email, String token) {

        @Override
        public String toString() {
            return "Jira[redacted]";
        }
    }

    /** The Xray Cloud client id and secret. */
    public record Xray(String clientId, String clientSecret) {

        @Override
        public String toString() {
            return "Xray[redacted]";
        }
    }

    private AtlassianCredentials() {}

    /**
     * The Jira token file: CAT-Java's own {@code ~/.cat/java/jira_api_token.properties} (where the Setup page saves a
     * login) when it exists, else {@code %USERPROFILE%\jira_api_token.properties}, the file the Python CAT reads.
     */
    public static Path defaultPath() {
        Path home = Path.of(System.getProperty("user.home"));
        Path own = home.resolve(".cat").resolve("java").resolve("jira_api_token.properties");
        return java.nio.file.Files.isRegularFile(own) ? own : home.resolve("jira_api_token.properties");
    }

    /** The email and token, or {@link AtlassianUnavailable} naming the setup step. */
    public static Jira loadJira(Path file) {
        Properties values = read(file);
        require(values, file, EMAIL_KEY, TOKEN_KEY);
        return new Jira(values.getProperty(EMAIL_KEY).trim(), values.getProperty(TOKEN_KEY).trim());
    }

    /** The Xray client id and secret from the same file, or {@link AtlassianUnavailable}. */
    public static Xray loadXray(Path file) {
        Properties values = read(file);
        require(values, file, XRAY_ID_KEY, XRAY_SECRET_KEY);
        return new Xray(values.getProperty(XRAY_ID_KEY).trim(),
                values.getProperty(XRAY_SECRET_KEY).trim());
    }

    private static Properties read(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AtlassianUnavailable("no Atlassian credentials at " + file + " ("
                    + e.getClass().getSimpleName() + ") — run the '" + SETUP_STEP + "' setup step", e);
        }
        Properties values = new Properties();
        try {
            values.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            throw new AtlassianUnavailable(file + " is not a readable .properties file — run the '"
                    + SETUP_STEP + "' setup step", e);
        }
        return values;
    }

    private static void require(Properties values, Path file, String... keys) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            String value = values.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new AtlassianUnavailable(file + " is missing " + String.join(", ", missing)
                    + " — run the '" + SETUP_STEP + "' setup step");
        }
    }
}
