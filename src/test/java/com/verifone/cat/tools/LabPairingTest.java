package com.verifone.cat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.verifone.cat.commander.Say;

/**
 * {@link LabPairing}'s order with no rig: the lab registers that answer DevTools are paired, the rest said and left
 * out, then each paired register gets its pos-shim pushed again. Over fake seams; nothing here reaches a register or
 * the Commander. Evidence about this repository only.
 */
class LabPairingTest {

    private static final RegisterTarget ONE = new RegisterTarget("Reg101", "one.invalid", 8801, 9222);
    private static final RegisterTarget TWO = new RegisterTarget("Reg102", "two.invalid", 8801, 9222);

    /** Records each call; {@link #answering} are the registers with DevTools, at port 9220. */
    static final class Fake implements LabPairing.Seams {
        final List<String> calls = new ArrayList<>();
        List<RegisterTarget> answering = List.of(ONE);
        boolean pairs = true;
        boolean pushes = true;

        @Override
        public Optional<String> devTools(RegisterTarget register) {
            return answering.contains(register) ? Optional.of(register.cdpUrl(9220)) : Optional.empty();
        }

        @Override
        public boolean pair(Map<RegisterTarget, String> registers, Say say) {
            calls.add("pair " + registers);
            return pairs;
        }

        @Override
        public boolean pushShim(RegisterTarget register, Say say) {
            calls.add("push " + register.id());
            return pushes;
        }
    }

    @Test
    void theRegistersThatAnswerArePairedThenGetTheirShimBack() {
        Fake fake = new Fake();
        Say.Recorded say = new Say.Recorded();

        assertTrue(LabPairing.pair(List.of(ONE, TWO), fake, say));

        assertEquals(List.of("pair {" + ONE + "=http://one.invalid:9220}", "push Reg101"), fake.calls);
        assertTrue(say.outLines().stream().anyMatch(l -> l.startsWith("Reg102 (two.invalid): no DevTools endpoint "
                + "answering on [9222, 19220, 9220]; not paired")), say.outLines().toString());
    }

    @Test
    void noRegisterAnsweringPairsNothing() {
        Fake fake = new Fake();
        fake.answering = List.of();
        Say.Recorded say = new Say.Recorded();

        assertFalse(LabPairing.pair(List.of(ONE, TWO), fake, say));

        assertEquals(List.of(), fake.calls);
        assertEquals("no lab register answers DevTools, so none can be paired",
                say.errLines().get(say.errLines().size() - 1));
    }

    @Test
    void aFailedPairingPushesNoShim() {
        Fake fake = new Fake();
        fake.pairs = false;

        assertFalse(LabPairing.pair(List.of(ONE, TWO), fake, new Say.Recorded()));

        assertEquals(1, fake.calls.size(), fake.calls.toString());
    }

    @Test
    void aFailedShimPushAfterPairingIsAFailedTool() {
        Fake fake = new Fake();
        fake.answering = List.of(ONE, TWO);
        fake.pushes = false;

        assertFalse(LabPairing.pair(List.of(ONE, TWO), fake, new Say.Recorded()));

        // Every paired register is still pushed to, so one failure does not leave the other without its shim.
        assertEquals(List.of("push Reg101", "push Reg102"), fake.calls.subList(1, 3));
    }
}
