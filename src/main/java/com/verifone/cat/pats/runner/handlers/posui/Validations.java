package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.PosText;
import com.verifone.cat.validate.PyRepr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The POS text and overlay checks: {@code WIDGET_VALIDATION} and {@code VALIDATION_BY_TEXT} (a substring of an element,
 * within the wait) and {@code VALIDATE_OVERLAY} (the message on screen, as the register shows it or pushed it over RMI).
 *
 * <p>A failed overlay check is a {@link ValidationFailure}: recorded, and the case carries on. A wait that cannot finish
 * because the POS is held by an OK-only message is a step failure.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Validations {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The poll interval PATS's {@code poll_until} takes when the caller names none (Python's 0.25 s). */
    static final Duration QUARTER_SECOND = Duration.ofMillis(250);

    private final PosUi ui;
    private final Messages messages;

    Validations(PosUi ui, Messages messages) {
        this.ui = ui;
        this.messages = messages;
    }

    /** {@code WIDGET_VALIDATION}: the widget key's English text, in the element. */
    void widget(CaseRun run, RunnableAction action) {
        expectText(run, action, ui.widget(PosUi.require(action, "VALIDATION KEY")));
    }

    /** {@code VALIDATION_BY_TEXT}: {@code VALIDATION TEXT} (with its space) as written. */
    void byText(CaseRun run, RunnableAction action) {
        expectText(run, action, PosUi.require(action, "VALIDATION TEXT"));
    }

    /**
     * {@code textToBePresentInElementLocated}: the element's text contains {@code expected} (a case-sensitive
     * substring, once normalised), within the budget. An OK-only message nothing will press ends the wait early.
     */
    void expectText(CaseRun run, RunnableAction action, String expected) {
        String want = PosText.normalise(expected);
        Locator locator = PosUi.locator(action);
        DialogWatch stuck = new DialogWatch(ui, run);
        BooleanSupplier shows = () -> {
            String text = ui.chosenText(locator);
            if ((text == null ? "\0" : text).contains(want)) {
                return true;
            }
            String reason = stuck.check();
            if (reason != null) {
                throw new PatsStepError(reason + "; it reads " + PyRepr.reprString(String.valueOf(text)));
            }
            return false;
        };
        Duration timeout = PosUi.seconds(PosUi.budget(run, ui.timings().uiWait()));
        if (!ui.pace().poll(shows, timeout, ui.timings().poll())) {
            throw new PatsStepError(action.get("WEB ELEMENT") + " to contain " + PyRepr.reprString(expected)
                    + "; it reads " + PyRepr.reprString(String.valueOf(ui.chosenText(locator))));
        }
    }

    /**
     * {@code VALIDATE_OVERLAY}: each non-blank field equal to the overlay's, trimmed, ignoring case. Soft: no overlay on
     * screen at all is a pass, and the overlay is left up for the next action to deal with.
     *
     * <p>The overlay is polled for up to the overlay window, and a miss is judged by the first read, as PATS's one read
     * would judge it: an overlay that only came up during the wait is not the one the case is about. A nag on top is
     * OK-ed, and what is under it read.
     */
    void validateOverlay(CaseRun run, RunnableAction action) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String key : List.of("TITLE", "LINE1", "LINE2", "LINE3")) {
            String value = action.get(key).strip();
            if (!value.isEmpty()) {
                wanted.put(key, value);
            }
        }
        Watch watch = new Watch(ui.pace().nanoTime());
        BooleanSupplier matches = () -> {
            watch.seen = ui.overlayTexts();
            watch.note("screen", watch.seen);
            if (watch.seen != null && PosDriver.NAG_OVERLAY_TITLES.contains(watch.seen.title().strip().toUpperCase(Locale.ROOT))) {
                // A nag on top is no case's overlay: OK it and read what is under it.
                messages.clearNag();
                watch.seen = ui.overlayTexts();
                watch.note("screen, nag cleared", watch.seen);
            }
            if (!watch.firstTaken) {
                watch.firstTaken = true;
                watch.first = watch.seen;
            }
            if (agrees(wanted, watch.seen)) {
                return true;
            }
            watch.pushed = ui.pushedOverlay(run);
            watch.note("pushed", watch.pushed);
            return agrees(wanted, watch.pushed);
        };
        Duration timeout = PosUi.seconds(PosUi.budget(run, ui.timings().overlayWait()));
        if (ui.pace().poll(matches, timeout, QUARTER_SECOND)) {
            return;
        }
        if (watch.seen == null) {
            LOG.info("{}: no overlay showing; PATS counts that as a pass", run.kase().key());
            return;
        }
        if (watch.first == null) {
            LOG.info("{}: no overlay showing at the first read, as PATS's one read would have found; {} came up during"
                    + " the wait", run.kase().key(), watch.seen.key());
            return;
        }
        String also = watch.pushed == null ? "" : "; pushed over RMI: " + repr(watch.pushed);
        LOG.info("{}: overlays seen while waiting: {}", run.kase().key(), String.join(" | ", watch.timeline));
        throw new ValidationFailure("overlay reads " + repr(watch.seen) + ", expected " + reprWanted(wanted) + also);
    }

    private static boolean agrees(Map<String, String> wanted, PosUi.Shown read) {
        if (read == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            if (!field(read, entry.getKey()).strip().toLowerCase(Locale.ROOT)
                    .equals(entry.getValue().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static String field(PosUi.Shown read, String key) {
        return switch (key) {
            case "TITLE" -> read.title();
            case "LINE1" -> read.line1();
            case "LINE2" -> read.line2();
            default -> read.line3();
        };
    }

    private static String repr(PosUi.Shown read) {
        return "{'TITLE': " + PyRepr.reprString(read.title()) + ", 'LINE1': " + PyRepr.reprString(read.line1())
                + ", 'LINE2': " + PyRepr.reprString(read.line2()) + ", 'LINE3': " + PyRepr.reprString(read.line3())
                + "}";
    }

    private static String reprWanted(Map<String, String> wanted) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            parts.add(PyRepr.reprString(entry.getKey()) + ": " + PyRepr.reprString(entry.getValue()));
        }
        return "{" + String.join(", ", parts) + "}";
    }

    /** What one overlay validation's wait saw: each read, and the first. */
    private static final class Watch {
        private final long started;
        private final List<String> timeline = new ArrayList<>();
        private String lastEntry = "";
        private PosUi.Shown seen;
        private PosUi.Shown first;
        private boolean firstTaken;
        private PosUi.Shown pushed;
        private final PosUi.Shown none = null;

        Watch(long started) {
            this.started = started;
        }

        /** Logs a read the timeline does not already end with. */
        void note(String where, PosUi.Shown read) {
            String entry = where + " " + describe(read);
            if (!entry.equals(lastEntry)) {
                lastEntry = entry;
                timeline.add(String.format(Locale.ROOT, "%.2fs %s", (System.nanoTime() - started) / 1e9, entry));
            }
        }

        private static String describe(PosUi.Shown read) {
            if (read == null) {
                return "{}";
            }
            List<String> parts = new ArrayList<>();
            if (!read.title().isEmpty()) {
                parts.add("'TITLE': " + PyRepr.reprString(read.title()));
            }
            if (!read.line1().isEmpty()) {
                parts.add("'LINE1': " + PyRepr.reprString(read.line1()));
            }
            if (!read.line2().isEmpty()) {
                parts.add("'LINE2': " + PyRepr.reprString(read.line2()));
            }
            if (!read.line3().isEmpty()) {
                parts.add("'LINE3': " + PyRepr.reprString(read.line3()));
            }
            return "{" + String.join(", ", parts) + "}";
        }
    }
}
