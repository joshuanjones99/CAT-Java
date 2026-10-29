package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.devices.legacyrmi.LegacyRmi;
import com.verifone.cat.pats.runner.handlers.sale.SaleScreen;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link RegisterPos}: the report actions' keys through the pos-shim ({@link LegacyRmi}, the Python's
 * {@code run.rmi}) and their reads off the POS screen ({@link SaleScreen}, the Python's {@code run.pos.driver} and the
 * {@code pos_ui} helpers). Each method is the Python call {@code configuration.py} makes:
 * <ul>
 *   <li>the keys: {@code rmi.exit_key}, {@code rmi.pending_enter}, {@code rmi.press_enter()} (no data, so a bare
 *       ENTER), {@code rmi.print_report}, {@code rmi.void_ticket};</li>
 *   <li>{@link #screenText} and {@link #settle}: {@code driver.screen_text()} and {@code pos_ui.settle(driver, before)},
 *       capped at {@code TOUCH_SETTLE};</li>
 *   <li>{@link #sweep}: {@code CaseRun.sweep}, the driver's {@code clear_blocking_overlays};</li>
 *   <li>{@link #ready}: {@code pos_ui._ready}, an express button reading the {@code EXPRESS_REPRINT},
 *       {@code EXPRESS_RECALL} or {@code EXPRESS_CSRFUNC} widget text;</li>
 *   <li>{@link #functionLabels}: {@code configuration._function_ready}'s {@code EXPRESS_RECALL},
 *       {@code EXPRESS_CSRFUNC} and {@code EXPRESS_PRICE_CHECK};</li>
 *   <li>{@link #overlay}: {@code pos_ui._overlay_texts}; {@link #closingCashiersLine}: the {@code CLOSE_CASHIERS}
 *       widget text;</li>
 *   <li>{@link #overlayPause}: {@code sale._overlay_pause}, {@code OVERLAY_SEEN_PAUSE} when a message is up.</li>
 * </ul>
 *
 * <p>The printed report itself is not read here: as in the Python, {@code REPORT_PRINT} only marks the case, and the
 * case's {@code VALIDATE_RECEIPT} reads the run's receipt source (the validation family's).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): fake-tested only.
 */
public final class LegacyRegister implements RegisterPos {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    private final LegacyRmi rmi;
    private final SaleScreen screen;
    private final Function<String, String> widgetText;
    private final Duration settleCap;
    private final Duration overlaySeenPause;
    private final Consumer<Duration> pause;

    /**
     * @param rmi              the register's key path, its before-action the driver's overlay clear
     * @param screen           the POS screen's reads
     * @param widgetText       the English text of a widget key
     * @param settleCap        {@code TOUCH_SETTLE}: the longest a key waits for the screen to settle
     * @param overlaySeenPause {@code OVERLAY_SEEN_PAUSE}: the pause before a key when a message is up
     * @param pause            the bounded pause
     */
    public LegacyRegister(LegacyRmi rmi, SaleScreen screen, Function<String, String> widgetText, Duration settleCap,
            Duration overlaySeenPause, Consumer<Duration> pause) {
        this.rmi = Objects.requireNonNull(rmi, "rmi");
        this.screen = Objects.requireNonNull(screen, "screen");
        this.widgetText = Objects.requireNonNull(widgetText, "widgetText");
        this.settleCap = Objects.requireNonNull(settleCap, "settleCap");
        this.overlaySeenPause = Objects.requireNonNull(overlaySeenPause, "overlaySeenPause");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    @Override
    public void exitKey() {
        rmi.exitKey();
    }

    @Override
    public void pendingEnter(String value) {
        rmi.pendingEnter(value);
    }

    @Override
    public void pressEnter() {
        rmi.pressEnter(null);
    }

    @Override
    public void printReport() {
        rmi.printReport();
    }

    @Override
    public void voidTicket() {
        rmi.voidTicket();
    }

    @Override
    public String screenText() {
        return screen.screenText();
    }

    @Override
    public void settle(String before) {
        screen.settled(before, settleCap);
    }

    @Override
    public void sweep() {
        screen.clearBlockingOverlays();
    }

    @Override
    public boolean ready() {
        Set<String> wanted = widgets("EXPRESS_REPRINT", "EXPRESS_RECALL", "EXPRESS_CSRFUNC");
        return screen.expressLabels().stream().anyMatch(wanted::contains);
    }

    @Override
    public List<String> expressLabels() {
        return screen.expressLabels();
    }

    @Override
    public Set<String> functionLabels() {
        return widgets("EXPRESS_RECALL", "EXPRESS_CSRFUNC", "EXPRESS_PRICE_CHECK");
    }

    @Override
    public Optional<Map<String, String>> overlay() {
        return Optional.ofNullable(screen.overlay());
    }

    @Override
    public String closingCashiersLine() {
        return widgetText.apply("CLOSE_CASHIERS");
    }

    @Override
    public void overlayPause() {
        Map<String, String> shown = screen.overlay();
        if (shown != null) {
            LOG.info("a message is up before the key: {}", shown);
            pause.accept(overlaySeenPause);
        }
    }

    private Set<String> widgets(String... keys) {
        Set<String> texts = new java.util.LinkedHashSet<>();
        for (String key : keys) {
            texts.add(widgetText.apply(key));
        }
        return texts;
    }
}
