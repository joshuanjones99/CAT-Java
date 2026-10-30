package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code eps_reachable}'s escalation through the context's reprovision hook (the Python's {@code rig.reprovision}):
 * none wired names the remedy; one wired is called and its answer is the repair's. Fakes only: nothing provisions.
 */
class EpsEscalationTest {

    private static RigContext context() {
        Surfaces.EpsProbe eps = new Surfaces.EpsProbe() {
            @Override
            public boolean reachable(Duration timeout) {
                return false;
            }

            @Override
            public String describe() {
                return "the Buypass UMF port";
            }
        };
        return new RigContext("bench",
                Surfaces.unavailable(Surfaces.PosStatus.class, "pos status", "a test"),
                Surfaces.unavailable(Surfaces.PosControl.class, "pos control", "a test"),
                eps,
                Surfaces.unavailable(Surfaces.ConfigStatus.class, "config status", "a test"),
                Surfaces.unavailable(Surfaces.ConfigControl.class, "config control", "a test"),
                new Expectations());
    }

    @Test
    void withNoHookTheRepairNamesTheRemedy() {
        Result result = new ServiceChecks.EpsReachable().repair(context());
        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("provision --rig X --force"), result.detail());
    }

    @Test
    void aWiredHookIsTheEscalationAndItsAnswerIsTheRepairs() {
        List<String> calls = new ArrayList<>();
        RigContext rig = context();
        rig.setReprovision(() -> {
            calls.add("provision --force");
            return true;
        });
        Result repaired = new ServiceChecks.EpsReachable().repair(rig);
        assertEquals(Outcome.REPAIRED, repaired.outcome());
        assertEquals("escalated to provision --force", repaired.detail());

        rig.setReprovision(() -> false);
        assertEquals("provision --force did not restore EPS", new ServiceChecks.EpsReachable().repair(rig).detail());
        assertEquals(List.of("provision --force"), calls);

        assertThrows(MutationInVerifyError.class, () -> rig.readOnly().reprovision(), "a control surface");
    }
}
