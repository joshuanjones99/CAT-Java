package com.verifone.cat.devices.csite;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.verifone.cat.cloudtunnel.CloudTunnel;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * C-Site's endpoints for the lab the Commander is onboarded to, read from the Commander's own Commander Connect files:
 * the Python's {@code cloud_config_from} and {@code commander_cloud_config} in {@code petrotest.devices.csite}, which
 * are the specification. The result is the {@code app} map {@link CSiteSettings} reads.
 *
 * <p>Prod's {@code cloud_connect.prop} carries the OAuth client; a lab's ({@code cloud_connect_qat.prop}) overrides
 * its addresses. {@code cloudURL} is C-Site's {@code /oidc} base plus the Commander's own {@code /pems}, so the base
 * URL is it without its last segment; ForgeRock's authenticate and token paths go under {@code forgerockURL}.
 *
 * <p>Read over SSH once per Commander host for the process, as the Python caches it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the file names and keys were read off the lab Commander by the Python
 * CAT (2026-10-22); this port has read them only from fakes.
 */
public final class CommanderCloudConfig {

    /** The Commander's Commander Connect settings, {@code {suffix}} empty for prod or {@code _<lab>}. */
    public static final String CLOUD_CONNECT_FILE = "/home/coresvcs/config/cloud_connect{suffix}.prop";
    /** ForgeRock's paths under the Commander's {@code forgerockURL}. */
    public static final String AUTHENTICATE_PATH = "/am/json/authenticate";
    public static final String ACCESS_TOKEN_PATH = "/am/oauth2/access_token";

    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private CommanderCloudConfig() {}

    /** The {@code cloud_connect} file for {@code suffix} ({@code ""} or {@code _qat}). */
    public static String file(String suffix) {
        return CLOUD_CONNECT_FILE.replace("{suffix}", suffix);
    }

    /**
     * C-Site's endpoints for {@code profile}, keyed as {@link CSiteSettings} reads them, from the {@code cloud_connect}
     * files' texts (prod's first, the lab's over it).
     *
     * @throws DeviceError when the files name no {@code forgerockURL} or {@code cloudURL}: not onboarded
     */
    public static Map<String, String> fromTexts(String profile, List<String> texts) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String text : texts) {
            // A Java properties file escapes a value's ':' (https\://...).
            CloudTunnel.parseProperties(text).forEach((key, value) -> values.put(key, value.replace("\\", "")));
        }
        String forgerock = stripTrailingSlashes(values.getOrDefault("forgerockURL", ""));
        String cloud = stripTrailingSlashes(values.getOrDefault("cloudURL", ""));
        if (forgerock.isEmpty() || !pathOf(cloud).contains("/")) {
            throw new DeviceError("the Commander's " + file("_" + profile) + " names no forgerockURL or cloudURL: "
                    + "is it onboarded to Commander Connect?");
        }
        Map<String, String> config = new LinkedHashMap<>();
        config.put(CSiteSettings.PROFILE_KEY, profile);
        config.put("csite.base.url." + profile, cloud.substring(0, cloud.lastIndexOf('/')));
        config.put("cloud.auth.id.url." + profile, forgerock + AUTHENTICATE_PATH);
        config.put("cloud.access.token.url." + profile, forgerock + ACCESS_TOKEN_PATH);
        for (String[] pair : new String[][] {{"cloud.petro.client.id", "client_id"}, {"cloud.petro.scope", "scope"}}) {
            String value = values.getOrDefault(pair[1], "").strip();
            if (!value.isEmpty()) {
                config.put(pair[0] + "." + profile, value);
            }
        }
        return Map.copyOf(config);
    }

    /**
     * {@code commander_cloud_config}: the endpoints for the lab {@code host}'s Commander is onboarded to, read over a
     * transport from {@code transports} (connected, closed after) once per host for the process.
     *
     * @throws DeviceError "cannot read the Commander's cloud settings: ..." when the read fails, or
     *                     {@link #fromTexts}'s refusal
     */
    public static Map<String, String> forCommander(String host, Supplier<Transport> transports, Duration timeout) {
        Map<String, String> cached = CACHE.get(host);
        if (cached != null) {
            return cached;
        }
        Map<String, String> config = read(transports, timeout);
        CACHE.put(host, config);
        return config;
    }

    /** {@link #forCommander} without the cache. */
    public static Map<String, String> read(Supplier<Transport> transports, Duration timeout) {
        Transport transport;
        try {
            transport = transports.get();
        } catch (RuntimeException e) {
            throw new DeviceError("cannot read the Commander's cloud settings: " + e.getMessage(), e);
        }
        String profile;
        List<String> texts = new ArrayList<>();
        try {
            profile = CloudTunnel.profileOf(cat(transport, CloudTunnel.PROFILE_FILE, timeout));
            List<String> files = new ArrayList<>(List.of(file("")));
            if (!profile.equals("prod")) {
                files.add(file("_" + profile));
            }
            for (String path : files) {
                texts.add(cat(transport, path, timeout));
            }
        } catch (RuntimeException e) {
            throw new DeviceError("cannot read the Commander's cloud settings: " + e.getMessage(), e);
        } finally {
            transport.close();
        }
        return fromTexts(profile, texts);
    }

    /** Forgets what was read: for a test, or after the Commander was onboarded elsewhere. */
    public static void forget() {
        CACHE.clear();
    }

    private static String cat(Transport transport, String path, Duration timeout) {
        CommandResult result = transport.run("cat " + path + " 2>/dev/null", timeout);
        return result.ok() ? result.stdout() : "";
    }

    private static String stripTrailingSlashes(String text) {
        String out = text;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getRawPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
