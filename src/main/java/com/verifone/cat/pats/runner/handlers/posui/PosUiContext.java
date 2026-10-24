package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.handlers.inits.InitsState;
import com.verifone.cat.pats.runner.handlers.sale.TouchPacer;
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
 *
 * <p>Two pieces of run state are shared with other families when the orchestrator wires them: the run's
 * {@link InitsState} (the SLEEP beside an init waits on it, as the inits family does), and the
 * {@link TouchPacer} whose last-touch time the touch's boundary pause reads, so posui and sale make one pause.
 */
public final class PosUiContext {

    private final PosDriver driver;
    private final PosShimClient shim;
    private final Function<String, String> widgetText;
    private final PosUiTimings timings;
    private final Pace pace;
    private final ToIntFunction<CaseRun> printCount;
    private final Session session;
    private final InitsState inits;

    /**
     * @param driver     the POS page's driver
     * @param shim       the pos-shim client
     * @param widgetText the English text of a widget key ({@code EXPRESS_REPRINT}, {@code ENTER_ID}, ...); throws a
     *                   step failure for a key it does not know
     */
    public PosUiContext(PosDriver driver, PosShimClient shim, Function<String, String> widgetText) {
        this(driver, shim, widgetText, PosUiTimings.defaults(), Pace.REAL, run -> 0, new Session(), null);
    }

    private PosUiContext(PosDriver driver, PosShimClient shim, Function<String, String> widgetText,
            PosUiTimings timings, Pace pace, ToIntFunction<CaseRun> printCount, Session session, InitsState inits) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.shim = Objects.requireNonNull(shim, "shim");
        this.widgetText = Objects.requireNonNull(widgetText, "widgetText");
        this.timings = Objects.requireNonNull(timings, "timings");
        this.pace = Objects.requireNonNull(pace, "pace");
        this.printCount = Objects.requireNonNull(printCount, "printCount");
        this.session = session;
        this.inits = inits;
    }

    /** The same context with other timings. */
    public PosUiContext withTimings(PosUiTimings value) {
        return new PosUiContext(driver, shim, widgetText, value, pace, printCount, session, inits);
    }

    /** The same context with another pace (a test's no-op pauses, or a fake clock). */
    public PosUiContext withPace(Pace value) {
        return new PosUiContext(driver, shim, widgetText, timings, value, printCount, session, inits);
    }

    /** The same context with the receipt family's printer count. */
    public PosUiContext withPrintCount(ToIntFunction<CaseRun> value) {
        return new PosUiContext(driver, shim, widgetText, timings, pace, value, session, inits);
    }

    /**
     * The same context with the run's init state, which a SLEEP beside a driver init waits on. Without one, such a SLEEP
     * is carried or slept as any other.
     */
    public PosUiContext withInits(InitsState value) {
        return new PosUiContext(driver, shim, widgetText, timings, pace, printCount, session, value);
    }

    /**
     * The same context sharing {@code pacer}'s last-touch time: the touch's boundary pause and the sale family's pause
     * then read one clock, as the Python's module {@code _last_touch} does. The context's own session is bound to it.
     */
    public PosUiContext withTouchPacer(TouchPacer pacer) {
        session.bind(Objects.requireNonNull(pacer, "pacer"));
        return this;
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

    /** The run's init state, or {@code null} when none is wired. */
    InitsState inits() {
        return inits;
    }

    Session session() {
        return session;
    }

    /**
     * What lasts across the cases of one run: when the POS was last touched, and the messages it has shown it takes a
     * press under. The Python keeps these as module state; here they belong to the run's context. The last-touch time
     * is the bound {@link TouchPacer}'s once one is bound, so the sale family and this one share it.
     */
    static final class Session {
        private final ConcurrentHashMap<List<String>, Boolean> takesPresses = new ConcurrentHashMap<>();
        private volatile OptionalLong lastTouch = OptionalLong.empty();
        private volatile TouchPacer pacer;

        void bind(TouchPacer value) {
            pacer = value;
        }

        OptionalLong lastTouch() {
            TouchPacer bound = pacer;
            return bound != null ? bound.lastTouch() : lastTouch;
        }

        void touched(long nanos) {
            TouchPacer bound = pacer;
            if (bound != null) {
                bound.touchedAt(nanos);
            } else {
                lastTouch = OptionalLong.of(nanos);
            }
        }

        Set<List<String>> takesPresses() {
            return takesPresses.keySet();
        }

        void learn(List<String> message) {
            takesPresses.put(List.copyOf(message), Boolean.TRUE);
        }
    }
}
