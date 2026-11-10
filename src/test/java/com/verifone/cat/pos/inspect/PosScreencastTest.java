package com.verifone.cat.pos.inspect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.core.Json;

/** Choosing the register's page and reading DevTools' replies, with no browser. Evidence about this repository only. */
class PosScreencastTest {

    private static final URI ENDPOINT = URI.create("http://register.invalid:19220");

    @Test
    void theSocketIsReachedWhereCatReachedTheBrowserNotWhereTheBrowserSaysItIs() {
        // Behind the register's relay the browser names its own loopback port.
        URI socket = PosScreencast.socketUrl("ws://localhost:9222/devtools/page/ABC123", ENDPOINT);

        assertEquals(URI.create("ws://register.invalid:19220/devtools/page/ABC123"), socket);
    }

    @Test
    void onlyPagesWithASocketCanBeShown() {
        List<PosScreencast.Target> targets = PosScreencast.parseTargets("""
                [{"id":"W","type":"service_worker","url":"https://pos.invalid/sw.js",
                  "webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/W"},
                 {"id":"A","type":"page","title":"Topaz","url":"https://pos.invalid/",
                  "webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/A"},
                 {"id":"B","type":"page","title":"held by DevTools","url":"https://pos.invalid/other"}]
                """, ENDPOINT);

        List<PosScreencast.Target> pages = PosScreencast.pages(targets);

        assertEquals(1, pages.size());
        assertEquals("A", pages.get(0).id());
        assertEquals("Topaz", pages.get(0).title());
    }

    @Test
    void withOnePageThereIsNothingToChoose() {
        List<PosScreencast.Target> targets = PosScreencast.parseTargets("""
                [{"id":"A","type":"page","url":"https://pos.invalid/",
                  "webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/A"}]
                """, ENDPOINT);

        // One page: no socket is opened to choose it (none could be here).
        assertEquals("A", PosScreencast.pick(targets, Duration.ofMillis(50)).id());
    }

    @Test
    void anOutOfProcessPosUiFrameNamesItsPage() {
        List<PosScreencast.Target> targets = PosScreencast.parseTargets("""
                [{"id":"A","type":"page","url":"https://pos.invalid/blank",
                  "webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/A"},
                 {"id":"B","type":"page","url":"https://pos.invalid/appselector",
                  "webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/B"},
                 {"id":"F","type":"iframe","parentId":"B","url":"https://pos.invalid/posuiapp/index.html",
                  "webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/F"}]
                """, ENDPOINT);

        assertEquals("B", PosScreencast.pick(targets, Duration.ofMillis(50)).id());
    }

    @Test
    void noPageIsSaidPlainly() {
        InspectError e = assertThrows(InspectError.class, () -> PosScreencast.pick(List.of(), Duration.ofMillis(50)));

        assertTrue(e.getMessage().contains("no page"), e.getMessage());
    }

    @Test
    void thePosUiFrameIsFoundAnywhereInTheFrameTree() throws Exception {
        JsonNode with = Json.MAPPER.readTree("""
                {"frame":{"url":"https://pos.invalid/appselector"},
                 "childFrames":[{"frame":{"url":"https://pos.invalid/banner"}},
                                {"frame":{"url":"https://pos.invalid/x"},
                                 "childFrames":[{"frame":{"url":"https://pos.invalid/posuiapp/"}}]}]}
                """);
        JsonNode without = Json.MAPPER.readTree("{\"frame\":{\"url\":\"https://pos.invalid/appselector\"}}");

        assertTrue(PosScreencast.anyFrameUrl(with));
        assertFalse(PosScreencast.anyFrameUrl(without));
    }

    @Test
    void theViewportIsTheCssOneWhereTheBrowserHasIt() throws Exception {
        JsonNode modern = Json.MAPPER.readTree("""
                {"layoutViewport":{"clientWidth":2732,"clientHeight":1536},
                 "cssVisualViewport":{"clientWidth":1366,"clientHeight":768}}
                """);
        JsonNode older = Json.MAPPER.readTree("{\"layoutViewport\":{\"clientWidth\":1024,\"clientHeight\":768}}");

        assertArrayEquals(new double[] {1366, 768}, PosScreencast.viewport(modern));
        assertArrayEquals(new double[] {1024, 768}, PosScreencast.viewport(older));
    }

    @Test
    void noRegisterAndABadEndpointAreRefusedBeforeAnythingIsReached() {
        assertTrue(assertThrows(InspectError.class, () -> PosScreencast.endpoint(null)).getMessage()
                .contains("--rig"));
        assertThrows(InspectError.class, () -> PosScreencast.endpoint("register.invalid"));
    }
}
