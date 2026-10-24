package com.verifone.cat.pos.driver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.verifone.cat.pos.BuildVariant;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.receipt.DomReceiptCapture.DomElement;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Durations;

/**
 * A bounded, frame-aware handle on one Topaz register's Chrome. Port of the Python
 * {@code petrotest.pos.driver.PosDriver} (DESIGN.md 5.2, 6.3, 8).
 *
 * <p>Everything above this takes {@link Locator} values from the generated catalogue; everything
 * below is Playwright. No element ID and no XPath appears in this file: each selector comes from
 * {@link Locator#selector()} or {@link Locator#prefixSelector()}, and the in-page scripts take
 * IDs as arguments.
 *
 * <p>Every wait is bounded. The default budget is the rig's {@code timeouts.default_wait}; a
 * zero or negative override is refused, because it would mean "wait forever" (DESIGN.md 6.3).
 *
 * <p>Not ported in this slice: the Python's {@code fake}/{@code plugin} modules and the
 * {@code clickable}-driven page objects (later slices). Ported: connect, resolve, frames, tap,
 * press, the overlay watch, the reads, the one-look document scripts, and rung-0 capture.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's Phase 0 caveats (which page is the POS,
 * whether DEVICE_MONITORING is a DOM iframe, screenshot under the real Chromium) carry over
 * unchanged. Tests drive a local Chromium only.
 */
public final class PosDriver implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PosDriver.class);

    /** How long {@link #tapWithin} keeps trying an ID that was visible but gone at the click. */
    public static final Duration ID_REDRAW_WAIT = Duration.ofSeconds(2);

    /** The POS renders inside this one nested frame on the register (DESIGN.md 5.2). */
    public static final String DEFAULT_NESTED_FRAME = "SecondaryWebAppView_HostedApp_Frame";

    /** {@code SaleStateContext.busyWndw}: a buttonless overlay while sales functions initialise. */
    public static final String BUSY_OVERLAY_TITLE = "Sales functions initializing";

    /** OK-only nags that belong to no test. */
    public static final List<String> NAG_OVERLAY_TITLES =
            List.of("SYSTEM NOT REGISTERED", "NOT ONBOARDED", "EXPIRING TODAY");

    /** How long a nag's title may show before its OK button does. */
    public static final Duration NAG_BUTTON_GRACE = Duration.ofSeconds(5);

    /** How long a nag may stay up after its OK was first pressed before it counts as orphaned. */
    public static final Duration NAG_ORPHAN = Duration.ofSeconds(5);

    /** OK presses on one nag before it is left alone, never removed. */
    public static final int NAG_PRESS_LIMIT = 3;

    /** How long {@link #press} waits for a visible match before pressing a hidden one. */
    public static final Duration HIDDEN_PRESS_GRACE = Duration.ofSeconds(2);

    /** How long the busy window may stay up. Unmeasured, as in the Python. */
    public static final Duration OVERLAY_SETTLE = Duration.ofSeconds(120);

    /** How often {@link #settleScreen} samples the screen. */
    public static final Duration SETTLE_SAMPLE = Duration.ofMillis(50);

    /** Navigation gets a little more room than an element wait; still bounded (DESIGN.md 6.3). */
    static final Duration NAVIGATION_GRACE = Duration.ofSeconds(1);

    /** Default poll gap, as the Python's {@code poll_until}. */
    static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** The UI's own frame inside the app-selector page. */
    static final String POS_UI_FRAME = "/posuiapp/";

    /** Clicks the first element with one of the IDs, in document order (the {@code tap} path). */
    static final String CLICK_BY_ID = """
            (ids) => { for (const id of ids) { const el = document.getElementById(id);
            if (el) { el.click(); return true; } } return false; }
            """;

    private final Page page;
    private final Timeouts timeouts;
    private final String buildVersion;
    private final ArtifactSink artifacts;
    private final String registerId;
    private final boolean settleOverlays;
    private final List<Runnable> owned = new ArrayList<>();
    private boolean tracing;
    /** Set once a look found the nested frame attached but not readable from the page. */
    private boolean splitFrames;

    private PosDriver(Builder b) {
        this.page = b.page;
        this.timeouts = b.timeouts;
        this.buildVersion = b.buildVersion;
        this.artifacts = b.artifacts;
        this.registerId = b.registerId;
        this.settleOverlays = b.settleOverlays;
    }

    /** A driver over an existing page. Tests use this against a local page. */
    public static Builder builder(Page page) {
        return new Builder(page);
    }

    /** Fluent construction, so a caller cannot leave out the bounded-wait budget by accident. */
    public static final class Builder {

        private final Page page;
        private Timeouts timeouts = Timeouts.defaults();
        private String buildVersion;
        private ArtifactSink artifacts;
        private String registerId = "";
        private boolean settleOverlays = true;

        private Builder(Page page) {
            if (page == null) {
                throw new IllegalArgumentException("a page is required");
            }
            this.page = page;
        }

        public Builder timeouts(Timeouts value) {
            this.timeouts = value == null ? Timeouts.defaults() : value;
            return this;
        }

        /** The rig's {@code build.expected_version}; resolves build-variant locators (DESIGN.md 5.2). */
        public Builder buildVersion(String value) {
            this.buildVersion = value;
            return this;
        }

        public Builder artifacts(ArtifactSink value) {
            this.artifacts = value;
            return this;
        }

        public Builder registerId(String value) {
            this.registerId = value == null ? "" : value;
            return this;
        }

        /** Off only for a fake page with no overlays to clear. */
        public Builder settleOverlays(boolean value) {
            this.settleOverlays = value;
            return this;
        }

        public PosDriver build() {
            return new PosDriver(this);
        }
    }

    /** Attach to a register's Topaz Chrome over CDP, as the Python {@code connect_pos}. */
    public static PosDriver connect(RegisterEndpoint endpoint, Timeouts timeouts, String buildVersion,
            ArtifactSink artifacts, Integer pageIndex, boolean trace) {
        Timeouts budget = timeouts == null ? Timeouts.defaults() : timeouts;
        Playwright playwright = Playwright.create();
        Browser browser = null;
        try {
            browser = playwright.chromium().connectOverCDP(endpoint.cdpUrl(),
                    new BrowserType.ConnectOverCDPOptions().setTimeout(Durations.milliseconds(budget.defaultWait())));
            List<BrowserContext> contexts = browser.contexts();
            if (contexts.isEmpty()) {
                throw new DriverError(endpoint.cdpUrl() + ": attached, but the browser reports no contexts; "
                        + "is this the POS Chrome? (DESIGN.md 5.2)");
            }
            Page page;
            if (pageIndex == null) {
                page = livePosPage(contexts);
                if (page == null) {
                    throw new DriverError(endpoint.cdpUrl() + ": attached, but the browser has no pages");
                }
            } else {
                List<Page> pages = contexts.get(0).pages();
                if (pages.size() <= pageIndex) {
                    throw new DriverError(endpoint.cdpUrl() + ": context has " + pages.size()
                            + " page(s), wanted index " + pageIndex);
                }
                page = pages.get(pageIndex);
            }
            double ms = Durations.milliseconds(budget.defaultWait());
            page.setDefaultTimeout(ms);
            page.setDefaultNavigationTimeout(ms + NAVIGATION_GRACE.toMillis());

            PosDriver driver = builder(page)
                    .timeouts(budget)
                    .buildVersion(buildVersion)
                    .artifacts(artifacts)
                    .registerId(endpoint.id())
                    .build();
            // Closed in reverse: the browser first (on a CDP-attached browser this only disconnects,
            // and never closes the register's Chrome), then Playwright's temp folder.
            driver.owned.add(playwright::close);
            final Browser attached = browser;
            driver.owned.add(attached::close);
            if (trace) {
                driver.startTrace();
            }
            return driver;
        } catch (RuntimeException e) {
            playwright.close();
            throw e;
        }
    }

    /** The newest page holding the POS UI frame, else the newest page (DESIGN.md 5.2, 2026-10-09). */
    static Page livePosPage(Collection<BrowserContext> contexts) {
        List<Page> pages = new ArrayList<>();
        for (BrowserContext context : contexts) {
            pages.addAll(context.pages());
        }
        for (int i = pages.size() - 1; i >= 0; i--) {
            Page page = pages.get(i);
            for (Frame frame : page.frames()) {
                if (frame.url().contains(POS_UI_FRAME)) {
                    return page;
                }
            }
        }
        return pages.isEmpty() ? null : pages.get(pages.size() - 1);
    }

    /** The Playwright page this driver drives. */
    public Page page() {
        return page;
    }

    public Timeouts timeouts() {
        return timeouts;
    }

    public String registerId() {
        return registerId;
    }

    // -- resolution --------------------------------------------------------

    /** A concrete catalogue entry, with its template indices filled in (DESIGN.md 5.2). */
    public Locator resolve(Locator locator, Object... args) {
        if (args == null || args.length == 0) {
            return locator;
        }
        return locator.at(args);
    }

    /** A build-variant entry, resolved against this session's build version, then templated. */
    public Locator resolve(BuildVariant variant, Object... args) {
        return resolve(variant.resolve(buildVersion), args);
    }

    /** The Playwright locator for a catalogue entry, inside its frame if any. */
    public com.microsoft.playwright.Locator find(Locator locator, Object... args) {
        Locator resolved = resolve(locator, args);
        String frame = effectiveFrame(resolved.frame(), resolved.selector());
        return locatorIn(frame, resolved.selector());
    }

    /**
     * Every member of an indexed ID family, in one query. Uses {@link Locator#prefixSelector()}:
     * a probe that misses costs a full timeout, which is a sleep by another name (DESIGN.md 4.2 rule 2).
     */
    public com.microsoft.playwright.Locator findFamily(Locator locator, String frame) {
        String given = frame == null ? locator.frame() : frame;
        String selector = locator.prefixSelector();
        return locatorIn(effectiveFrame(given, selector), selector);
    }

    /**
     * {@code frame} if the catalogue named one; else which frame actually has the element. A
     * snapshot count on the page, with no wait. Falls back to the nested frame when the element
     * is not on the page, which is where the register renders almost everything (DESIGN.md 5.2).
     */
    String effectiveFrame(String frame, String selector) {
        if (frame != null && !frame.isEmpty()) {
            return frame;
        }
        if (page.locator(selector).count() > 0) {
            return null;
        }
        try {
            frameOwnerSelector(DEFAULT_NESTED_FRAME);
        } catch (FrameNotFoundError e) {
            // The default nested frame is not attached here: "not found there either".
            return null;
        }
        return DEFAULT_NESTED_FRAME;
    }

    private com.microsoft.playwright.Locator locatorIn(String frame, String selector) {
        if (frame == null || frame.isEmpty()) {
            return page.locator(selector);
        }
        return page.frameLocator(frameOwnerSelector(frame)).locator(selector);
    }

    /** Which of {@code Locators.FRAMES[frame]}'s candidate selectors is present. */
    String frameOwnerSelector(String frame) {
        List<String> candidates = Locators.FRAMES.get(frame);
        if (candidates == null || candidates.isEmpty()) {
            throw new FrameNotFoundError("no selector candidates for frame '" + frame
                    + "'; it must be in Locators.FRAMES, which GenLocators generates from the corpus");
        }
        for (String candidate : candidates) {
            // count() is a snapshot with no implicit wait, so trying each candidate cannot hang.
            if (page.locator(candidate).count() > 0) {
                return candidate;
            }
        }
        throw new FrameNotFoundError("frame '" + frame + "' matched none of " + candidates
                + ". Which DOM iframe this PATS logical name denotes is UNVERIFIED and is a Phase 0 "
                + "rig-inspection item (DESIGN.md 10); see Locators.FRAMES.");
    }

    /** The Frame object {@code frame} denotes, for {@code evaluate}, not for selectors. */
    private Frame frameFor(String frame) {
        for (Frame candidate : page.frames()) {
            if (frame.equals(candidate.name())) {
                return candidate;
            }
        }
        List<String> names = new ArrayList<>();
        for (Frame candidate : page.frames()) {
            names.add(candidate.name());
        }
        throw new FrameNotFoundError("frame '" + frame + "' not among " + names
                + "; not attached, or its name attribute does not match the logical name (DESIGN.md 10)");
    }

    /** Evaluates in the page (no frame) or in the named frame. */
    private Object evaluateIn(String frame, String script, Object arg) {
        if (frame == null || frame.isEmpty()) {
            return page.evaluate(script, arg);
        }
        return frameFor(frame).evaluate(script, arg);
    }

    /** One evaluating target: the page, or the nested frame. */
    @FunctionalInterface
    private interface Target {
        Object evaluate(String script, Object arg);
    }

    /** The page, then the nested frame if attached (the Python {@code _overlay_frames}). */
    private List<Target> overlayFrames() {
        List<Target> targets = new ArrayList<>();
        targets.add(page::evaluate);
        Frame nested = null;
        for (Frame frame : page.frames()) {
            if (DEFAULT_NESTED_FRAME.equals(frame.name())) {
                nested = frame;
                break;
            }
        }
        if (nested != null) {
            final Frame target = nested;
            targets.add(target::evaluate);
        }
        return targets;
    }

    // -- blocking overlays -----------------------------------------------

    /**
     * Waits out the busy window, and presses OK on the unregistered-system nag. Nothing is ever
     * removed from the page: a removed panel left the POS ignoring every tap after it (HANDOFF 1.145).
     * A nag still up after {@link #NAG_PRESS_LIMIT} presses is left alone and the caller goes ahead.
     */
    public void clearBlockingOverlays(Duration timeout) {
        List<String> titleIds = Locators.OVERLAY_MSG_VIEW_TITLE.literals();
        List<String> buttonIds = new ArrayList<>(Locators.OVERLAY_MSG_VIEW_OK_BTN.literals());
        buttonIds.addAll(Locators.OVERLAY_MSG_VIEW_CANCEL_BTN.literals());
        List<String> nags = new ArrayList<>();
        for (String title : NAG_OVERLAY_TITLES) {
            nags.add(title.toLowerCase(java.util.Locale.ROOT));
        }
        String busy = BUSY_OVERLAY_TITLE.toLowerCase(java.util.Locale.ROOT);
        OverlayWatch watch = new OverlayWatch();

        BooleanSupplier settled = () -> {
            long now = System.nanoTime();
            String mode;
            if (watch.presses >= NAG_PRESS_LIMIT && watch.pressedAt != null) {
                if (elapsed(watch.pressedAt, now).compareTo(NAG_ORPHAN) > 0) {
                    LOG.warn("a nag {} is still up after {} OK presses; left alone, not removed",
                            NAG_OVERLAY_TITLES, watch.presses);
                    return true;
                }
                mode = "look";
            } else if (watch.pressedAt != null && elapsed(watch.pressedAt, now).compareTo(NAG_ORPHAN) <= 0) {
                mode = "look";
            } else {
                mode = "press";
            }
            List<Object> states;
            try {
                states = documents(Scripts.BLOCKING_OVERLAY_DOC,
                        Arrays.asList(titleIds, buttonIds, busy, nags, mode), false, "", false);
            } catch (FrameReloaded e) {
                return false;
            }
            if (states.contains("dismissed")) {
                watch.presses++;
                watch.pressedAt = now;
                return false;
            }
            if (states.contains("nag")) {
                return false;
            }
            watch.pressedAt = null;
            if (states.contains("nag without ok")) {
                if (watch.buttonlessSince == null) {
                    watch.buttonlessSince = now;
                }
                if (elapsed(watch.buttonlessSince, now).compareTo(NAG_BUTTON_GRACE) > 0) {
                    throw new DriverError("a nag " + NAG_OVERLAY_TITLES
                            + " is showing, but no visible OK button sits beside it");
                }
                return false;
            }
            watch.buttonlessSince = null;
            return !states.contains("busy");
        };
        PollUntil.until(settled, timeoutOr(timeout, OVERLAY_SETTLE), POLL_INTERVAL,
                "'" + BUSY_OVERLAY_TITLE + "' and nags " + NAG_OVERLAY_TITLES + " to clear");
    }

    /** The state {@link #clearBlockingOverlays} carries between polls. */
    private static final class OverlayWatch {
        int presses;
        Long pressedAt;
        Long buttonlessSince;
    }

    // -- actions, every one bounded (DESIGN.md 6.3) -----------------------

    /** Press an element: {@code tap} in the Python, the replacement for TOUCH_EVENT, with the default wait. */
    public void tap(Locator locator, Object... args) {
        tapWithin(null, locator, args);
    }

    /**
     * Press an element, waiting at most {@code timeout} for it to be visible. ID locators are
     * pressed by {@code getElementById(id).click()} in the frame's own world; XPath locators by
     * mouse at the box's centre. Neither ever hands Playwright a node handle for the press (the
     * DOM.resolveNode fault the Python's VERIFY_NESTED_FRAME_CLICK.md records).
     */
    public void tapWithin(Duration timeout, Locator locator, Object... args) {
        if (settleOverlays) {
            clearBlockingOverlays(null);
        }
        Locator resolved = resolve(locator, args);
        String frame = effectiveFrame(resolved.frame(), resolved.selector());
        com.microsoft.playwright.Locator target = locatorIn(frame, resolved.selector());
        double ms = ms(timeout);
        target.waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE).setTimeout(ms));
        if (resolved.kind() == Locator.Kind.ID) {
            List<String> ids = resolved.literals();
            BooleanSupplier click = () -> Boolean.TRUE.equals(evaluateIn(frame, CLICK_BY_ID, ids));
            if (click.getAsBoolean()) {
                return;
            }
            // The view was being redrawn between the wait and the click (the login keypad after
            // the ID's OK). Nothing was clicked, so trying again cannot press twice.
            try {
                PollUntil.until(click, ID_REDRAW_WAIT, Duration.ofMillis(100), resolved.name());
            } catch (DriverTimeout e) {
                throw new DriverError(resolved.name() + ": visible a moment ago, but no id in "
                        + ids + " resolved to click for " + ID_REDRAW_WAIT.toSeconds() + "s");
            }
            LOG.info("{}: pressed once its view had been redrawn", resolved.name());
            return;
        }
        BoundingBox box = target.boundingBox(new com.microsoft.playwright.Locator.BoundingBoxOptions().setTimeout(ms));
        if (box == null) {
            throw new DriverError(resolved.name() + ": visible a moment ago, but boundingBox() is null");
        }
        double x = box.x + box.width / 2;
        double y = box.y + box.height / 2;
        page.mouse().move(x, y);
        page.mouse().down();
        page.mouse().up();
    }

    /**
     * Press the topmost visible element of {@code locator} whose text starts with {@code text},
     * waiting, bounded, for one to render. Returns the pressed element's text.
     */
    public String pressLastVisible(Locator locator, String text, Duration timeout) {
        return PollUntil.value(() -> pressLastVisibleOnce(locator, text), timeoutOr(timeout, timeouts.defaultWait()),
                POLL_INTERVAL, locator.name() + " reading '" + text + "'");
    }

    /** One look for {@link #pressLastVisible}'s target: press it if it is there. No wait. */
    public String pressLastVisibleOnce(Locator locator, String text) {
        if (settleOverlays) {
            clearBlockingOverlays(null);
        }
        List<Object> arg = Arrays.asList(familySelector(locator), text.toLowerCase(java.util.Locale.ROOT));
        try {
            for (Object pressed : documents(Scripts.TAP_LAST_VISIBLE_DOC, arg, false, "truthy", false)) {
                if (pressed != null) {
                    return pressed.toString();
                }
            }
        } catch (FrameReloaded e) {
            // look again
            return null;
        }
        return null;
    }

    /**
     * Press the element {@code locator} names, once it exists (PATS's TOUCH_EVENT). Where several
     * share an ID, presses the last visible one, the menu on top. A hidden-only match is pressed
     * after {@link #HIDDEN_PRESS_GRACE} with a warning.
     */
    public void press(Locator locator, Duration timeout) {
        if (settleOverlays) {
            clearBlockingOverlays(null);
        }
        PressTarget target = pressTarget(locator);
        long started = System.nanoTime();
        PollUntil.until(() -> pressAttempt(target, started), timeoutOr(timeout, timeouts.defaultWait()),
                POLL_INTERVAL, target.selectors().get(0) + " to be present");
    }

    private boolean pressAttempt(PressTarget target, long started) {
        boolean hiddenOk = elapsed(started, System.nanoTime()).compareTo(HIDDEN_PRESS_GRACE) >= 0;
        List<Object> arg = Arrays.asList(target.kind(), target.selectors(), hiddenOk ? "click" : "click-visible");
        try {
            for (Object docStates : documents(Scripts.ELEMENTS_DOC, arg, true, "pressed", false)) {
                List<Map<String, Object>> states = statesOf(docStates);
                for (Map<String, Object> state : states) {
                    if (truthy(state.get("visible")) || truthy(state.get("laid"))) {
                        return true;
                    }
                }
                if (!states.isEmpty() && hiddenOk) {
                    LOG.warn("pressed {} while no match was visible", target.selectors().get(0));
                    return true;
                }
            }
        } catch (FrameReloaded e) {
            // look again
            return false;
        }
        return false;
    }

    /** Dispatches mousedown, mouseup and click on the {@code index}th visible match. No wait. */
    public boolean dispatchPress(Locator locator, int index) {
        PressTarget target = pressTarget(locator);
        for (Object result : documents(Scripts.DISPATCH_DOC,
                Arrays.asList(target.kind(), target.selectors(), index), true, "truthy", true)) {
            if (truthy(result)) {
                return true;
            }
        }
        return false;
    }

    // -- reads ------------------------------------------------------------

    /** {@code (visible, text)} of every element {@code locator} matches. No wait: one look. */
    public List<ElementState> elementStates(Locator locator) {
        List<ElementState> out = new ArrayList<>();
        for (Map<String, Object> state : elements(locator)) {
            out.add(new ElementState(truthy(state.get("visible")),
                    PosText.normalise(String.valueOf(state.get("text")))));
        }
        return out;
    }

    /** The class attribute of every element {@code locator} matches. No wait. */
    public List<String> elementClasses(Locator locator) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> state : elements(locator)) {
            out.add(String.valueOf(state.get("cls")));
        }
        return out;
    }

    /** {@code (displayed, enabled)} of every element {@code locator} matches. No wait. */
    public List<Clickable> clickableStates(Locator locator) {
        PressTarget target = pressTarget(locator);
        List<Clickable> out = new ArrayList<>();
        for (Object docStates : documents(Scripts.CLICKABLE_DOC,
                Arrays.asList(target.kind(), target.selectors()), false, "", true)) {
            for (Object pair : asList(docStates)) {
                List<?> values = asList(pair);
                out.add(new Clickable(truthy(values.get(0)), truthy(values.get(1))));
            }
        }
        return out;
    }

    /** Whether any element of any of {@code locators} is visible, in one look. Kinds must match. */
    public boolean anyVisible(Locator... locators) {
        Set<String> kinds = new LinkedHashSet<>();
        List<String> selectors = new ArrayList<>();
        for (Locator locator : locators) {
            PressTarget target = pressTarget(locator);
            kinds.add(target.kind());
            selectors.addAll(target.selectors());
        }
        if (kinds.size() != 1) {
            throw new DriverError("anyVisible wants locators of one kind, got " + kinds);
        }
        for (Object docStates : documents(Scripts.ELEMENTS_DOC,
                Arrays.asList(kinds.iterator().next(), selectors, ""), false, "", true)) {
            for (Map<String, Object> state : statesOf(docStates)) {
                if (truthy(state.get("visible"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Waits until the screen differs from {@code before} and has not changed for {@code quiet},
     * or {@code cap} has passed, whichever is first; with {@code moved}, only for it to hold still.
     * Waited inside the browser, sampling every {@link #SETTLE_SAMPLE}; falls back to polling
     * {@link #screenText} from here when the page's context goes under the wait.
     */
    public Settled settleScreen(String before, Duration quiet, Duration cap, boolean moved) {
        long started = System.nanoTime();
        if (!splitFrames) {
            try {
                Object raw = page.evaluate(Scripts.SETTLE_SCRIPT,
                        Arrays.asList(before, quiet.toMillis(), cap.toMillis(), moved,
                                DEFAULT_NESTED_FRAME, SETTLE_SAMPLE.toMillis()));
                List<?> pair = asList(raw);
                return new Settled(truthy(pair.get(0)), String.valueOf(pair.get(1)));
            } catch (PlaywrightException e) {
                LOG.info("settling from the controller instead: {}", firstLine(e.getMessage()));
            }
        }
        Watch seen = new Watch(before, System.nanoTime(), moved);
        BooleanSupplier still = () -> {
            long now = System.nanoTime();
            String text = screenText();
            if (!text.equals(seen.text)) {
                seen.text = text;
                seen.since = now;
                seen.moved = true;
            }
            return seen.moved && elapsed(seen.since, now).compareTo(quiet) >= 0;
        };
        Duration remaining = cap.minus(elapsed(started, System.nanoTime()));
        if (remaining.compareTo(SETTLE_SAMPLE) < 0) {
            remaining = SETTLE_SAMPLE;
        }
        try {
            PollUntil.until(still, remaining, SETTLE_SAMPLE, "the screen to settle");
        } catch (DriverTimeout expected) {
            // the cap ran out: report what the screen is now
        }
        return new Settled(seen.moved, seen.text);
    }

    /**
     * Everything the page and the nested frame render as text, and every text field's value, now.
     * No wait. A keypad digit lands only in an input's value, which innerText leaves out.
     */
    public String screenText() {
        StringBuilder out = new StringBuilder();
        for (Object text : documents(Scripts.SCREEN_TEXT_DOC, null, false, "", true)) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(text);
        }
        return out.toString();
    }

    /** The text of every visible element matching {@code locator}, in document order. No wait. */
    public List<String> visibleTexts(Locator locator) {
        List<String> out = new ArrayList<>();
        for (Object docTexts : documents(Scripts.VISIBLE_TEXTS_DOC, familySelector(locator),
                false, "", true)) {
            for (Object text : asList(docTexts)) {
                out.add(String.valueOf(text));
            }
        }
        return out;
    }

    /**
     * Every element of an indexed ID family, hidden ones too, by row index. Several elements under
     * one index (a stacked copy) are all kept, page first. No wait.
     */
    public Map<Long, List<String>> familyTexts(Locator locator) {
        Map<Long, List<String>> found = new LinkedHashMap<>();
        for (DomElement element : familyElements(locator.prefixSelector())) {
            OptionalLong index = locator.indexOf(element.id());
            if (index.isPresent()) {
                found.computeIfAbsent(index.getAsLong(), k -> new ArrayList<>()).add(element.text());
            }
        }
        return found;
    }

    /**
     * {@code [id, text]} of every element matching one ID-prefix selector, page first. The
     * selector must come from a catalogue {@link Locator}; no selector is written here. No wait.
     */
    public List<DomElement> familyElements(String prefixSelector) {
        List<DomElement> out = new ArrayList<>();
        for (Object docPairs : documents(Scripts.FAMILY_TEXTS_DOC, prefixSelector, false, "", true)) {
            for (Object pair : asList(docPairs)) {
                List<?> values = asList(pair);
                out.add(new DomElement(String.valueOf(values.get(0)),
                        PosText.normalise(String.valueOf(values.get(1)))));
            }
        }
        return out;
    }

    /** The element's text, normalised (VALIDATION_BY_TEXT, WIDGET_VALIDATION): read, not asserted. */
    public String textOf(Locator locator, Object... args) {
        return textOfWithin(null, locator, args);
    }

    public String textOfWithin(Duration timeout, Locator locator, Object... args) {
        String content = find(locator, args).textContent(
                new com.microsoft.playwright.Locator.TextContentOptions().setTimeout(ms(timeout)));
        return PosText.normalise(content == null ? "" : content);
    }

    /** IS_ELEMENT_PRESENT: a snapshot count, no wait. */
    public boolean isPresent(Locator locator, Object... args) {
        return find(locator, args).count() > 0;
    }

    /** Retry until the element is visible or the budget expires. */
    public void expectVisible(Locator locator, Object... args) {
        expectVisibleWithin(null, locator, args);
    }

    public void expectVisibleWithin(Duration timeout, Locator locator, Object... args) {
        expectation(find(locator, args), timeout).toBeVisible();
    }

    /** Retry until the element is gone: how an overlay dismissal is confirmed. */
    public void expectHiddenWithin(Duration timeout, Locator locator, Object... args) {
        expectation(find(locator, args), timeout).toBeHidden();
    }

    /**
     * Retry until the element reads {@code text}. Substring unless {@code exact}: the corpus
     * asserts POS labels with {@code exactMatch "No"} almost everywhere.
     */
    public void expectTextWithin(Duration timeout, Locator locator, String text, boolean exact, Object... args) {
        BoundAssertion assertion = expectation(find(locator, args), timeout);
        if (exact) {
            assertion.toHaveText(text);
        } else {
            assertion.toContainText(text);
        }
    }

    /** A Playwright assertion with the rig's timeout already applied. */
    public BoundAssertion expectation(com.microsoft.playwright.Locator target, Duration timeout) {
        return new BoundAssertion(PlaywrightAssertions.assertThat(target), ms(timeout));
    }

    // -- rung 0: capture before recovery (DESIGN.md 6.3) ------------------

    /**
     * Begins a Playwright trace, recorded in chunks so {@link #beginTest} can drop what came before
     * a test. No screencast and no DOM snapshots: they grew the trace past the controller's disk.
     */
    public void startTrace() {
        BrowserContext context = page.context();
        if (context == null) {
            return;
        }
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(false).setSnapshots(false).setSources(false));
        context.tracing().startChunk();
        tracing = true;
    }

    /** Drops the trace recorded so far; a failure in this test saves only its own. */
    public void beginTest() {
        if (!tracing) {
            return;
        }
        try {
            page.context().tracing().stopChunk();
            page.context().tracing().startChunk();
        } catch (RuntimeException e) {
            // tracing is evidence, never a reason to fail a test
            tracing = false;
        }
    }

    /**
     * Rung 0: trace, screenshot and DOM, written before any recovery rung runs. Never throws: a
     * capture failure must not mask the failure it captures, nor stop the next test starting.
     */
    public Map<String, Path> capture(String label) {
        Map<String, Path> written = new LinkedHashMap<>();
        if (artifacts == null) {
            return written;
        }
        String prefix = registerId.isEmpty() ? label : label + "-" + registerId;
        try {
            Path path = artifacts.pathFor(prefix + "-screenshot.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(path).setTimeout(ms(null)));
            written.put("screenshot", path);
        } catch (RuntimeException e) {
            LOG.debug("screenshot not captured: {}", e.getMessage());
        }
        try {
            Path path = artifacts.pathFor(prefix + "-dom.html");
            Files.write(path, dom().getBytes(StandardCharsets.UTF_8));
            written.put("dom", path);
        } catch (RuntimeException | IOException e) {
            LOG.debug("DOM not captured: {}", e.getMessage());
        }
        if (tracing) {
            try {
                Path path = artifacts.pathFor(prefix + "-trace.zip");
                page.context().tracing().stopChunk(new Tracing.StopChunkOptions().setPath(path));
                written.put("trace", path);
            } catch (RuntimeException e) {
                LOG.debug("trace not captured: {}", e.getMessage());
            }
            // A fresh chunk from here, so the next failure's trace covers the time since this one.
            try {
                page.context().tracing().startChunk();
            } catch (RuntimeException e) {
                tracing = false;
            }
        }
        return written;
    }

    /**
     * The page's HTML, then each child frame's. {@code Page.content()} alone leaves the POS out,
     * because the POS is a frame (115 failure DOMs on Reg101 held only the empty host page).
     */
    String dom() {
        StringBuilder out = new StringBuilder();
        out.append(page.content());
        List<Frame> frames = page.frames();
        for (int i = 1; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            try {
                out.append("\n<!-- frame '").append(frame.name()).append("' ").append(frame.url()).append(" -->\n")
                        .append(frame.content());
            } catch (RuntimeException e) {
                out.append("\n<!-- frame ").append(frame.url()).append(": unreadable -->\n");
            }
        }
        return out.toString();
    }

    // -- lifecycle -------------------------------------------------------

    /**
     * Releases the CDP session. Does not close the POS's Chrome: on a CDP-attached browser,
     * {@code Browser.close()} only disconnects. Teardown never fails a run.
     */
    @Override
    public void close() {
        for (int i = owned.size() - 1; i >= 0; i--) {
            try {
                owned.get(i).run();
            } catch (RuntimeException e) {
                LOG.debug("teardown: {}", e.getMessage());
            }
        }
        owned.clear();
    }

    // -- the document looks ----------------------------------------------

    /**
     * {@code script}'s result in the page's document, then the nested frame's, in one round trip
     * when the frame is readable from the page. {@code stop}: "truthy" after a document whose result
     * is truthy, "pressed" after one whose result holds a pressed element. A frame that reloads
     * under the look is skipped, or, with {@code skip} false, ends the look with
     * {@link FrameReloaded}, and the caller looks again.
     */
    private List<Object> documents(String docScript, Object arg, boolean reverse, String stop, boolean skip) {
        if (!splitFrames) {
            Object raw;
            try {
                raw = page.evaluate(Scripts.multiOf(docScript), Arrays.asList(arg, DEFAULT_NESTED_FRAME, reverse, stop));
            } catch (PlaywrightException e) {
                if (skip) {
                    return new ArrayList<>();
                }
                throw new FrameReloaded();
            }
            List<?> parts = asList(raw);
            boolean found = truthy(parts.get(0));
            boolean reached = truthy(parts.get(1));
            boolean loading = truthy(parts.get(2));
            if (loading && !skip) {
                // Mid-reload, the frame's document is new and empty: a press must not settle for the page.
                throw new FrameReloaded();
            }
            if ((found && reached) || overlayFrames().size() == 1) {
                return new ArrayList<>(asList(parts.get(3)));
            }
            LOG.warn("the nested frame '{}' is attached but not readable from the page's document "
                    + "(found={}, readable={}); looking frame by frame from now on",
                    DEFAULT_NESTED_FRAME, found, reached);
            splitFrames = true;
        }
        List<Target> targets = overlayFrames();
        if (reverse) {
            Collections.reverse(targets);
        }
        String perDoc = Scripts.perDoc(docScript);
        List<Object> results = new ArrayList<>();
        for (Target target : targets) {
            Object result;
            try {
                result = target.evaluate(perDoc, arg);
            } catch (PlaywrightException e) {
                if (skip) {
                    continue;
                }
                throw new FrameReloaded();
            }
            results.add(result);
            if ("truthy".equals(stop) && truthy(result)) {
                break;
            }
            if ("pressed".equals(stop) && anyPressed(result)) {
                break;
            }
        }
        return results;
    }

            /** Settle, in the page, as the Python's {@code _SETTLE_SCRIPT}. */
    
    private List<Map<String, Object>> elements(Locator locator) {
        PressTarget target = pressTarget(locator);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object docStates : documents(Scripts.ELEMENTS_DOC,
                Arrays.asList(target.kind(), target.selectors(), ""), false, "", true)) {
            out.addAll(statesOf(docStates));
        }
        return out;
    }

    private PressTarget pressTarget(Locator locator) {
        Locator resolved = resolve(locator);
        return new PressTarget(resolved.kind() == Locator.Kind.ID ? "id" : "xpath", resolved.literals());
    }

    /** A templated locator's whole ID family ({@code FunctionMenu_{n}}), else the one element. */
    private static String familySelector(Locator locator) {
        return locator.value().contains("{") ? locator.prefixSelector() : locator.selector();
    }

        /** Milliseconds for a Playwright timeout: the override, else the rig's default. Never zero. */
    double ms(Duration override) {
        Duration value = override == null ? timeouts.defaultWait() : override;
        return Durations.milliseconds(requirePositive(value));
    }

    private static Duration requirePositive(Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("a zero or negative timeout means 'wait forever', "
                    + "which DESIGN.md 6.3 forbids");
        }
        return value;
    }

    private static Duration timeoutOr(Duration override, Duration fallback) {
        return override == null ? fallback : requirePositive(override);
    }

    private static Duration elapsed(long fromNanos, long toNanos) {
        return Duration.ofNanos(toNanos - fromNanos);
    }

    private static Duration elapsed(Long fromNanos, long toNanos) {
        return Duration.ofNanos(toNanos - fromNanos);
    }

    private static boolean anyPressed(Object result) {
        if (result instanceof Collection<?> states) {
            for (Object state : states) {
                if (state instanceof Map<?, ?> map && truthy(map.get("pressed"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Map<String, Object>> statesOf(Object docStates) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object state : asList(docStates)) {
            if (state instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                out.add(typed);
            }
        }
        return out;
    }

    /** A Playwright JSON value as a list; anything else as empty. */
    static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** JavaScript truthiness of a value read back from the page. */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }

    /** A press target: the element kind ("id" or "xpath") and every literal it may match. */
    private record PressTarget(String kind, List<String> selectors) {}

    /** {@code (visible, text)} of one element, as {@link #elementStates} reports it. */
    public record ElementState(boolean visible, String text) {}

    /** {@code (displayed, enabled)} of one element, as Selenium's elementToBeClickable asks. */
    public record Clickable(boolean displayed, boolean enabled) {}

    /** The result of {@link #settleScreen}: whether the screen changed, and its text now. */
    public record Settled(boolean changed, String text) {}

    /** The mutable state of {@link #settleScreen}'s fallback poll. */
    private static final class Watch {
        String text;
        long since;
        boolean moved;

        Watch(String text, long since, boolean moved) {
            this.text = text;
            this.since = since;
            this.moved = moved;
        }
    }
}
