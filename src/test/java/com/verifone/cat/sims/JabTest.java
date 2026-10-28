package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.sims.FakeAccessBridge.Node;

/**
 * The click sequence against {@link FakeAccessBridge}: the matching rules, the re-query, the detail
 * on a miss, the caps on a hung bridge, and that every object the bridge hands out is released.
 * Evidence about this port's logic only; no Access Bridge is touched.
 */
class JabTest {

    private static final long HWND = 0x1234;
    private static final Duration CONNECT = Duration.ofSeconds(5);
    private static final Duration SETTLE = Duration.ofMillis(1500);

    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private FakeAccessBridge bridge;

    @AfterEach
    void unblock() {
        if (bridge != null) {
            bridge.unblock();
        }
    }

    private Jab jab(Node root) {
        bridge = new FakeAccessBridge(HWND, root);
        return new Jab(bridge, FakeAccessBridge.recording(pauses));
    }

    @Test
    void eachButtonIsClickedInOrderWithASettleAfterConnectingAndAfterEachClick() {
        Jab jab = jab(Node.panel(Node.panel(Node.button("Load Data")), Node.button("Load Servers")));

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Load Data", "Load Servers"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(true, "clicked Load Data, Load Servers"), outcome);
        assertEquals(List.of("Load Data", "Load Servers"), bridge.clicked);
        assertEquals(List.of(SETTLE, SETTLE, SETTLE), pauses);
        assertEquals(List.of("click", "click"), bridge.actionsDone);
    }

    @Test
    void everyObjectTheBridgeHandsOutIsReleasedRootIncluded() {
        Jab jab = jab(Node.panel(Node.panel(Node.button("Start Host"), new Node("Port", "text")),
                Node.button("Stop Host")));

        assertTrue(jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE).clicked());

        assertTrue(bridge.leaked().isEmpty(), "leaked " + bridge.leaked());
        assertTrue(bridge.handedOut.size() > 1);
    }

    @Test
    void theCallsRunOffTheCallersThread() {
        Jab jab = jab(Node.panel(Node.button("Start Host")));

        jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE);

        String caller = Thread.currentThread().getName();
        assertFalse(bridge.callThreads.contains(caller), bridge.callThreads.toString());
        assertTrue(bridge.callThreads.contains("access-bridge-connect"));
        assertTrue(bridge.callThreads.contains("access-bridge-click"));
    }

    @Test
    void anExactCaseInsensitiveMatchWinsOverAnEarlierSubstringMatch() {
        Jab jab = jab(Node.panel(Node.button("Start Host Later"), Node.button("START HOST")));

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("start host"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(true, "clicked START HOST"), outcome);
    }

    @Test
    void aSubstringMatchIsUsedWhenNoNameIsExact() {
        Jab jab = jab(Node.panel(new Node("Start Host", "label"), Node.button("Start Host Now")));

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("start host"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(true, "clicked Start Host Now"), outcome, "a label is not a button");
    }

    @Test
    void theTreeIsQueriedAgainBeforeEachClick() {
        Node panel = Node.panel();
        Node loadData = Node.button("Load Data");
        loadData.onClick = () -> panel.children.add(Node.button("Load Servers"));
        panel.children.add(loadData);
        Jab jab = jab(panel);

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Load Data", "Load Servers"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(true, "clicked Load Data, Load Servers"), outcome);
    }

    @Test
    void aMissingButtonNamesTheAvailableOnesAndTheRestAreStillTried() {
        Jab jab = jab(Node.panel(Node.button("Start Host"), Node.button(""), Node.button("Stop Host")));

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Load Data", "Stop Host"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(false,
                "clicked Stop Host; 'Load Data' not found (available: Start Host, Stop Host)"), outcome);
        assertTrue(bridge.leaked().isEmpty(), "leaked " + bridge.leaked());
    }

    @Test
    void aWindowWithNoButtonsIsANotFoundWithNothingAvailable() {
        Jab jab = jab(Node.panel(new Node("Status", "label")));

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(false, "clicked nothing; 'Start Host' not found (available: )"), outcome);
    }

    @Test
    void aButtonWithSeveralActionsIsPressedByItsClickAction() {
        Node button = Node.button("Start Host");
        button.actions = List.of("toggle", "Click");
        Jab jab = jab(Node.panel(button));

        assertTrue(jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE).clicked());
        assertEquals(List.of("Click"), bridge.actionsDone);
    }

    @Test
    void aButtonWithNoActionOrAFailedActionIsAProblemNotAThrow() {
        Node none = Node.button("Start Host");
        none.actions = List.of();
        Node refused = Node.button("Stop Host");
        refused.actionWorks = false;
        Jab jab = jab(Node.panel(none, refused));

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host", "Stop Host"), CONNECT, SETTLE);

        assertFalse(outcome.clicked());
        assertEquals("clicked nothing; 'Start Host' failed: IllegalStateException: the button supports no "
                + "accessible action; 'Stop Host' failed: IllegalStateException: doAccessibleActions(click) "
                + "reported failure", outcome.detail());
    }

    @Test
    void theBridgeIsStartedAndPumpedUntilTheWindowIsAJavaWindow() {
        Jab jab = jab(Node.panel(Node.button("Start Host")));
        bridge.notJavaFor = 3;

        assertTrue(jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE).clicked());

        assertEquals(1, bridge.windowsRuns.get(), "registered within one attempt");
        assertTrue(bridge.pumps.get() >= 4, "pumped " + bridge.pumps.get());
    }

    @Test
    void aWindowTheBridgeNeverKnowsIsRetriedThenACouldNotConnectRow() {
        Jab jab = new Jab(bridge = new FakeAccessBridge(HWND, Node.panel()), Park::park);
        bridge.notJavaFor = Integer.MAX_VALUE;

        long started = System.nanoTime();
        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host"), Duration.ofSeconds(1), SETTLE);

        assertFalse(outcome.clicked());
        assertTrue(outcome.detail().startsWith("could not connect to the simulator window: HWND 4660 is not a "
                + "Java window the Access Bridge knows"), outcome.detail());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(8)) < 0);
    }

    @Test
    void aFailedContextIsRetriedThenACouldNotConnectRow() {
        Jab jab = new Jab(bridge = new FakeAccessBridge(HWND, Node.panel()), Park::park);
        bridge.contextAvailable = false;

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host"), Duration.ofSeconds(1), SETTLE);

        assertEquals(new Jab.Outcome(false, "could not connect to the simulator window: "
                + "getAccessibleContextFromHWND failed for HWND 4660"), outcome);
    }

    @Test
    void aHungConnectIsAbandonedAtItsCapAndReported() {
        Jab jab = jab(Node.panel(Node.button("Start Host")));
        bridge.hangWindowsRun = true;

        long started = System.nanoTime();
        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host"), Duration.ofSeconds(1), SETTLE,
                Duration.ofSeconds(5));

        assertFalse(outcome.clicked());
        assertEquals("could not connect to the simulator window: Access Bridge connect timed out after 1s",
                outcome.detail());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(4)) < 0);
    }

    @Test
    void aHungClickIsAbandonedAtTheSequenceCap() {
        Jab jab = jab(Node.panel(Node.button("Start Host")));
        bridge.hangAction = true;

        long started = System.nanoTime();
        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE, Duration.ofSeconds(1));

        assertEquals(new Jab.Outcome(false, "clicking timed out after 1s"), outcome);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(4)) < 0);
    }

    @Test
    void aBridgeThatCannotLoadStopsAtOnceRatherThanRetrying() {
        Jab jab = jab(Node.panel(Node.button("Start Host")));
        bridge.windowsRunError = new UnsatisfiedLinkError("Unable to load library 'windowsaccessbridge-64.dll'");

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(false, "could not connect to the simulator window: "
                + "Unable to load library 'windowsaccessbridge-64.dll'"), outcome);
        assertEquals(1, bridge.windowsRuns.get());
    }

    @Test
    void theTreeWalkIsBounded() {
        Node panel = Node.panel();
        for (int i = 0; i <= Jab.MAX_NODES; i++) {
            panel.children.add(new Node("x" + i, "label"));
        }
        Jab jab = jab(panel);

        Jab.Outcome outcome = jab.clickButtons(HWND, List.of("Start Host"), CONNECT, SETTLE);

        assertEquals(new Jab.Outcome(false, "clicked nothing; 'Start Host' failed: IllegalStateException: more than "
                + Jab.MAX_NODES + " accessible elements in the window"), outcome);
        assertTrue(bridge.leaked().isEmpty(), "leaked " + bridge.leaked().size());
    }
}
