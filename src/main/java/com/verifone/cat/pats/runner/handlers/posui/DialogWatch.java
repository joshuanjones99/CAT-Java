package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunnableAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Ends a wait the POS cannot finish: a message whose only button reads OK, up and unchanged for the dead-end window.
 * Such a message holds the sale until it is pressed, and nothing presses it while a case waits. Also ends a wait the
 * pinpad has made impossible: it showed DECLINED and this case does not expect a decline.
 *
 * <p>Called from a wait's own poll, it looks every {@code dialogLook}, so a wait that ends at its first look never
 * pays for one. A message with any other button, or none, is never one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class DialogWatch {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    private final PosUi ui;
    private final CaseRun run;
    private long next;

    DialogWatch(PosUi ui, CaseRun run) {
        this.ui = ui;
        this.run = run;
        this.next = ui.pace().nanoTime() + ui.timings().dialogLook().toNanos();
    }

    /**
     * The reason the wait cannot finish, or null while it still can.
     */
    String check() {
        String declined = unexpectedDecline();
        if (declined != null) {
            LOG.warn("{}: given up: {}", run.kase().key(), declined);
            return declined;
        }
        long now = ui.pace().nanoTime();
        if (now < next) {
            return null;
        }
        next = now + ui.timings().dialogLook().toNanos();
        List<String> shown = okDialog();
        PosUiCaseState state = PosUiCaseState.of(run);
        if (shown == null) {
            state.dialogSeen(null);
            return null;
        }
        PosUiCaseState.DialogSeen seen = state.dialogSeen();
        if (seen == null || !seen.shown().equals(shown)) {
            state.dialogSeen(new PosUiCaseState.DialogSeen(shown, now));
            return null;
        }
        Duration unchanged = Duration.ofNanos(now - seen.sinceNanos());
        if (unchanged.compareTo(ui.timings().dialogDeadEnd()) < 0) {
            return null;
        }
        String reason = "the POS has shown " + shown + " for " + unchanged.toSeconds()
                + "s, waiting for OK, and nothing presses it while the case waits";
        LOG.warn("{}: given up: {}", run.kase().key(), reason);
        state.deadEnd(shown);
        return reason;
    }

    /**
     * Why a wait cannot finish, when a physical pinpad saw the terminal decline this case's payment and the case never
     * waits for DECLINED itself; else null. Only a decline after this case began counts.
     */
    private String unexpectedDecline() {
        if (!run.declares(FixtureName.PINPAD)) {
            return null;
        }
        OptionalLong declinedAt = run.fixture(FixtureName.PINPAD, PinpadView.class).declinedAtNanos();
        if (declinedAt.isEmpty() || declinedAt.getAsLong() < PosUiCaseState.of(run).startedNanos()) {
            return null;
        }
        for (RunnableAction action : run.kase().actions()) {
            if (action.get("DISPLAY_KEY").toUpperCase(Locale.ROOT).contains("DECLIN")
                    || action.get("VALIDATION TEXT").toUpperCase(Locale.ROOT).contains("DECLIN")) {
                return null;
            }
        }
        long ago = ui.pace().nanoTime() - declinedAt.getAsLong();
        return "the pinpad showed DECLINED " + Duration.ofNanos(ago).toSeconds()
                + "s ago, and this case does not expect a decline: the sale cannot close";
    }

    private List<String> okDialog() {
        return ui.okDialog();
    }
}
