package com.verifone.cat.launch;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.vats.PhysicalPinpad;
import com.verifone.cat.devices.vats.VatsPinpad;
import com.verifone.cat.devices.vats.VatsSession;
import com.verifone.cat.pos.pinpad.PinpadDisplay;
import com.verifone.cat.rig.Rig;

/**
 * Step 5's pinpad on a {@code pinpad: vats} rig: CAT's own VATS connector, opened at first use and held to run end. A
 * port of the Python CAT's {@code petrotest.devices.plugin._vats_session} and the {@code pinpad} fixture's VATS
 * branch.
 *
 * <p>The first pinpad call opens a {@link VatsSession} (BTT's card server adopted from step 3, or started; Verifone's
 * KM4 relay; CAT's agent), which holds the terminal's one VATS connection for the rest of the run, as the ViperPay
 * listener is held. A session that fails to open is remembered, not retried: every pinpad call then fails with
 * {@code pinpad: the VATS session did not start: <cause>}, so the pinpad cases fail alone, with their cause, as the
 * Python's fixture yields the exception once per session.
 *
 * <p>While open, {@link #current()} is the run's {@link VatsPinpad}: what recovery's VOID rung ends the terminal's
 * payment through ({@link VatsRecovery}), the Python's {@code VATS_SESSION} stash. Recovery never opens a session.
 *
 * <p>Deviation: the session's opening lines (BTT's route, the terminal's model) are logged at INFO under this class's
 * logger; the Python's fixture passes no {@code log} and drops them.
 *
 * <p>Hooks (BrowserConnector, owned by launchcore): in {@code rig(base)}, after the ViperPay pinpad,
 * {@code if (pinpad == null && rig.pinpad() == Rig.Pinpad.VATS) { vats = VatsRunPinpad.forRig(rig, System.getenv());
 * pinpad = vats; }}; in {@code close()}, {@code quietly("the VATS session", () -> { if (vats != null) { vats.close();
 * vats = null; } });}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class VatsRunPinpad implements PhysicalPinpad, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VatsRunPinpad.class);

    /** The run's open session's pinpad, or null. */
    private static final AtomicReference<VatsPinpad> CURRENT = new AtomicReference<>();

    private final Supplier<VatsSession> opener;
    private VatsSession session;
    private RuntimeException failure;

    /** @param opener opens the session; called once, at the first pinpad call */
    public VatsRunPinpad(Supplier<VatsSession> opener) {
        this.opener = opener;
    }

    /** The rig's session on this controller, its logs in {@code <results root>/vats}. Building it opens nothing. */
    public static VatsRunPinpad forRig(Rig rig, Map<String, String> env) {
        return new VatsRunPinpad(() -> VatsSession.open(rig, VatsSession.workdir(env), LOG::info,
                VatsSession.Environment.forThisMachine(env)));
    }

    /** The run's {@link VatsPinpad} while its session is open, else null: recovery's view (never opens one). */
    public static VatsPinpad current() {
        return CURRENT.get();
    }

    /** The session's pinpad, opening the session the first time. */
    private synchronized VatsPinpad pinpad() {
        if (session != null) {
            return session.pinpad();
        }
        if (failure == null) {
            try {
                session = opener.get();
                CURRENT.set(session.pinpad());
                return session.pinpad();
            } catch (RuntimeException e) {
                failure = e;
                LOG.warn("pinpad: the VATS session did not start: {}", e.getMessage());
            }
        }
        throw new DeviceError("pinpad: the VATS session did not start: " + failure.getMessage(), failure);
    }

    private synchronized VatsPinpad openOrNull() {
        return session == null ? null : session.pinpad();
    }

    @Override
    public String id() {
        return "1";
    }

    @Override
    public PinpadDisplay display() {
        return pinpad().display();
    }

    @Override
    public String atPrompt(String prompt, Duration timeout) {
        return pinpad().atPrompt(prompt, timeout);
    }

    @Override
    public String expectPromptCleared(String prompt, Duration timeout) {
        return pinpad().expectPromptCleared(prompt, timeout);
    }

    @Override
    public String insertCard(String label, String waitForPrompt, Duration timeout) {
        return pinpad().insertCard(label, waitForPrompt, timeout);
    }

    @Override
    public String insertCard(String label, String image, String waitForPrompt, Duration timeout) {
        return pinpad().insertCard(label, image, waitForPrompt, timeout);
    }

    @Override
    public String swipeCard(String label, String waitForPrompt, Duration timeout) {
        return pinpad().swipeCard(label, waitForPrompt, timeout);
    }

    @Override
    public String tapCard(String label, String waitForPrompt, Duration timeout) {
        return pinpad().tapCard(label, waitForPrompt, timeout);
    }

    @Override
    public void removeCard() {
        pinpad().removeCard();
    }

    @Override
    public String key(String key, String waitForPrompt, Duration timeout) {
        return pinpad().key(key, waitForPrompt, timeout);
    }

    @Override
    public String decimal(BigDecimal value, String waitForPrompt, Duration timeout) {
        return pinpad().decimal(value, waitForPrompt, timeout);
    }

    @Override
    public String decimalText(String text, String waitForPrompt, Duration timeout) {
        return pinpad().decimalText(text, waitForPrompt, timeout);
    }

    @Override
    public String enterDigits(String digits, String waitForPrompt, Duration timeout) {
        return pinpad().enterDigits(digits, waitForPrompt, timeout);
    }

    @Override
    public boolean settle(Duration budget) {
        return pinpad().settle(budget);
    }

    /** Never opens the session: a session not open has seen no decline. */
    @Override
    public OptionalLong declinedAtNanos() {
        VatsPinpad open = openOrNull();
        return open == null ? OptionalLong.empty() : open.declinedAtNanos();
    }

    /** Never opens the session: one not open holds no payment to end. */
    @Override
    public List<String> abortPayment() {
        VatsPinpad open = openOrNull();
        return open == null ? List.of() : open.abortPayment();
    }

    /** Closes the session if it opened; {@link #current()} is null after. */
    @Override
    public synchronized void close() {
        VatsSession open = session;
        session = null;
        if (open != null) {
            CURRENT.compareAndSet(open.pinpad(), null);
            open.close();
        }
    }
}
