package com.verifone.cat.devices.csite;

import com.verifone.cat.devices.DeviceError;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * C-Site's settings: the login (from the environment, as the Python reads {@code cloudcredentials.prop}), and the
 * endpoints the Commander's Commander Connect files name ({@code app}: {@link CommanderCloudConfig}, the Python's
 * {@code commander_cloud_config}, reads them over SSH).
 *
 * <p>The variables are {@link CSiteClient#ENV_VARS}, renamed from PATS's keys as CLAUDE.md's credential rule asks. A
 * credential is never hard-coded here, and never printed: {@link #toString()} omits the username and the password.
 *
 * <p>Refused rather than guessed, as the Python refuses them: the legacy PKCE token flow, a basic-auth C-Site, and
 * {@code isDisableCsiteSSLVerification=true}. Java's HTTP client cannot skip certificate checks without a trust-all
 * context, and a silent one would hide a cloud that is not the one the Commander is onboarded to.
 *
 * @param env                 the C-Site environment name ({@code qat}, {@code dev}, ...)
 * @param username            the C-Site login, or empty
 * @param password            the C-Site password, or empty
 * @param stripServicePrefix  whether the endpoint's {@code pcms/} prefix is dropped (PATS's
 *                            {@code csite.strip.service.prefix})
 * @param app                 the endpoint settings, keyed as the Commander's files key them
 */
public record CSiteSettings(String env, String username, String password, boolean stripServicePrefix,
        Map<String, String> app) {

    /** The app key holding the Commander's lab (its profile). */
    public static final String PROFILE_KEY = "profile";

    /** PATS's {@code cloudRetry} and {@code cloud.http.read.timeout} defaults. */
    public static final int DEFAULT_ATTEMPTS = 3;
    public static final long DEFAULT_READ_TIMEOUT_MS = 90_000L;

    public CSiteSettings {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        app = Map.copyOf(Objects.requireNonNull(app, "app"));
    }

    /**
     * The settings from {@code environ} (the {@link CSiteClient#ENV_VARS} variables) and the endpoint {@code app}. An
     * unset {@code CAT_CSITE_ENV} is the lab the Commander is onboarded to.
     *
     * @throws DeviceError when the auth mode, auth type or SSL setting is one this port refuses
     */
    public static CSiteSettings fromEnvironment(Map<String, String> environ, Map<String, String> app) {
        Objects.requireNonNull(environ, "environ");
        Objects.requireNonNull(app, "app");
        String authMode = value(environ, "cloud.auth.mode").toLowerCase(Locale.ROOT);
        if (authMode.equals("legacy")) {
            throw new DeviceError("cloud.auth.mode=legacy (PKCE token flow) is not ported to CAT-Java");
        }
        String auth = value(environ, "csite.auth.type").toLowerCase(Locale.ROOT);
        if (auth.isEmpty()) {
            auth = "oauthbearertoken";
        }
        if (!Set.of("oauthbearertoken", "bearertoken", "bearer").contains(auth)) {
            throw new DeviceError("csite.auth.type=" + auth + " is not ported to CAT-Java");
        }
        if (value(environ, "isDisableCsiteSSLVerification").equalsIgnoreCase("true")) {
            throw new DeviceError("isDisableCsiteSSLVerification=true is not ported to CAT-Java: "
                    + "C-Site's certificate is always checked");
        }
        String env = value(environ, "cfg_env").toLowerCase(Locale.ROOT);
        if (env.isEmpty()) {
            env = app.getOrDefault(PROFILE_KEY, "");
        }
        return new CSiteSettings(
                env,
                value(environ, "csite.username"),
                value(environ, "csite.password"),
                value(environ, "csite.strip.service.prefix").equalsIgnoreCase("true"),
                app);
    }

    /** The base URL: {@code csite.base.url.<env>}, else {@code csite.base.url}. */
    public String baseUrl() {
        String value = app.getOrDefault("csite.base.url." + env, "");
        if (value.isBlank()) {
            value = app.getOrDefault("csite.base.url", "");
        }
        if (value.isBlank()) {
            throw new DeviceError(where() + " names no base URL for C-Site environment " + env);
        }
        return value.strip();
    }

    /** A named app setting for this environment, or {@code fallback}; an empty one with no fallback is refused. */
    String appSetting(String key, String fallback) {
        String value = app.getOrDefault(key + "." + env, "");
        if (value.isEmpty()) {
            value = fallback == null ? "" : fallback;
        }
        if (value.isEmpty()) {
            throw new DeviceError(where() + " names no " + key + " for C-Site environment " + env);
        }
        return value;
    }

    /** The retries a request is given ({@code cloudRetry}). */
    public int attempts() {
        return (int) longSetting(app.get("cloudRetry"), DEFAULT_ATTEMPTS);
    }

    /** The read timeout in milliseconds ({@code cloud.http.read.timeout}). */
    public long readTimeoutMillis() {
        return longSetting(app.get("cloud.http.read.timeout"), DEFAULT_READ_TIMEOUT_MS);
    }

    private String where() {
        String lab = app.get(PROFILE_KEY);
        return lab != null && !lab.isEmpty() ? "the Commander (onboarded to " + lab + ")" : "the endpoint settings";
    }

    private static String value(Map<String, String> environ, String key) {
        String name = CSiteClient.ENV_VARS.get(key);
        return environ.getOrDefault(name, "").strip();
    }

    private static long longSetting(String text, long fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The password and username are never printed. */
    @Override
    public String toString() {
        return "CSiteSettings[env=" + env + ", stripServicePrefix=" + stripServicePrefix
                + ", credentials=" + (username.isEmpty() ? "none" : "set") + "]";
    }
}
