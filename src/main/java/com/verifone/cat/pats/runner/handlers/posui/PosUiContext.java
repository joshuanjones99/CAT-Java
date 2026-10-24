package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.posshim.PosShimClient;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * What the POS UI family reaches: the POS page's driver, the pos-shim client (which answers the legacy RMI reads and
 * presses the named keys), the widget texts the POS shows in English, the timings and the pace.
 *
 * <p>The fuel pumps and the pinpad are the case's own fixtures, fetched through {@link CaseRun#fixture} only when an
 * action names them. The print count is the receipt family's; the default is zero until it is wired.
 */
public final class PosUiContext {

    private final PosDriver driver;
    private final PosShimClient shim;
    private final Function<String, String> widgetText;
    private final PosUiTimings timings;
    private final Pace pace;
    private final ToIntFunction<CaseRun> printCount;
    private final Session session;

    /**
     * @param driver     the POS page's driver
     * @param shim       the pos-shim client
     * @param widgetText the English text of a widget key ({@code EXPRESS_REPRINT}, {@code ENTER_ID}, ...); throws a
     *                   step failure for a key it does not know
     */
    public PosUiContext(PosDriver driver, PosShimClient shim, Function<String, String> widgetText) {
        this(driver, shim, widgetText, PosUiTimings.defaults(), Pace.REAL, run -> 0, new Session());
    }

    private PosUiContext(PosDriver driver, PosShimClient shim, Function<String, String> widgetText,
            PosUiTimings timings, Pace pace, ToIntFunction<CaseRun> printCount, Session session) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.shim = Objects.requireNonNull(shim, "shim");
        this.widgetText = Objects.requireNonNull(widgetText, "widgetText");
        this.timings = Objects.requireNonNull(timings, "timings");
        this.pace = Objects.requireNonNull(pace, "pace");
        this.printCount = Objects.requireNonNull(printCount, "printCount");
        this.session = session;
    }

    /** The same context with other timings. */
    public PosUiContext withTimings(PosUiTimings value) {
        return new PosUiContext(driver, shim, widgetText, value, pace, printCount, session);
    }

    /** The same context with another pace (a test's no-op pauses, or a fake clock). */
    public PosUiContext withPace(Pace value) {
        return new PosUiContext(driver, shim, widgetText, timings, value, printCount, session);
    }

    /** The same context with the receipt family's printer count. */
    public PosUiContext withPrintCount(ToIntFunction<CaseRun> value) {
        return new PosUiContext(driver, shim, widgetText, timings, pace, value, session);
    }

    public PosDriver driver() {
        return driver;
    }

    public PosShimClient shim() {
        return shim;
    }

    public Function<String, String> widgetText() {
        return widgetText;
    }

    public PosUiTimings timings() {
        return timings;
    }

    public Pace pace() {
        return pace;
    }

    public ToIntFunction<CaseRun> printCount() {
        return printCount;
    }

    Session session() {
        return session;
    }

    /**
     * What lasts across the cases of one run: when the POS was last touched, and the messages it has shown it takes a
     * press under. The Python keeps these as module state; here they belong to the run's context.
     */
    static final class Session {
        private final ConcurrentHashMap<List<String>, Boolean> takesPresses = new ConcurrentHashMap<>();
        private volatile OptionalLong lastTouch = OptionalLong.empty();

        OptionalLong lastTouch() {
            return lastTouch;
        }

        void touched(long nanos) {
            lastTouch = OptionalLong.of(nanos);
        }

        Set<List<String>> takesPresses() {
            return takesPresses.keySet();
        }

        void learn(List<String> message) {
            takesPresses.put(List.copyOf(message), Boolean.TRUE);
        }
    }
}
