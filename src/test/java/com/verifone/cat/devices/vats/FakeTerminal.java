package com.verifone.cat.devices.vats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An in-memory {@link VatsAgent}: {@code agent.py}'s ops over the Python CAT's stand-in SDK
 * ({@code tests/unit/vats_fake_sdk.py}), modelled op for op so {@link VatsPinpad} is driven without a Python. The
 * "terminal" is a screen of lines and buttons; an insert, a removal and each CONFIRM can move it to the next screen of
 * a queue; every SDK call is recorded by the stand-in SDK's name. Patterns are matched as the fake SDK matches them
 * ({@code re.search(pattern, lines + buttons, DOTALL | MULTILINE)}), with Java's regex engine, which reads every pattern
 * {@link VatsPinpad} builds the same way.
 *
 * <p>Waits never wait: what the agent would loop on until its budget ran out answers at once with that outcome. What
 * the real terminal does is not modelled. Evidence about this repository only.
 */
final class FakeTerminal implements VatsAgent {

    /** One SDK call: its stand-in name and arguments. */
    record Call(String name, List<Object> args) {}

    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    List<String> lines = new ArrayList<>();
    List<String> buttons = new ArrayList<>();
    final Deque<List<String>> afterConfirm = new ArrayDeque<>();
    final Deque<List<String>> afterInsert = new ArrayDeque<>();
    final Deque<List<String>> afterRemove = new ArrayDeque<>();
    int cardin = -1;
    String pendingKeys = "";
    boolean redrawing = true;
    private boolean cardIn;
    boolean closed;

    /** What the "terminal" shows, and the screens nothing queued yet; the stand-in SDK's {@code show}. */
    FakeTerminal show(List<String> shown, String... shownButtons) {
        lines = new ArrayList<>(shown);
        buttons = new ArrayList<>(List.of(shownButtons));
        afterConfirm.clear();
        afterInsert.clear();
        afterRemove.clear();
        cardin = -1;
        pendingKeys = "";
        redrawing = true;
        return this;
    }

    List<String> names() {
        synchronized (calls) {
            return calls.stream().map(Call::name).toList();
        }
    }

    List<Object> argsOf(String name) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.name().equals(name)).map(c -> c.args().get(0)).toList();
        }
    }

    private void record(String name, Object... args) {
        calls.add(new Call(name, List.of(args)));
    }

    private boolean search(String pattern) {
        List<String> all = new ArrayList<>(lines);
        all.addAll(buttons);
        return Pattern.compile(pattern, Pattern.DOTALL | Pattern.MULTILINE).matcher(String.join("\n", all)).find();
    }

    private Map<String, Object> screen(Map<String, Object> result) {
        result.put("lines", new ArrayList<>(lines));
        return result;
    }

    private static Map<String, Object> of(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private void advance(Deque<List<String>> queue) {
        if (!queue.isEmpty()) {
            lines = new ArrayList<>(queue.pollFirst());
        }
    }

    @Override
    public synchronized Map<String, Object> call(String op, double budgetSeconds, Map<String, Object> args) {
        switch (op) {
            case "hello":
                record("set_terminal_specific_values", args.get("response_ms"));
                return of("model", "M400", "version", "'ADK VERS':<ADK-x>,'V/OS2':<release-x>", "os", "VOS2",
                        "region", "/0");
            case "screen":
                return screen(of());
            case "wait": {
                String pattern = (String) args.get("pattern");
                if (Boolean.TRUE.equals(args.get("gone"))) {
                    return screen(of("status", !search(pattern)));
                }
                record("gui_any_wait_region_contains", pattern, args.get("timeout_ms"));
                return screen(of("status", search(pattern)));
            }
            case "update":
                record("wait_html_display_update", args.get("timeout_ms"));
                return screen(of("status", redrawing));
            case "shows": {
                List<Boolean> shown = new ArrayList<>();
                for (Object p : (List<?>) args.get("patterns")) {
                    shown.add(search((String) p));
                }
                return of("shown", shown);
            }
            case "settle": {
                String watch = (String) args.getOrDefault("watch", "");
                boolean seen = !watch.isEmpty() && search(watch);
                boolean rest = !redrawing && !search((String) args.get("busy"));
                return screen(of("status", rest, "seen", seen));
            }
            case "press": {
                String label = (String) args.get("label");
                String pattern = PyText.escape(label);
                if (!search(pattern)) {
                    return screen(of("status", false, "reason", PyText.repr(label) + " never shown"));
                }
                record("gui_press_button", pattern);
                boolean hit = buttons.stream().anyMatch(b -> Pattern.compile(pattern).matcher(b).find());
                return screen(of("status", hit, "action", hit ? "[('action', 'return " + label + "')]" : "None"));
            }
            case "keys": {
                String keys = (String) args.get("keys");
                record("enter_keys", keys);
                if (keys.contains("<CONFIRM>")) {
                    advance(afterConfirm);
                }
                return of("status", true);
            }
            case "insert":
                record("insert_smartcard");
                cardIn = true;
                advance(afterInsert);
                return of("status", true);
            case "remove":
                record("remove_smartcard");
                if (cardIn && cardin == 1) {
                    // As the lab M400 on 2026-10-13: a removal clears the reader only after this session's insert.
                    cardin = 0;
                }
                cardIn = false;
                advance(afterRemove);
                return of("status", true);
            case "card_state":
                return of("state", cardIn);
            case "cardin":
                record("get_cardin_status");
                return of("present", cardin);
            case "keybuf":
                return of("keys", pendingKeys);
            case "swipe":
                record("swipe_magcard", args.get("track1"), args.get("track2"), args.get("track3"));
                return of("status", true);
            case "comm":
                if (!"eth0".equalsIgnoreCase((String) args.get("interface"))) {
                    throw new AgentError("VATS comm: ValueError: unknown interface " + PyText.repr(
                            (String) args.get("interface")));
                }
                return of("config", of("IP_Addr", "192.0.2.10", "DHCP", "True"));
            default:
                throw new AgentError("VATS " + op + ": ValueError: unknown op " + PyText.repr(op));
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
