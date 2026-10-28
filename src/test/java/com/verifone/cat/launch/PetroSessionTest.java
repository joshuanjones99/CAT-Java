package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.devices.forecourt.PetroDcr;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 5's Petro session against loopback fakes of the suite's sockets ({@link FakePetroSuite}) and a fake DCR link:
 * the listeners started for what the cases declare, the pump teardown after a case that failed in its actions (and not
 * after one that failed only on its validations), and the DCR link's one recovery per session. Evidence about this
 * repository; no simulator is touched.
 */
class PetroSessionTest {

    private static final Set<FixtureName> FUEL_AND_POS = EnumSet.of(FixtureName.FUEL_PUMPS, FixtureName.POS);
    private static final Set<FixtureName> DCR = EnumSet.of(FixtureName.DCR);

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    private Rig rig(String yaml) throws IOException {
        Files.writeString(dir.resolve("bench.yaml"), yaml);
        return RigLoader.loadResolved("bench", dir).rig();
    }

    private Rig petroRig() throws IOException {
        return rig(RunServicesWiringTest.RIG);
    }

    private PetroSession.Log log() {
        return (level, message) -> lines.add(level + " " + message);
    }

    /** Devices over a pump fake and a DCR fake, with a link read and a recovery the test sets. */
    private class Fake implements PetroSession.Devices {
        final FakePetroSuite pumpSuite = FakePetroSuite.pumps();
        final FakePetroSuite dcrSuite = FakePetroSuite.dcr();
        final int pumpPort = FakePetroSuite.freePort();
        final int dcrPort = FakePetroSuite.freePort();
        final AtomicInteger pumpStarts = new AtomicInteger();
        final AtomicInteger dcrStarts = new AtomicInteger();
        final AtomicInteger recoveries = new AtomicInteger();
        final List<String> faults = new ArrayList<>();
        final List<Optional<String>> recoveryAnswers = new ArrayList<>();
        RuntimeException pumpStartFailure;

        Fake() {
            open.add(pumpSuite);
            open.add(dcrSuite);
        }

        @Override
        public PetroPumps startPumps() {
            pumpStarts.incrementAndGet();
            if (pumpStartFailure != null) {
                throw pumpStartFailure;
            }
            return pumpSuite.startedPumps(pumpPort);
        }

        @Override
        public PetroDcr startDcr() {
            dcrStarts.incrementAndGet();
            return dcrSuite.startedDcr(dcrPort);
        }

        @Override
        public String dcrLinkFault(int dcr) {
            return faults.isEmpty() ? null : faults.remove(0);
        }

        @Override
        public Optional<String> recoverDcrLink(int dcr, PetroSession.Log log) {
            recoveries.incrementAndGet();
            return recoveryAnswers.isEmpty() ? Optional.empty() : recoveryAnswers.remove(0);
        }
    }

    /** A due-fuel settle that records the pumps it was asked about. */
    private static final class Settle implements PetroSession.DueFuel {
        final List<Integer> settled = new ArrayList<>();
        final List<Integer> reservedAsked = new ArrayList<>();

        @Override
        public List<String> settle(int pump) {
            settled.add(pump);
            return List.of("+10.00");
        }

        @Override
        public Optional<String> reservedIfIdle(int pump) {
            reservedAsked.add(pump);
            return Optional.of("$5.00 PREPAY");
        }
    }

    private PetroSession start(Fake fake, Set<FixtureName> wanted) throws IOException {
        PetroSession session = PetroSession.start(petroRig(), false, wanted, fake, log());
        open.add(session);
        return session;
    }

    @Test
    void theListenersStartOnlyForWhatTheCasesDeclare() throws IOException {
        Fake fake = new Fake();
        PetroSession session = start(fake, EnumSet.of(FixtureName.FUEL_PUMPS, FixtureName.POS));

        assertEquals(1, fake.pumpStarts.get());
        assertEquals(0, fake.dcrStarts.get(), "no case declares dcr");
        assertNotNull(session.fuelPumps());
        assertNull(session.dcr());
        fake.pumpSuite.awaitRequests(1); // the login
    }

    @Test
    void noListenerStartsUnderTheFakePosOrOnARigWithoutPetro() throws IOException {
        Fake fake = new Fake();
        PetroSession fakePos = PetroSession.start(petroRig(), true, EnumSet.allOf(FixtureName.class), fake, log());
        PetroSession noPetro = PetroSession.start(rig(RunServicesWiringTest.RIG_NO_FORECOURT), false,
                EnumSet.allOf(FixtureName.class), fake, log());

        assertEquals(0, fake.pumpStarts.get() + fake.dcrStarts.get());
        assertThrows(DeviceUnavailable.class, () -> fakePos.beginCase(DCR));
        DeviceUnavailable unavailable = assertThrows(DeviceUnavailable.class,
                () -> noPetro.beginCase(EnumSet.of(FixtureName.FUEL_PUMPS)));
        assertTrue(unavailable.getMessage().contains("forecourt: petro"), unavailable.getMessage());
        noPetro.beginCase(EnumSet.of(FixtureName.POS)); // a case with no Petro fixture is not stopped
    }

    @Test
    void aFailedFuelCaseClearsThePumpsItTouchedAndSettlesTheirDueFills() throws IOException {
        Fake fake = new Fake();
        PetroSession session = start(fake, FUEL_AND_POS);
        fake.pumpSuite.awaitRequests(1);
        FakePetroSuite.pushPumpState(fake.pumpPort, 3, "BUSYON");

        session.beginCase(FUEL_AND_POS);
        waitForState(session, 3, "BUSYON");
        // The suite answers the hang-up with IDLEOFF, as Petro does once the sale posts.
        Thread pusher = new Thread(() -> {
            fake.pumpSuite.awaitRequests(2);
            FakePetroSuite.pushPumpState(fake.pumpPort, 3, "IDLEOFF");
        });
        pusher.setDaemon(true);
        pusher.start();
        Settle settle = new Settle();
        session.afterCase(FUEL_AND_POS, true, settle);

        assertEquals(2, fake.pumpSuite.received().size(), "the login, then pump 3's hang-up");
        assertEquals(List.of(3), settle.settled);
        assertEquals(List.of(3), settle.reservedAsked);
        assertTrue(lines.contains("WARNING pump 3: cleared from BUSYON after the failure"), lines.toString());
        assertTrue(lines.contains("WARNING pump 3: settled due fill(s) [+10.00] after the failure"), lines.toString());
        assertTrue(lines.contains("WARNING pump 3: $5.00 PREPAY reserved after the failure; left for a person"),
                lines.toString());
    }

    @Test
    void aCaseThatFailedOnlyOnItsValidationsLeavesThePumpsAsPatsWould() throws IOException {
        Fake fake = new Fake();
        PetroSession session = start(fake, FUEL_AND_POS);
        fake.pumpSuite.awaitRequests(1);
        FakePetroSuite.pushPumpState(fake.pumpPort, 3, "BUSYON");
        session.beginCase(FUEL_AND_POS);
        waitForState(session, 3, "BUSYON");

        Settle settle = new Settle();
        session.afterCase(FUEL_AND_POS, false, settle);

        assertEquals(1, fake.pumpSuite.received().size(), "nothing after the login");
        assertTrue(settle.settled.isEmpty());
    }

    @Test
    void theTouchedPumpsAreThisCasesOnlyAndACaseWithoutPosIsClearedNotSettled() throws IOException {
        Fake fake = new Fake();
        PetroSession session = start(fake, FUEL_AND_POS);
        fake.pumpSuite.awaitRequests(1);
        FakePetroSuite.pushPumpState(fake.pumpPort, 3, "BUSYON");
        session.beginCase(FUEL_AND_POS);
        waitForState(session, 3, "BUSYON");

        session.beginCase(EnumSet.of(FixtureName.FUEL_PUMPS)); // the next case touches nothing
        session.afterCase(EnumSet.of(FixtureName.FUEL_PUMPS), true, null);

        assertEquals(1, fake.pumpSuite.received().size(), "pump 3 was the last case's");
        assertTrue(session.fuelPumps().touched().isEmpty());
    }

    @Test
    void aPumpListenerThatDidNotStartFailsTheFuelCasesSetupOnly() throws IOException {
        Fake fake = new Fake();
        fake.pumpStartFailure = new DeviceUnreachable("the Petro suite's port 1072", "already held");
        PetroSession session = start(fake, FUEL_AND_POS);

        assertNull(session.fuelPumps());
        DeviceUnreachable failed = assertThrows(DeviceUnreachable.class, () -> session.beginCase(FUEL_AND_POS));
        assertTrue(failed.getMessage().contains("the session's listener did not start"), failed.getMessage());
        assertTrue(failed.getMessage().contains("already held"), failed.getMessage());
        session.beginCase(EnumSet.of(FixtureName.POS));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("WARNING pumps: not listening from session start")),
                lines.toString());
    }

    @Test
    void aDcrLinkFaultIsRecoveredOnceAndThenEveryDcrCaseFailsAtOnce() throws IOException {
        Fake fake = new Fake();
        fake.faults.addAll(List.of("not connected to the Commander", "not connected to the Commander"));
        fake.recoveryAnswers.add(Optional.of("DCR 1: the Commander sent it no display"));
        PetroSession session = start(fake, DCR);
        assertNotNull(session.dcr());

        DeviceUnreachable first = assertThrows(DeviceUnreachable.class, () -> session.beginCase(DCR));
        DeviceUnreachable second = assertThrows(DeviceUnreachable.class, () -> session.beginCase(DCR));

        assertEquals(1, fake.recoveries.get(), "one recovery per session");
        assertTrue(first.getMessage().contains("did not bring it back (DCR 1: the Commander sent it no display)"),
                first.getMessage());
        assertTrue(second.getMessage().contains("the session's DCR re-init did not bring it back"),
                second.getMessage());
    }

    @Test
    void aRecoveryThatWorkedDoesNotStopALaterDropBeingRecovered() throws IOException {
        Fake fake = new Fake();
        fake.faults.add("not connected");
        PetroSession session = start(fake, DCR);

        session.beginCase(DCR); // recovered
        session.beginCase(DCR); // no fault
        fake.faults.add("not connected again");
        session.beginCase(DCR); // recovered again

        assertEquals(2, fake.recoveries.get());
        assertTrue(lines.contains("WARNING DCR 1: connected again after the recovery"), lines.toString());
    }

    @Test
    void aRecoveryThatThrowsIsAFailedRecovery() throws IOException {
        Fake fake = new Fake() {
            @Override
            public Optional<String> recoverDcrLink(int dcr, PetroSession.Log log) {
                recoveries.incrementAndGet();
                throw new IllegalStateException("ssh refused");
            }
        };
        fake.faults.add("not connected");
        PetroSession session = start(fake, DCR);

        DeviceUnreachable failed = assertThrows(DeviceUnreachable.class, () -> session.beginCase(DCR));
        assertTrue(failed.getMessage().contains("IllegalStateException: ssh refused"), failed.getMessage());
    }

    /** Reads the pump's state through the session (which marks it touched) until the suite's push has arrived. */
    private static void waitForState(PetroSession session, int pump, String state) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!Optional.of(state).equals(session.fuelPumps().state(pump))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("pump " + pump + " never reported " + state);
            }
            Thread.onSpinWait();
        }
    }
}
