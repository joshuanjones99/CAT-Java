package com.verifone.cat.devices.csite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.cloudtunnel.CloudTunnel;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CSiteReset;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

/**
 * The Commander's Commander Connect files read into C-Site's endpoints (the Python's {@code cloud_config_from} and
 * {@code commander_cloud_config}), and {@code csite-reset} ({@code csite_reset_cli}) against the loopback stand-in. No
 * cloud or Commander is reached. Evidence about this repository only.
 */
class CommanderCloudCsiteTest {

    private static final String SERVICE = "1181078";
    private static final String SUGGESTIONS = "/oidc/pcms/v1/config/" + SERVICE + "/smartsuggestions";
    private static final String TIPS = "/oidc/pcms/v1/config/" + SERVICE + "/tips";

    private static final String PROD = "client_id=PetroClient\nscope=write\n"
            + "forgerockURL=https\\://login.example.invalid/\ncloudURL=https\\://csite.example.invalid/oidc/pems\n";
    private static final String QAT = "forgerockURL=https\\://login-qat.example.invalid\n"
            + "cloudURL=https\\://csite-qat.example.invalid/oidc/pems/\n";

    private CSiteFake fake;

    @BeforeEach
    void setUp() throws IOException {
        fake = new CSiteFake();
        CSiteClientTest.loginRoutes(fake);
        CommanderCloudConfig.forget();
    }

    @AfterEach
    void tearDown() {
        fake.close();
        CommanderCloudConfig.forget();
    }

    @Test
    void theLabsFileOverridesProdsAddressesAndKeepsItsClient() {
        Map<String, String> app = CommanderCloudConfig.fromTexts("qat", List.of(PROD, QAT));
        assertEquals("qat", app.get(CSiteSettings.PROFILE_KEY));
        assertEquals("https://csite-qat.example.invalid/oidc", app.get("csite.base.url.qat"));
        assertEquals("https://login-qat.example.invalid/am/json/authenticate", app.get("cloud.auth.id.url.qat"));
        assertEquals("https://login-qat.example.invalid/am/oauth2/access_token", app.get("cloud.access.token.url.qat"));
        assertEquals("PetroClient", app.get("cloud.petro.client.id.qat"));
        assertEquals("write", app.get("cloud.petro.scope.qat"));
    }

    @Test
    void aCommanderNotOnboardedIsSaidSo() {
        DeviceError error = assertThrows(DeviceError.class,
                () -> CommanderCloudConfig.fromTexts("qat", List.of("", "")));
        assertTrue(error.getMessage().contains("cloud_connect_qat.prop names no forgerockURL or cloudURL"),
                error.getMessage());
    }

    @Test
    void theFilesAreReadOverSshOncePerCommander() {
        int[] opened = {0};
        InMemoryTransport commander = new InMemoryTransport("commander.invalid")
                .command("cat " + CloudTunnel.PROFILE_FILE + " 2>/dev/null", new CommandResult(0, "profile=QAT\n", ""))
                .command("cat " + CommanderCloudConfig.file("") + " 2>/dev/null", new CommandResult(0, PROD, ""))
                .command("cat " + CommanderCloudConfig.file("_qat") + " 2>/dev/null", new CommandResult(0, QAT, ""));
        Map<String, String> first = CommanderCloudConfig.forCommander("commander.invalid", () -> {
            opened[0]++;
            return commander;
        }, Duration.ofSeconds(1));
        Map<String, String> again = CommanderCloudConfig.forCommander("commander.invalid", () -> {
            opened[0]++;
            return commander;
        }, Duration.ofSeconds(1));
        assertEquals("qat", first.get(CSiteSettings.PROFILE_KEY));
        assertEquals(first, again);
        assertEquals(1, opened[0]);
    }

    @Test
    void anUnreachableCommanderIsACloudSettingsError() {
        DeviceError error = assertThrows(DeviceError.class, () -> CommanderCloudConfig.read(() -> {
            throw new DeviceError("connection refused");
        }, Duration.ofSeconds(1)));
        assertEquals("cannot read the Commander's cloud settings: connection refused", error.getMessage());
    }

    private CSiteClient client() {
        Map<String, String> environ = new HashMap<>(CSiteClientTest.environ("tester@example.invalid",
                "not-a-real-one"));
        Map<String, String> app = Map.of(
                "profile", "qat",
                "csite.base.url.qat", fake.url() + "/oidc",
                "cloud.auth.id.url.qat", fake.url() + "/am/json/authenticate",
                "cloud.access.token.url.qat", fake.url() + "/am/oauth2/access_token");
        return new CSiteClient(CSiteSettings.fromEnvironment(environ, app), HttpClient.newHttpClient(),
                ignored -> { });
    }

    @Test
    void csiteResetDeletesTheSuggestionsTurnsOffTheTipsAndSaysSo() {
        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.json(200, "{\"config\": {\"smartSuggestionsConfig\": "
                + "{\"smartSuggestions\": {\"smartSuggestion\": [{\"sysid\": 11}, {\"sysid\": 12}]}}}}"));
        fake.route("DELETE", SUGGESTIONS + "/11", CSiteFake.Reply.of(204));
        fake.route("DELETE", SUGGESTIONS + "/12", CSiteFake.Reply.of(204));
        fake.route("GET", TIPS, CSiteFake.Reply.json(200, "{\"config\": {\"tipConfig\": {\"tip\": null}}}"));
        Say.Recorded say = new Say.Recorded();

        assertTrue(CSiteReset.cli(this::client, SERVICE, say));

        assertEquals(List.of("csite-reset: deleted 2 smart suggestion(s) on service " + SERVICE + ": sysid 11, 12",
                "csite-reset: service " + SERVICE + " has no tips on"), say.outLines());
    }

    @Test
    void aCloudCatCannotReachIsAWarnAndSuccess() {
        Say.Recorded say = new Say.Recorded();
        assertTrue(CSiteReset.cli(() -> {
            throw new DeviceError("the Commander (onboarded to qat) names no base URL");
        }, SERVICE, say));
        assertEquals(List.of("csite-reset: WARN nothing checked: the Commander (onboarded to qat) names no base URL"),
                say.outLines());

        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.of(500));
        fake.route("GET", TIPS, CSiteFake.Reply.of(500));
        Say.Recorded each = new Say.Recorded();
        assertTrue(CSiteReset.cli(this::client, SERVICE, each));
        assertEquals(2, each.outLines().size(), each.outText());
        assertTrue(each.outLines().get(0).startsWith("csite-reset: WARN smart suggestions not checked: GET "),
                each.outText());
        assertTrue(each.outLines().get(1).startsWith("csite-reset: WARN tips not checked: "), each.outText());
    }
}
