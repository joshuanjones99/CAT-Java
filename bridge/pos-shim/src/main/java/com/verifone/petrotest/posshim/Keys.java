package com.verifone.petrotest.posshim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.isd.pcapps.automation.target.pos.topaz.app.rmi.LexTokenRMIServiceImpl;
import com.verifone.isd.pcapps.automation.target.pos.topaz.populator.LexTokenMaker;
import com.verifone.isd.topaz.sys.ent.LexToken;

/**
 * Injecting keystrokes into the POS.
 *
 * <h2>What can actually be pressed</h2>
 *
 * Keys are built by the retained {@link LexTokenMaker}, and the set it can build
 * is narrower than it first looks. Its {@code KbdTokens} enum lists 70-odd names,
 * but {@code createKBDToken}'s switch only has cases for the {@link #NAMED_KEYS}
 * subset below; every other name — {@code CLEAR} and {@code FORCE_EXIT} among
 * them — falls to {@code default}, which returns {@code null}. Digits are worse:
 * {@code "5"} matches neither the {@code 0xNN} pattern nor an enum constant, so
 * {@code convertNumber("15")} returns an array of nulls rather than two digit
 * presses.
 *
 * <p>This class therefore validates before sending, and reports a name it cannot
 * build as a 400 listing what it can. The alternative — passing the name through
 * and letting {@code sendToken} take a null — surfaces as an NPE inside the POS's
 * Lex pipeline, which is a long way from the caller who typed the wrong name.
 *
 * <h2>Why there is no sign-on here</h2>
 *
 * Typing a cashier id or password needs digits, and digits over this surface mean
 * raw Topaz key codes: {@code createKBDToken("0x35")} looks {@code 0x35} up in
 * {@code LexTokenMaker}'s hex table and presses whatever scan code {@code 93}
 * denotes. Which code is the digit {@code 5} is a property of the Topaz keyboard
 * layout, and it is not derivable from anything in this repository or in the
 * agent source. Guessing it would produce a shim that presses arbitrary keys on a
 * live POS, so {@code sign_on} is not implemented and its route answers 501
 * naming this gap. {@code POST /pos/keys} accepts {@code 0xNN} codes directly, so
 * the table can be mapped empirically on a rig and the route then written against
 * facts.
 */
final class Keys {

    /**
     * The key names {@link LexTokenMaker#createKBDToken} has a real case for.
     *
     * <p>Kept as data and checked against the maker at request time rather than
     * trusted: if the retained code gains a case, {@link #supported()} reports it
     * without this list being touched, and if it loses one this list stops
     * claiming it. The list exists to give a caller a useful 400 message, not to
     * be the authority.
     */
    static final List<String> NAMED_KEYS = List.of(
            "APPROVE", "CASH", "CREDIT", "ENTER", "ERROR_CORRECT", "EXIT",
            "KEY_LANE_CLOSE_VIEW_EXIT_BUTTON", "KEY_SCO_CASHIER_ENTRY", "LOG_OUT",
            "NO", "NO_SALE", "OTHER_FUEL", "OTHER_FUNC", "PRINT_REPORT", "QTY",
            "REFUND", "SAFE_DROP", "STOP", "SUSP", "TAX_EXEMPT", "TICKET_PRINT",
            "TOTAL", "VOID_LINE", "VOID_TICKET", "YES");

    private final ShimRmiHost host;
    private final int keyWaitMillis;

    Keys(ShimRmiHost host, int keyWaitMillis) {
        this.host = host;
        this.keyWaitMillis = keyWaitMillis;
    }

    /** The key names this shim can build right now, verified against the maker. */
    static List<String> supported() {
        List<String> names = new ArrayList<>();
        for (String name : NAMED_KEYS) {
            if (LexTokenMaker.createKBDToken(name) != null) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Presses one key on a POS and reports whether it was consumed.
     *
     * <p>{@code sendToken} returns the agent's own {@code tokenSent} flag, which
     * is set when the POS calls back {@code tokenHasBeenProcessed} — so a
     * {@code true} here means the POS's Lex pipeline actually took the key, not
     * merely that we wrote it to a socket. That is the only acknowledgement this
     * surface offers and it is worth propagating honestly: a key that was sent
     * and ignored is the failure mode a recovery rung most needs to see.
     */
    Map<String, Object> press(String client, String name) {
        LexToken token = build(name);
        LexTokenRMIServiceImpl service = host.tokenService(client);
        long started = System.currentTimeMillis();
        boolean processed = service.sendToken(token, client);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", name);
        result.put("processed", processed);
        result.put("elapsed_ms", System.currentTimeMillis() - started);
        return result;
    }

    /**
     * Presses ENTER carrying a data payload — PATS' {@code pendingToken} path
     * (POSDevice.java's {@code PRESS_ENTER} handler, the branch a bare
     * {@link #press} cannot reach).
     *
     * <p>Built from the retained {@code LexTokenMaker.createPendingTokens("ENTER",
     * data)} rather than {@link LexTokenMaker#createKBDToken}: it produces the same
     * {@code LexToken} source and lex behavior as a bare ENTER, and the only
     * difference is the {@code KBDLexDataEvent}'s data field carrying {@code data}
     * instead of {@code ""}. Like {@link Scanner}'s barcode, the payload travels as
     * a plain string field — no digit scan-code table is needed here.
     */
    Map<String, Object> pressEnterWithData(String client, String data) {
        return pressWithData(client, "ENTER", data);
    }

    /**
     * Presses {@code key} carrying a data payload — PATS' {@code pendingToken(key,
     * data)} for any key, not only ENTER.
     *
     * <p>PATS' {@code POSDevice.cashMop} needs it: {@code MOP=0x3e, DATA=1000} is
     * {@code pendingToken("0x3e", "1000")}. {@code LexTokenMaker.createPendingTokens}
     * looks a {@code 0xNN} code up in its hex table, or a name up in
     * {@code KbdTokens}, and carries {@code data} on the same event.
     */
    Map<String, Object> pressWithData(String client, String key, String data) {
        if (key == null || key.isEmpty() || data == null || data.isEmpty()) {
            throw new ShimException(400, "bad-request",
                    "key and data must both be non-empty; a bare key should use POST /pos/keys instead.");
        }
        LexToken token = LexTokenMaker.createPendingTokens(key, data);
        if (token == null) {
            throw new ShimException(500, "token-build-failed",
                    "LexTokenMaker.createPendingTokens(\"" + key + "\", ...) returned null.");
        }
        LexTokenRMIServiceImpl service = host.tokenService(client);
        long started = System.currentTimeMillis();
        boolean processed = service.sendToken(token, client);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("data", data);
        result.put("processed", processed);
        result.put("elapsed_ms", System.currentTimeMillis() - started);
        return result;
    }

    /** Presses keys in order, stopping at the first the POS does not consume. */
    Map<String, Object> pressAll(String client, List<String> names) {
        // Validate the whole sequence before pressing any of it. Half a key
        // sequence leaves the POS on an intermediate screen that neither the
        // caller nor the recovery ladder expects, and a typo in the fourth name
        // is not a reason to have already pressed the first three.
        for (String name : names) {
            build(name);
        }
        List<Map<String, Object>> pressed = new ArrayList<>();
        boolean allProcessed = true;
        for (String name : names) {
            Map<String, Object> one = press(client, name);
            pressed.add(one);
            if (!Boolean.TRUE.equals(one.get("processed"))) {
                allProcessed = false;
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client", client);
        result.put("requested", names);
        result.put("pressed", pressed);
        result.put("all_processed", allProcessed);
        return result;
    }

    /**
     * §6.3 rung 1 (SOFT): dismiss whatever overlay is on screen, then verify.
     *
     * <p>Two properties make this a rung rather than a hope. It chooses the key
     * from the overlay's own button flags — an OK-only acknowledgement takes
     * {@code ENTER}, anything offering cancel takes {@code EXIT} — and it re-reads
     * the pushed screen state after each press, so "it worked" is an observation
     * and not an assumption about which key dismisses which dialog. If a press
     * changes nothing the loop stops and says so, instead of hammering a POS that
     * is not responding to it.
     */
    Map<String, Object> cancelLadder(String client, int maxPresses) {
        List<Map<String, Object>> steps = new ArrayList<>();
        String outcome = "already-idle";
        for (int attempt = 0; attempt < maxPresses; attempt++) {
            Snapshot before = snapshot(client);
            if (!before.overlayPresent()) {
                outcome = attempt == 0 ? "already-idle" : "cleared";
                break;
            }
            String key = keyForOverlay(before);
            Map<String, Object> step = new LinkedHashMap<>(press(client, key));
            step.put("overlay_before", before.overlay());
            Snapshot after = snapshot(client);
            step.put("overlay_after", after.overlay());
            boolean progressed = !after.overlayPresent()
                    || !String.valueOf(before.overlay()).equals(String.valueOf(after.overlay()));
            step.put("progressed", progressed);
            steps.add(step);
            if (!after.overlayPresent()) {
                outcome = "cleared";
                break;
            }
            if (!progressed) {
                outcome = "stuck";
                break;
            }
            outcome = "still-overlaid";
        }
        Snapshot end = snapshot(client);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client", client);
        result.put("outcome", outcome);
        result.put("steps", steps);
        result.put("overlay_present", end.overlayPresent());
        result.put("idle", end.idle());
        result.put("idle_basis", end.idleBasis());
        // "Did the rung achieve its goal" is the caller's decision to make, and
        // §6.3 defines it as the testability predicate rather than as the rung
        // reporting its own success. So `cleared` is stated as an outcome and
        // `idle` is stated separately; a rung that cleared an overlay and left an
        // open ticket has not made the POS testable and must not claim it did.
        result.put("success", !end.overlayPresent());
        return result;
    }

    private String keyForOverlay(Snapshot snapshot) {
        Map<String, Object> overlay = snapshot.overlay();
        if (overlay != null && Boolean.TRUE.equals(overlay.get("ok_only"))) {
            return "ENTER";
        }
        return "EXIT";
    }

    /**
     * State, or a 503 — never a default.
     *
     * <p>Routed through {@link ShimRmiHost#requireSnapshot} rather than reading
     * the holder directly because a ladder that cannot see the screen must not
     * run. With no pushed state {@link Snapshot#overlayPresent()} is false, so the
     * ladder would conclude "already-idle" and report success against a POS it has
     * never heard from — the recovery equivalent of a green preflight on a dead
     * register.
     */
    private Snapshot snapshot(String client) {
        return host.requireSnapshot(client);
    }

    private LexToken build(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new ShimException(400, "bad-key", "Key name must not be empty.");
        }
        String name = rawName.trim().toUpperCase();
        LexToken token = LexTokenMaker.createKBDToken(name);
        if (token == null) {
            throw new ShimException(400, "unsupported-key",
                    "The retained LexTokenMaker cannot build key '" + rawName + "'. "
                            + "Supported names: " + supported() + ". Raw Topaz key codes of the "
                            + "form 0xNN are also accepted; note that digit keys are only "
                            + "reachable that way, since LexTokenMaker maps neither \"5\" nor "
                            + "CLEAR to a token.");
        }
        return token;
    }

    /** Metadata for {@code GET /pos/keys}, so the surface documents itself. */
    static Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("named_keys", supported());
        out.put("rejected_names", rejected());
        out.put("raw_codes", "0xNN, looked up in LexTokenMaker's Topaz key-code table");
        out.put("notes", Arrays.asList(
                "Digits are not reachable by name: LexTokenMaker maps neither \"5\" nor CLEAR.",
                "A press reports processed=true only when the POS calls back "
                        + "tokenHasBeenProcessed, so false means sent-and-ignored."));
        return out;
    }

    private static List<String> rejected() {
        List<String> names = new ArrayList<>();
        for (String name : NAMED_KEYS) {
            if (LexTokenMaker.createKBDToken(name) == null) {
                names.add(name);
            }
        }
        return names;
    }
}
