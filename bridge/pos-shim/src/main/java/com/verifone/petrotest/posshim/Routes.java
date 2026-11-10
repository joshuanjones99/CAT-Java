package com.verifone.petrotest.posshim;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.verifone.isd.pcapps.automation.target.pos.topaz.ent.TokenInfoHolder;

/**
 * The HTTP surface — the boundary that keeps §4.2 rule 6 (Python never speaks Java).
 *
 * <h2>Routes that answer 501, and why that is the feature</h2>
 *
 * Four of the eight status probes `preflight` wants have no source on the pushed
 * panel surface: printer port state, cash drawer position, the suspended-ticket
 * list, and the installed build version. The observer sends screen state, and
 * none of those are on a screen.
 *
 * <p>They are still routed, and they answer 501 with a {@code code} of
 * {@code unsupported} and a message naming what is missing. That is deliberate.
 * The route table this shim replaces returned {@code data.get("connected", False)}
 * against an endpoint that did not exist, which turns "we cannot tell" into "the
 * printer is disconnected" — a FAIL where §6.2 requires an ERROR. A 501 makes the
 * client raise, the check report ERROR, and the gap stay visible until somebody
 * finds a real source for it. Silence would let it be forgotten.
 */
final class Routes implements HttpHandler {

    private final ShimRmiHost host;
    private final Keys keys;
    private final Scanner scanner;
    private final StateWatcher watcher;

    /** How many presses the cancel ladder is allowed. Bounded per §6.3. */
    private static final int MAX_CANCEL_PRESSES = 6;

    Routes(ShimRmiHost host, Keys keys, Scanner scanner, StateWatcher watcher) {
        this.host = host;
        this.keys = keys;
        this.scanner = scanner;
        this.watcher = watcher;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = normalise(exchange.getRequestURI().getPath());
        try {
            Object body = dispatch(method, path, exchange);
            respond(exchange, 200, body);
        } catch (ShimException e) {
            respond(exchange, e.status(), error(e.code(), e.getMessage()));
        } catch (Json.JsonException e) {
            respond(exchange, 400, error("bad-json", e.getMessage()));
        } catch (RuntimeException e) {
            // Anything unhandled is reported with its type: a bare 500 from a
            // shim standing between a test suite and a POS is close to useless
            // for whoever has to work out which of the two broke.
            respond(exchange, 500, error("internal",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private Object dispatch(String method, String path, HttpExchange exchange) throws IOException {
        if (method.equals("GET")) {
            switch (path) {
                case "/":
                    return index();
                case "/health":
                    return health();
                case "/status":
                    return status(exchange);
                case "/rmi/clients":
                    return clients();
                case "/pos/screen": {
                    // The one route that answers without pushed state, because
                    // reporting `state_present: false` is its whole job.
                    String client = client(exchange);
                    Map<String, Object> out = rawSnapshot(client).toMap();
                    watcher.annotate(out, client);
                    return out;
                }
                case "/pos/idle": {
                    String client = client(exchange);
                    Snapshot snapshot = snapshot(client);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("idle", snapshot.idle());
                    out.put("basis", snapshot.idleBasis());
                    out.put("overlay_present", snapshot.overlayPresent());
                    out.put("transaction_active", snapshot.transactionActive());
                    // The overlay's auto_hide flag plus the age is what lets a
                    // caller tell "still processing" from "the dismissal was
                    // never pushed" — the two look identical without them.
                    Map<String, Object> overlay = snapshot.overlay();
                    out.put("overlay_auto_hide",
                            overlay == null ? null : overlay.get("auto_hide"));
                    watcher.annotate(out, client);
                    return out;
                }
                case "/pos/transaction": {
                    Snapshot snapshot = snapshot(exchange);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("active", snapshot.transactionActive());
                    out.put("totals", snapshot.totals());
                    out.put("receipt", snapshot.receipt());
                    return out;
                }
                case "/pos/overlay": {
                    Snapshot snapshot = snapshot(exchange);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("present", snapshot.overlayPresent());
                    out.put("overlay", snapshot.overlay());
                    out.put("review", snapshot.overlayReview());
                    return out;
                }
                case "/pos/express-panel": {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("buttons", snapshot(exchange).expressPanel());
                    return out;
                }
                case "/pos/receipt":
                    return orEmpty(snapshot(exchange).receipt());
                case "/pos/totals":
                    return orEmpty(snapshot(exchange).totals());
                case "/pos/fuel":
                    return orEmpty(snapshot(exchange).fuelPanel());
                case "/pos/keys":
                    return Keys.describe();
                default:
                    break;
            }
        }
        if (method.equals("POST")) {
            switch (path) {
                case "/pos/keys": {
                    String client = client(exchange);
                    Map<String, Object> body = Json.decodeObject(readBody(exchange));
                    return keys.pressAll(client, stringList(body.get("keys")));
                }
                case "/pos/keys/enter": {
                    String client = client(exchange);
                    Map<String, Object> body = Json.decodeObject(readBody(exchange));
                    return keys.pressEnterWithData(client, requiredString(body, "data"));
                }
                case "/pos/keys/pending": {
                    String client = client(exchange);
                    Map<String, Object> body = Json.decodeObject(readBody(exchange));
                    return keys.pressWithData(
                            client, requiredString(body, "key"), requiredString(body, "data"));
                }
                case "/pos/control/cancel":
                    return keys.cancelLadder(client(exchange), MAX_CANCEL_PRESSES);
                case "/pos/control/ticket/void":
                    return voidTicket(client(exchange));
                case "/pos/scan": {
                    String client = client(exchange);
                    Map<String, Object> body = Json.decodeObject(readBody(exchange));
                    return scanner.press(
                            client,
                            optionalInt(body, "status", 0),
                            requiredString(body, "scan_data"),
                            optionalBool(body, "decode_data", true),
                            optionalString(body, "scan_data_label", requiredString(body, "scan_data")),
                            requiredInt(body, "scan_data_type"));
                }
                default:
                    break;
            }
        }
        Map<String, String> unsupported = UNSUPPORTED.get(path);
        if (unsupported != null) {
            throw new ShimException(501, "unsupported", unsupported.get("reason"));
        }
        throw new ShimException(404, "no-route",
                "No route " + method + " " + path + ". GET / lists the routes this shim serves.");
    }

    /**
     * §6.3 rung 2 (VOID): void the open ticket, confirming if the POS asks.
     *
     * <p>Reports success from the totals panel afterwards rather than from the
     * keypresses, for the same reason the cancel ladder does: the POS may decline
     * a void it does not consider valid, and a rung that reported success because
     * it managed to press a key would send the ladder on to rung 3 believing the
     * ticket was gone.
     */
    private Map<String, Object> voidTicket(String client) {
        List<Map<String, Object>> steps = new ArrayList<>();
        Snapshot before = snapshot(client);
        if (!before.transactionActive()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("client", client);
            out.put("outcome", "no-open-ticket");
            out.put("steps", steps);
            out.put("transaction_active", false);
            out.put("success", true);
            return out;
        }
        steps.add(keys.press(client, "VOID_TICKET"));
        Snapshot afterVoid = snapshot(client);
        // A void normally raises a confirmation dialog; answer it with the key its
        // own button flags call for rather than assuming YES exists on it.
        if (afterVoid.overlayPresent()) {
            Map<String, Object> overlay = afterVoid.overlay();
            String confirm = Boolean.TRUE.equals(overlay.get("ok_only")) ? "ENTER" : "YES";
            Map<String, Object> step = new LinkedHashMap<>(keys.press(client, confirm));
            step.put("confirming_overlay", overlay);
            steps.add(step);
        }
        Snapshot end = snapshot(client);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client", client);
        out.put("outcome", end.transactionActive() ? "still-open" : "voided");
        out.put("steps", steps);
        out.put("transaction_active", end.transactionActive());
        out.put("totals", end.totals());
        out.put("success", !end.transactionActive());
        return out;
    }

    private Map<String, Object> index() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "petrotest-pos-shim");
        out.put("build", Build.id());
        out.put("design", "DESIGN.md §5.2 — thin HTTP shim over the retained POS RMI agent");
        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("GET /health", "shim liveness, RMI bind state, and this jar's build stamp");
        routes.put("GET /status", "status round-trip; 200 only once a POS has pushed state");
        routes.put("GET /rmi/clients", "POS clients known to the shim");
        routes.put("GET /pos/screen", "full pushed screen snapshot");
        routes.put("GET /pos/idle", "§6.3 testability predicate, with its basis");
        routes.put("GET /pos/transaction", "open-ticket state from the totals panel");
        routes.put("GET /pos/overlay", "current overlay/dialog and its buttons");
        routes.put("GET /pos/express-panel", "soft-key buttons on screen");
        routes.put("GET /pos/receipt", "receipt panel action");
        routes.put("GET /pos/totals", "ODSP totals panel");
        routes.put("GET /pos/fuel", "fuel panel state");
        routes.put("GET /pos/keys", "which keys can be pressed");
        routes.put("POST /pos/keys", "press keys: {\"keys\": [\"EXIT\"]}");
        routes.put("POST /pos/keys/enter", "press ENTER carrying data: {\"data\": \"123\"}");
        routes.put("POST /pos/keys/pending", "press any key carrying data (PATS pendingToken): "
                + "{\"key\": \"0x3e\", \"data\": \"1000\"}");
        routes.put("POST /pos/control/cancel", "§6.3 rung 1 SOFT — dismiss overlays");
        routes.put("POST /pos/control/ticket/void", "§6.3 rung 2 VOID — void open ticket");
        routes.put("POST /pos/scan", "present a barcode scan: "
                + "{\"scan_data\": \"...\", \"scan_data_type\": 104}");
        out.put("routes", routes);
        Map<String, Object> unsupported = new LinkedHashMap<>();
        UNSUPPORTED.forEach((path, meta) -> unsupported.put(path, meta.get("reason")));
        out.put("unsupported", unsupported);
        out.put("client_query", "all /pos routes accept ?client=<host>; required when "
                + "more than one POS is connected");
        return out;
    }

    private Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> known = host.clients();
        List<String> drivable = host.drivableClients();
        out.put("ok", true);
        // The build stamp compiled into this jar (Build.java): provision compares it
        // with the payload it pushed, and replaces a shim that differs.
        out.put("build", Build.id());
        out.put("uptime_s", host.uptimeSeconds());
        out.put("clients", known);
        out.put("drivable_clients", drivable);
        // A client with a holder but no token service is the half-connected case
        // ShimRmiHost.ensureHolder exists to survive: the observer is pushing but
        // no key can be sent. Surfaced here because it looks healthy otherwise.
        List<String> readOnly = new ArrayList<>(known);
        readOnly.removeAll(drivable);
        out.put("read_only_clients", readOnly);
        out.put("state_present", anyStatePresent());
        Map<String, Object> ages = new LinkedHashMap<>();
        for (String client : known) {
            ages.put(client, watcher.ageSeconds(client));
        }
        out.put("last_change_age_s", ages);
        // Drivable but silent is the shim-restart signature (see
        // ShimRmiHost.diagnoseSilentObserver). Surfaced on /health because this
        // is the route a provision run and a human both check first, and the
        // state is otherwise invisible — the POS looks entirely healthy.
        List<String> silent = new ArrayList<>();
        for (String client : drivable) {
            TokenInfoHolder holder = host.holder(client);
            if (holder == null || !new Snapshot(holder).statePresent()) {
                silent.add(client);
            }
        }
        out.put("drivable_but_silent", silent);
        if (!silent.isEmpty()) {
            out.put("warning", "POS " + silent + " accepts keys but has pushed no status. "
                    + "Usually means the shim was restarted while the POS kept running: the "
                    + "agent's static remoteToken stub still points at the old shim JVM and is "
                    + "never replaced. Restart the POS to restore the status surface.");
        }
        return out;
    }

    /**
     * The §6.1 post-provision check that the RMI agent answers.
     *
     * <p>{@code provision.verify} asks the shim for this and treats 200 as proof
     * the shim reached the agent behind it. So it must be 200 only when a POS has
     * genuinely pushed something: answering 200 because the shim itself is
     * healthy would make {@code verify_agents} pass on a register whose observer
     * never loaded, which is the one thing that check exists to catch.
     *
     * <p>The no-state 503 comes from {@link ShimRmiHost#requireSnapshot}, which
     * every predicate route shares — so there is one definition of "we have not
     * heard from the POS" rather than two that can drift apart.
     */
    private Map<String, Object> status(HttpExchange exchange) {
        String client = client(exchange);
        Map<String, Object> out = snapshot(client).toMap();
        watcher.annotate(out, client);
        return out;
    }

    private Map<String, Object> clients() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : host.clients()) {
            TokenInfoHolder holder = host.holder(name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("client", name);
            entry.put("has_holder", holder != null);
            entry.put("drivable", host.drivableClients().contains(name));
            entry.put("state_present", holder != null && new Snapshot(holder).statePresent());
            entries.add(entry);
        }
        out.put("clients", entries);
        return out;
    }

    private boolean anyStatePresent() {
        for (String name : host.clients()) {
            TokenInfoHolder holder = host.holder(name);
            if (holder != null && new Snapshot(holder).statePresent()) {
                return true;
            }
        }
        return false;
    }

    /** State or 503 — used by every route whose answer would otherwise be a guess. */
    private Snapshot snapshot(HttpExchange exchange) {
        return snapshot(client(exchange));
    }

    private Snapshot snapshot(String client) {
        return host.requireSnapshot(client);
    }

    /**
     * The snapshot as it stands, pushed state or not. Only {@code /pos/screen}.
     *
     * <p>Separate from {@link #snapshot(HttpExchange)} so that answering without
     * state is something a route opts into by name, rather than the default a new
     * route inherits by accident.
     */
    private Snapshot rawSnapshot(String client) {
        TokenInfoHolder holder = host.holder(client);
        if (holder == null) {
            throw new ShimException(503, "no-state",
                    "No state holder for POS '" + client + "'; it has not connected.");
        }
        return new Snapshot(holder);
    }

    private String client(HttpExchange exchange) {
        return host.resolveClient(queryParam(exchange.getRequestURI(), "client"));
    }

    private static Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            throw new ShimException(400, "bad-request",
                    "Body must be {\"keys\": [\"NAME\", ...]} with at least one key.");
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String s)) {
                throw new ShimException(400, "bad-request", "Key names must be strings.");
            }
            out.add(s);
        }
        return out;
    }

    /**
     * Field readers for {@code POST /pos/scan}'s body.
     *
     * <p>{@link Json#decodeObject} decodes every JSON number as {@code Long} or
     * {@code Double}, never {@code Integer}, so these narrow rather than cast —
     * a body built by hand with a decimal {@code scan_data_type} must 400, not
     * silently truncate.
     */
    private static String requiredString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String s) || s.isEmpty()) {
            throw new ShimException(400, "bad-request", "Body must carry a non-empty '" + key + "'.");
        }
        return s;
    }

    private static String optionalString(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String s)) {
            throw new ShimException(400, "bad-request", "'" + key + "' must be a string.");
        }
        return s;
    }

    private static int requiredInt(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof Long l)) {
            throw new ShimException(400, "bad-request", "Body must carry an integer '" + key + "'.");
        }
        return Math.toIntExact(l);
    }

    private static int optionalInt(Map<String, Object> body, String key, int fallback) {
        Object value = body.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Long l)) {
            throw new ShimException(400, "bad-request", "'" + key + "' must be an integer.");
        }
        return Math.toIntExact(l);
    }

    private static boolean optionalBool(Map<String, Object> body, String key, boolean fallback) {
        Object value = body.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean b)) {
            throw new ShimException(400, "bad-request", "'" + key + "' must be a boolean.");
        }
        return b;
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalise(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", code);
        out.put("detail", message);
        return out;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = Json.encode(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /**
     * The probes with no source on this surface, and what each would need.
     *
     * <p>Written as reasons rather than as a bare list so the 501 body tells
     * whoever hits it where to look next, instead of only that they cannot have
     * what they asked for.
     */
    private static final Map<String, Map<String, String>> UNSUPPORTED = unsupportedRoutes();

    private static Map<String, Map<String, String>> unsupportedRoutes() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        out.put("/pos/status/printer", reason(
                "Printer port state is not on the pushed panel surface. The agent's "
                        + "printer path is recordRemotePrinterLexToken — receipt content the POS "
                        + "sends when it prints — which says nothing about whether the port is "
                        + "open. A real source would be the POS's JavaPOS printer service."));
        out.put("/pos/status/drawer", reason(
                "Cash drawer position is not pushed by the observer. No panel object carries "
                        + "it; DRAWER_OPEN/DRAWER_CLOSE exist as key names in LexTokenMaker's "
                        + "enum but have no case, so the drawer can be neither read nor driven "
                        + "over this surface."));
        out.put("/pos/status/transactions", reason(
                "Suspended/parked tickets are not on the pushed surface. Only the current "
                        + "ticket's totals arrive, so a parked ticket from an earlier session is "
                        + "invisible here. SUSP can be pressed, but the list cannot be read."));
        out.put("/pos/status/cashier", reason(
                "The signed-on cashier is not pushed by the observer. No panel object names "
                        + "it; the express panel's button set differs between the sign-on and "
                        + "operating screens, so it could be inferred from GET "
                        + "/pos/express-panel — but an inference from button labels is not a "
                        + "cashier id, and §6.2's cashier_signed_on wants the id."));
        out.put("/pos/control/ticket/recall-void", reason(
                "A parked ticket can be neither listed nor recalled over this surface. See "
                        + "/pos/status/transactions: only the current ticket's totals arrive, "
                        + "so there is no ticket id to recall."));
        out.put("/pos/status/version", reason(
                "The POS build version is not pushed. It is readable from the register's "
                        + "filesystem over the SSH transport provision already has, which is "
                        + "where build_matches (§6.2) should get it — not from this shim."));
        out.put("/pos/control/cashier/sign-on", reason(
                "Signing on needs digit keys, and digits over this surface mean raw Topaz key "
                        + "codes: LexTokenMaker maps neither \"5\" nor CLEAR to a token. The "
                        + "code table is not derivable from the agent source, and guessing it "
                        + "would press arbitrary keys on a live POS. POST /pos/keys accepts 0xNN "
                        + "codes, so the table can be mapped on a rig and this route written "
                        + "against facts."));
        out.put("/pos/control/drawer/close", reason(
                "DRAWER_CLOSE has no case in LexTokenMaker's switch, so no token can be built "
                        + "for it. See /pos/status/drawer."));
        out.put("/pos/control/printer/reopen", reason(
                "Reopening the printer port is not something this surface can ask for. The "
                        + "agent offers no control path to the printer, only a record of what "
                        + "was printed."));
        out.put("/pos/control/serial/reset", reason(
                "Resetting peripherals is not on this surface. It would be a register-side "
                        + "operation over the SSH transport, not an RMI call."));
        return out;
    }

    private static Map<String, String> reason(String reason) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("reason", reason);
        return out;
    }
}
