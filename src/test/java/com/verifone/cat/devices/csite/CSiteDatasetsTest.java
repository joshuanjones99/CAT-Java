package com.verifone.cat.devices.csite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The smart-suggestion and tip clean-up, ported from the Python {@code clear_smart_suggestions}, {@code clear_tips} and
 * their listing parsers, against the loopback stand-in.
 */
class CSiteDatasetsTest {

    private static final String SERVICE = "1181078";
    private static final String SUGGESTIONS = "/oidc/pcms/v1/config/" + SERVICE + "/smartsuggestions";
    private static final String TIPS = "/oidc/pcms/v1/config/" + SERVICE + "/tips";

    private CSiteFake fake;
    private CSiteClient client;

    @BeforeEach
    void setUp() throws IOException {
        fake = new CSiteFake();
        CSiteClientTest.loginRoutes(fake);
        Map<String, String> environ = new HashMap<>(CSiteClientTest.environ("tester@example.invalid", "not-a-real-one"));
        Map<String, String> app = new HashMap<>(Map.of(
                "profile", "qat",
                "csite.base.url.qat", fake.url() + "/oidc",
                "cloud.auth.id.url.qat", fake.url() + "/am/json/authenticate",
                "cloud.access.token.url.qat", fake.url() + "/am/oauth2/access_token"));
        client = new CSiteClient(CSiteSettings.fromEnvironment(environ, app), HttpClient.newHttpClient(),
                ignored -> { });
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void everySmartSuggestionOnTheServiceIsDeleted() {
        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.json(200, ("{\"config\": {\"smartSuggestionsConfig\": "
                + "{\"smartSuggestions\": {\"smartSuggestion\": [{\"sysid\": 11}, {\"sysid\": 12}]}}}}")));
        fake.route("DELETE", SUGGESTIONS + "/11", CSiteFake.Reply.of(204));
        fake.route("DELETE", SUGGESTIONS + "/12", CSiteFake.Reply.of(404));

        List<Long> deleted = CSiteDatasets.clearSmartSuggestions(client, SERVICE);

        assertEquals(List.of(11L, 12L), deleted);
        assertEquals(1, fake.seen("DELETE", SUGGESTIONS + "/11").size());
        assertEquals(1, fake.seen("DELETE", SUGGESTIONS + "/12").size());
    }

    @Test
    void aSingleSuggestionAsABareObjectIsOneItem() {
        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.json(200, "{\"config\": {\"smartSuggestionsConfig\": "
                + "{\"smartSuggestions\": {\"smartSuggestion\": {\"sysid\": 7}}}}}"));
        fake.route("DELETE", SUGGESTIONS + "/7", CSiteFake.Reply.of(200));

        assertEquals(List.of(7L), CSiteDatasets.clearSmartSuggestions(client, SERVICE));
    }

    @Test
    void aCleanServiceDeletesNothing() {
        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.json(200,
                "{\"config\": {\"smartSuggestionsConfig\": {\"smartSuggestions\": {\"smartSuggestion\": null}}}}"));

        assertEquals(List.of(), CSiteDatasets.clearSmartSuggestions(client, SERVICE));
        assertEquals(0, fake.seen("DELETE", SUGGESTIONS + "/0").size());
    }

    @Test
    void aListingWithNoSuggestionsPathIsOneCATCannotRead() {
        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.json(200, "{\"config\": {}}"));

        DeviceProtocolError error = assertThrows(DeviceProtocolError.class,
                () -> CSiteDatasets.clearSmartSuggestions(client, SERVICE));
        assertTrue(error.getMessage().contains("smart suggestions listing CAT cannot read"), error.getMessage());
    }

    @Test
    void aFailedListingFailsWithItsStatus() {
        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.json(500, "{}"));

        DeviceError error = assertThrows(DeviceError.class, () -> CSiteDatasets.clearSmartSuggestions(client, SERVICE));
        assertTrue(error.getMessage().contains("answered HTTP 500"), error.getMessage());
    }

    @Test
    void aRefusedDeleteFailsWithItsStatus() {
        fake.route("GET", SUGGESTIONS, CSiteFake.Reply.json(200,
                "{\"config\": {\"smartSuggestionsConfig\": {\"smartSuggestions\": {\"smartSuggestion\": {\"sysid\": 5}}}}}"));
        fake.route("DELETE", SUGGESTIONS + "/5", CSiteFake.Reply.json(500, "{}"));

        DeviceError error = assertThrows(DeviceError.class, () -> CSiteDatasets.clearSmartSuggestions(client, SERVICE));
        assertTrue(error.getMessage().contains("answered HTTP 500"), error.getMessage());
    }

    @Test
    void anEnabledTipIsPutBackDisabledAndTheOthersAreLeftAlone() throws IOException {
        fake.route("GET", TIPS, CSiteFake.Reply.json(200, "{\"config\": {\"tipConfig\": {\"tip\": ["
                + "{\"sysid\": 1, \"enabled\": true, \"name\": \"Tip one\"},"
                + "{\"sysid\": 2, \"enabled\": false, \"name\": \"Tip two\"}]}}}"));
        fake.route("PUT", TIPS + "/1", CSiteFake.Reply.of(200));

        List<Long> off = CSiteDatasets.clearTips(client, SERVICE);

        assertEquals(List.of(1L), off);
        assertEquals(0, fake.seen("PUT", TIPS + "/2").size());
        String put = fake.seen("PUT", TIPS + "/1").get(0).body();
        assertTrue(put.contains("\"enabled\":false") && put.contains("\"name\":\"Tip one\""), put);
    }

    @Test
    void aSingleTipAsABareObjectIsTurnedOff() {
        fake.route("GET", TIPS, CSiteFake.Reply.json(200,
                "{\"config\": {\"tipConfig\": {\"tip\": {\"sysid\": 3, \"enabled\": true}}}}"));
        fake.route("PUT", TIPS + "/3", CSiteFake.Reply.of(204));

        assertEquals(List.of(3L), CSiteDatasets.clearTips(client, SERVICE));
    }

    @Test
    void anUnreadableTipsListingFailsAsProtocol() {
        fake.route("GET", TIPS, CSiteFake.Reply.json(200, "not json"));

        assertThrows(DeviceProtocolError.class, () -> CSiteDatasets.clearTips(client, SERVICE));
    }

    @Test
    void anEmptyListingIsOneCATCannotRead() {
        // PATS reads the same keys of an empty body and of "{}": both are a KeyError, so both fail.
        assertThrows(DeviceProtocolError.class, () -> CSiteDatasets.smartSuggestionIds(new byte[0]));
        assertThrows(DeviceProtocolError.class, () -> CSiteDatasets.tips("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theTipsOfAListingKeepTheirOrder() {
        List<Long> sysids = new ArrayList<>();
        for (var tip : CSiteDatasets.tips(("{\"config\": {\"tipConfig\": {\"tip\": [{\"sysid\": 9}, {\"sysid\": 4}]}}}")
                .getBytes(StandardCharsets.UTF_8))) {
            sysids.add(tip.get("sysid").asLong());
        }
        assertEquals(List.of(9L, 4L), sysids);
    }
}
