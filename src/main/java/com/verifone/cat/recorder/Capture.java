package com.verifone.cat.recorder;

import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.authoring.PyJson;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.pages.ExpressPanel;
import com.verifone.cat.validate.PyRepr;

/**
 * The recorder: watch a register's POS page over CDP and write what the operator taps. Port of
 * {@code petrotest.recorder.capture} (CAT_RESULTS_AND_GENERATE.md 3.3, P11.2), <b>observe-only</b>. Entry point:
 * {@code cat-java record} ({@link RecordCommand}).
 *
 * <p><b>UNVERIFIED AGAINST HARDWARE (Java port).</b> Every run of this class so far has been against a local Chromium
 * page. What an operator's finger produces on a register ({@code click} only, or {@code touchend} then a synthesised
 * {@code click}) is not known; both are listened for and de-duplicated, and which one arrived is written on every tap.
 *
 * <p><b>Attach.</b> Playwright's {@code connectOverCDP}, against the endpoint the caller resolved from the rig file or
 * passed. <b>Deviation:</b> the Python uses Playwright's async API so a tap is delivered as it happens; Playwright for
 * Java is synchronous and delivers callbacks only while one of its calls is running, so the recorder waits in
 * {@code BrowserContext.waitForCondition}, which dispatches every event (each tap's binding call) while it waits and
 * checks the stop condition between them. That is a wait on an event, not a sleep.
 *
 * <p><b>Capture.</b> {@link #LISTENER} is the Python's listener, unchanged: installed into every frame of every page by
 * {@code addInitScript} (documents loaded later) and by an immediate {@code evaluate} (the ones already there). It
 * listens on {@code window} in the capture phase, {@code passive}, and never calls {@code preventDefault} or
 * {@code stopPropagation}. Each tap is posted through a CDP binding (which names its frame) and kept in the page's own
 * log; at stop the recorder removes the listener, reads each frame's log back and writes any tap the binding had not
 * delivered yet ({@code late}).
 *
 * <p><b>Ids come from the catalogue.</b> The listener is given the sign-on prompt's id as an argument, read from
 * {@link Locators}; no id is written in this class. <b>The cashier PIN is not written</b>: a tap made while the sign-on
 * prompt asks for a password is redacted ({@link #redact}) unless it maps to a key with no digit.
 */
public final class Capture {

    /** The page-side function the listener posts through. */
    public static final String BINDING = "__petrotestRecord";

    /** The page-side state object's key, so a second install in one document is a no-op. */
    public static final String STATE_KEY = "__petrotestRecorder";

    /** Installed once per document; {@code config} arrives as JSON. The Python's {@code _LISTENER}, unchanged. */
    static final String LISTENER = """
            (config) => {
              if (window[config.stateKey]) return window[config.stateKey].instance;
              const state = {
                instance: Math.random().toString(36).slice(2) + Date.now().toString(36),
                seq: 0, log: [], lastTouch: null,
              };
              window[config.stateKey] = state;
              const CLICKABLE = new Set(['BUTTON', 'A', 'INPUT', 'SELECT']);
              const shown = (el) => !!el && el.offsetParent !== null;
              const ownTexts = (el) => [...el.childNodes]
                .filter((n) => n.nodeType === 3).map((n) => n.data.trim()).filter(Boolean);
              const promptIn = (doc) => {
                const el = doc.getElementById(config.promptId);
                return shown(el) ? (el.textContent || '').replace(/\\s+/g, ' ').trim() : null;
              };
              const prompt = () => {
                const docs = [document];
                try {
                  docs.push(window.top.document);
                  for (let i = 0; i < window.top.frames.length; i++) docs.push(window.top.frames[i].document);
                } catch (e) { /* a cross-origin frame: its prompt is not readable from here */ }
                for (const doc of docs) {
                  try { const text = promptIn(doc); if (text) return text; } catch (e) { /* ignore */ }
                }
                return null;
              };
              const describe = (target) => {
                let anchor = null, clickable = null, depth = 0;
                for (let el = target; el && el.nodeType === 1; el = el.parentElement, depth++) {
                  if (!clickable && (CLICKABLE.has(el.tagName) || el.getAttribute('role') === 'button')) {
                    clickable = { el, depth };
                  }
                  if (el.id) { anchor = { el, depth }; break; }
                }
                const useText = !!clickable && !clickable.el.id && (!anchor || clickable.depth < anchor.depth);
                const el = useText ? clickable.el : anchor ? anchor.el : clickable ? clickable.el : target;
                return {
                  node: el,
                  info: {
                    id: useText || !anchor ? null : anchor.el.id,
                    container_id: useText && anchor ? anchor.el.id : null,
                    tag: (el.tagName || '').toLowerCase(),
                    texts: ownTexts(el),
                    text: (el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 120),
                  },
                };
              };
              const record = (event) => {
                if (!event.target || event.target.nodeType !== 1) return;
                const { node, info } = describe(event.target);
                const now = Date.now();
                // One finger press can be a touchend and then a synthesised click on the
                // same element: keep whichever came first.
                if (event.type === 'click' && state.lastTouch && state.lastTouch.node === node
                    && now - state.lastTouch.at < 1000) return;
                if (event.type === 'touchend') state.lastTouch = { node, at: now };
                const payload = {
                  instance: state.instance, seq: ++state.seq, at: now, event: event.type,
                  trusted: event.isTrusted, element: info, prompt: prompt(),
                };
                state.log.push(payload);
                try { window[config.binding](payload); } catch (e) { /* read back at stop */ }
              };
              const types = ['click', 'touchend'];
              for (const type of types) window.addEventListener(type, record, { capture: true, passive: true });
              state.remove = () => {
                for (const type of types) window.removeEventListener(type, record, { capture: true });
              };
              return state.instance;
            }
            """;

    /** At stop: take the listener out and hand back the page's log. */
    static final String UNINSTALL = "() => { const s = window." + STATE_KEY + "; if (!s) return [];\n"
            + "         s.remove(); delete window." + STATE_KEY + "; return s.log; }";

    private static final String TEXT_OF = """
            (id) => { const el = document.getElementById(id);
                      return el ? (el.textContent || '').replace(/\\s+/g, ' ').trim() : null; }
            """;

    private Capture() {}

    /** The recorder could not attach, or found nothing to watch. The Python's {@code RecorderError}. */
    public static final class RecorderException extends RuntimeException {
        public RecorderException(String message) {
            super(message);
        }
    }

    /**
     * Where the page came from: written to the header, never an address.
     *
     * @param kind {@code rig}, {@code cdp} or {@code fake_pos}
     */
    public record Source(String kind, String rig, String register) {

        public Map<String, Object> asDict() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("rig", rig);
            out.put("register", register);
            return out;
        }
    }

    /** A redacted (or not) observation: the mapping, the element and whether it was blanked. */
    record Redacted(Mapping mapping, Map<String, Object> element, boolean redacted) {}

    /**
     * A tap at a password prompt has its id, texts, text and slot values blanked, unless it maps to a key with no digit
     * in it (OK, CANCEL). The Python's {@code _redact}.
     */
    static Redacted redact(Mapping mapping, Map<String, Object> payload) {
        Object raw = payload.get("prompt");
        String prompt = raw == null ? "" : PyRepr.str(raw);
        Map<String, Object> element = Recording.map(payload.get("element"));
        if (!prompt.toLowerCase(java.util.Locale.ROOT).contains("password")) {
            return new Redacted(mapping, element, false);
        }
        if (mapping.isMapped() && mapping.args().isEmpty() && mapping.kwargs().isEmpty()) {
            return new Redacted(mapping, element, false);
        }
        List<String> stars = Collections.nCopies(mapping.args().size(), "*");
        Mapping blank = new Mapping(mapping.status(), mapping.locator(), mapping.group(), stars, Map.of(),
                mapping.via(), List.of(), "redacted: pressed at a password prompt");
        Map<String, Object> blanked = new LinkedHashMap<>(element);
        blanked.put("id", null);
        blanked.put("texts", new ArrayList<>());
        blanked.put("text", "");
        return new Redacted(blank, blanked, true);
    }

    /** The Java side of one recording: dedupes, maps, redacts, writes. The Python's {@code _Session}. */
    static final class Session {
        private final RecordingWriter writer;
        private final CatalogueMap cmap;
        private final Set<String> seen = new HashSet<>();
        private List<Map<String, Object>> buffer = new ArrayList<>();

        Session(RecordingWriter writer, CatalogueMap cmap) {
            this.writer = writer;
            this.cmap = cmap;
        }

        /** One tap from frame {@code frameName} at {@code frameUrl}; {@code late} when read back at stop. */
        synchronized void tap(Map<String, Object> payload, String frameName, String frameUrl, boolean late) {
            String key = PyRepr.str(payload.get("instance")) + "\u0000" + intOf(payload.get("seq"));
            if (!seen.add(key)) {
                return;
            }
            Map<String, Object> element = Recording.map(payload.get("element"));
            Mapping mapping = cmap.observe(text(element.get("id")), text(element.get("tag")), texts(element.get("texts")));
            Map<String, Object> withElement = new LinkedHashMap<>(payload);
            withElement.put("element", element);
            Redacted done = redact(mapping, withElement);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "tap");
            line.put("at", RecordingFormat.utcIso(doubleOf(payload.get("at"))));
            line.put("event", payload.get("event"));
            line.put("trusted", truthy(payload.get("trusted")));
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("name", frameName);
            // The path only: a page URL can carry the register's address.
            frame.put("url", urlPath(frameUrl));
            line.put("frame", frame);
            line.put("element", done.element());
            line.put("prompt", payload.get("prompt"));
            line.put("redacted", done.redacted());
            line.put("map", done.mapping().asDict());
            if (late) {
                line.put("late", true);
            }
            if (buffer != null) {
                buffer.add(line);
            } else {
                writer.write(line);
            }
        }

        /** Writes the header, then every tap that arrived before it. */
        synchronized void start(Map<String, Object> header) {
            writer.write(header);
            List<Map<String, Object>> pending = buffer == null ? List.of() : buffer;
            buffer = null;
            for (Map<String, Object> line : pending) {
                writer.write(line);
            }
        }
    }

    /** The listener's argument, as JSON. */
    static String config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("binding", BINDING);
        config.put("stateKey", STATE_KEY);
        config.put("promptId", Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT.value());
        return PyJson.dumps(config);
    }

    /** What is evaluated in a frame to install the listener. */
    static String install() {
        return "(" + LISTENER.strip() + ")(" + config() + ")";
    }

    /**
     * Records until {@code stop} says so or the browser goes away. Returns the tap count.
     *
     * @param interrupted true when the operator interrupted the recording (the footer then says so, and nothing is
     *                    read back); checked with {@code stop}
     * UNVERIFIED AGAINST HARDWARE (Java port).
     */
    public static int record(String cdpUrl, Writer stream, Source source, BooleanSupplier stop,
            BooleanSupplier interrupted, double connectTimeoutMs, Consumer<String> announce) {
        RecordingWriter writer = new RecordingWriter(stream);
        CatalogueMap cmap = CatalogueMap.standard();
        Session session = new Session(writer, cmap);
        String install = install();
        try (Playwright playwright = Playwright.create()) {
            Browser browser;
            try {
                browser = playwright.chromium().connectOverCDP(cdpUrl,
                        new BrowserType.ConnectOverCDPOptions().setTimeout(connectTimeoutMs));
            } catch (PlaywrightException e) {
                throw new RecorderException(cdpUrl + ": could not attach over CDP (" + firstLine(e.getMessage()) + ")");
            }
            AtomicBoolean gone = new AtomicBoolean();
            browser.onDisconnected(b -> gone.set(true));
            List<BrowserContext> contexts = browser.contexts();
            if (contexts.isEmpty() || contexts.stream().allMatch(c -> c.pages().isEmpty())) {
                throw new RecorderException(cdpUrl + ": attached, but the browser has no page to watch");
            }
            for (BrowserContext context : contexts) {
                context.exposeBinding(BINDING, (origin, args) -> {
                    Frame frame = origin.frame();
                    session.tap(normalise(args.length > 0 ? args[0] : null), frame.name(), frame.url(), false);
                    return null;
                });
                context.addInitScript(install);
            }
            List<Frame> frames = frames(browser);
            int installed = 0;
            for (Frame frame : frames) {
                try {
                    frame.evaluate(install);
                    installed++;
                } catch (PlaywrightException e) {
                    announce.accept("recorder: frame " + PyRepr.reprString(frame.name()) + " not installed now ("
                            + firstLine(e.getMessage()) + ")");
                }
            }
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("type", "header");
            header.put("format", RecordingFormat.FORMAT);
            header.put("version", RecordingFormat.VERSION);
            header.put("started_at", RecordingFormat.utcIso());
            header.put("source", source.asDict());
            header.put("catalogue", Map.of("entries", cmap.entries()));
            Map<String, Object> initial = new LinkedHashMap<>();
            initial.put("csr_function_text", initialCsrText(frames));
            header.put("initial_screen", initial);
            header.put("captured", List.of("pos_ui"));
            header.put("not_captured", new ArrayList<>(new java.util.TreeSet<>(RecordingFormat.NOT_CAPTURED.keySet())));
            session.start(header);
            announce.accept("recording: watching " + installed + " frame(s) — perform the case on the register, "
                    + "then press Enter here to stop");

            String reason = "stop requested";
            try {
                BrowserContext waiter = contexts.get(0);
                try {
                    waiter.waitForCondition(() -> stop.getAsBoolean() || interrupted.getAsBoolean() || gone.get(),
                            new BrowserContext.WaitForConditionOptions().setTimeout(0));
                } catch (PlaywrightException e) {
                    gone.set(true);
                }
                if (interrupted.getAsBoolean()) {
                    reason = "interrupted";
                } else if (gone.get()) {
                    reason = "browser disconnected";
                } else {
                    // Taps the binding has not delivered yet are still in each page's log.
                    for (Frame frame : frames(browser)) {
                        Object log;
                        try {
                            log = frame.evaluate(UNINSTALL);
                        } catch (PlaywrightException e) {
                            continue;
                        }
                        if (log instanceof List<?> items) {
                            for (Object payload : items) {
                                session.tap(normalise(payload), frame.name(), frame.url(), true);
                            }
                        }
                    }
                }
            } finally {
                Map<String, Object> footer = new LinkedHashMap<>();
                footer.put("type", "footer");
                footer.put("stopped_at", RecordingFormat.utcIso());
                footer.put("reason", reason);
                footer.put("taps", writer.taps());
                writer.write(footer);
            }
        }
        return writer.taps();
    }

    static List<Frame> frames(Browser browser) {
        List<Frame> out = new ArrayList<>();
        for (BrowserContext context : browser.contexts()) {
            for (Page page : context.pages()) {
                out.addAll(page.frames());
            }
        }
        return out;
    }

    /** The CSR Func button's text in the first frame that has it: the screen the recording starts on. */
    static String initialCsrText(List<Frame> frames) {
        String elementId = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(ExpressPanel.CSR_FUNCTION_SLOT).value();
        for (Frame frame : frames) {
            Object text;
            try {
                text = frame.evaluate(TEXT_OF, elementId);
            } catch (PlaywrightException e) { // a frame navigating away under us: look at the next
                continue;
            }
            if (text != null) {
                return PyRepr.str(text);
            }
        }
        return null;
    }

    /** {@code urlsplit(url).path}: the path alone, never the scheme, host or query. */
    static String urlPath(String url) {
        if (url == null) {
            return "";
        }
        String rest = url;
        java.util.regex.Matcher scheme = java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:").matcher(rest);
        if (scheme.find()) {
            rest = rest.substring(scheme.end());
        }
        if (rest.startsWith("//")) {
            int end = 2;
            while (end < rest.length() && "/?#".indexOf(rest.charAt(end)) < 0) {
                end++;
            }
            rest = rest.substring(end);
        }
        int cut = rest.length();
        for (char c : new char[] {'?', '#'}) {
            int at = rest.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return rest.substring(0, cut);
    }

    /** A page value as plain JSON-shaped Java: maps in order, lists, strings, booleans, whole numbers as longs. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> normalise(Object value) {
        Object plain = plain(value);
        return plain instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static Object plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), plain(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(item -> out.add(plain(item)));
            return out;
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 9.0e15) {
            return d.longValue();
        }
        if (value instanceof Float f && f == Math.rint(f)) {
            return (long) f.floatValue();
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? null : PyRepr.str(value);
    }

    private static List<String> texts(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        list.forEach(item -> out.add(PyRepr.str(item)));
        return out;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        return value != null;
    }

    private static long intOf(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(PyRepr.str(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleOf(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(PyRepr.str(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.lines().findFirst().orElse("");
    }
}
