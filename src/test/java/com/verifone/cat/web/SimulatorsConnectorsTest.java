package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.shell.Shell;
import com.verifone.cat.sims.FakeProcessOps;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.Supervisor;

/**
 * Launch Simulators' connector rows, default ticks, the shared busy flag and the launch's cancel, with fake processes
 * and connector hooks that only record: nothing starts. Evidence about this repository only.
 */
class SimulatorsConnectorsTest {

    @TempDir
    Path tmp;

    private final List<String> started = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();

    private SimulatorsService service(Shell shell, boolean onboarding) {
        SimEntry inert = SimEntry.exeRow("Inert").exe("missing-simulator.exe").match("cat-connectors-token").build();
        SimEntry host = SimEntry.exeRow("Fep Host").exe("missing-simulator.exe").match("cat-fep-token").build();
        SimulatorsService sims = new SimulatorsService(new Supervisor(new FakeProcessOps()), SimContext.of(tmp, ""),
                List.of(inert, host), queued::add)
                .withShell(shell)
                .withFepOnly(Set.of("Fep Host"))
                .withCcOnboard(() -> onboarding)
                .withConnector(SimulatorsService.CARD_SERVER, connector(SimulatorsService.CARD_SERVER, true))
                .withConnector(SimulatorsService.CLOUD_TUNNEL, connector(SimulatorsService.CLOUD_TUNNEL, false));
        sims.resetTicks();
        return sims;
    }

    private SimulatorsService.Connector connector(String name, boolean up) {
        return new SimulatorsService.Connector() {
            @Override
            public String probe() {
                return "";
            }

            @Override
            public Map.Entry<Boolean, String> start(Consumer<String> log) {
                started.add(name);
                return Map.entry(up, up ? "answers on 7000" : "not wired in CAT-Java yet (cat/launch/tunnel.py)");
            }
        };
    }

    @Test
    void theDefaultTicksAreWhatARunWouldStart() {
        SimulatorsService plain = service(new Shell(), false);
        assertTrue(plain.isTicked("Inert"));
        assertFalse(plain.isTicked("Fep Host"), "a FEP's host only when its FEP is used");
        assertTrue(plain.isTicked(SimulatorsService.CARD_SERVER));
        assertFalse(plain.isTicked(SimulatorsService.CLOUD_TUNNEL), "the tunnel only when Commander Connect onboards");
        assertTrue(service(new Shell(), true).isTicked(SimulatorsService.CLOUD_TUNNEL));

        String panel = SimulatorsPage.panel(plain);
        assertTrue(panel.contains("Fep Host <span class=\"muted\">(FEP simulator)</span>"), panel);
        assertTrue(panel.contains("BTT Card Server <span class=\"muted\">(connector)</span>"), panel);
        assertTrue(panel.contains("Cloud Tunnel <span class=\"muted\">(connector)</span>"), panel);
    }

    @Test
    void theCardServerStartsFirstAndTheTunnelLastAndAFailureIsNamed() {
        Shell shell = new Shell();
        SimulatorsService sims = service(shell, true);
        sims.toggle("Inert");
        sims.start();
        assertEquals(RigBusy.SIMS_LAUNCH, shell.busy().own(), "the launch holds the one flag");
        queued.remove(0).run();

        assertEquals(List.of(SimulatorsService.CARD_SERVER, SimulatorsService.CLOUD_TUNNEL), started);
        assertEquals("Launch finished. Not up: Cloud Tunnel.", sims.message());
        assertEquals("ready", sims.connectorView(SimulatorsService.CARD_SERVER).state());
        assertEquals("failed", sims.connectorView(SimulatorsService.CLOUD_TUNNEL).state());
        assertEquals("", shell.busy().own());
        assertTrue(shell.toastsSince(0).stream().anyMatch(t -> t.message().contains("Cloud Tunnel did not start")));
        assertTrue(sims.logLines(SimulatorsService.LAUNCH_LOG).get(0).startsWith("Launch Simulators: BTT Card Server"));
    }

    @Test
    void aLaunchIsRefusedWhileARunOrAnInstallHoldsTheRig() {
        Shell shell = new Shell();
        SimulatorsService sims = service(shell, false);
        shell.busy().begin(RigBusy.INSTALL);
        sims.start();
        assertEquals("An install is already in progress.", sims.message());
        assertTrue(queued.isEmpty());
        assertEquals("an install", sims.otherBusy());
        sims.stop();
        assertEquals("An install is already in progress.", sims.message());
    }

    @Test
    void aCancelledLaunchStartsNothingMoreAndSaysSo() {
        Shell shell = new Shell();
        SimulatorsService sims = service(shell, false);
        sims.start();
        sims.cancel();
        queued.remove(0).run();
        assertTrue(started.isEmpty(), "no connector starts after Cancel");
        assertEquals("Launch stopped: what it started was stopped again.", sims.message());
        assertEquals("", shell.busy().own());
        assertEquals("No launch in progress.", run(sims::cancel, sims));
    }

    private static String run(Runnable action, SimulatorsService sims) {
        action.run();
        return sims.message();
    }
}
