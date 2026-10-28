package com.verifone.cat.provision;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.preflight.Bounded;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * Toggles a pushed Chromium on and off for a register (BROWSER_STRATEGY.md 5.2), starts the CDP relay that exposes
 * it off-box (7), and verifies the controller can reach it. A port of {@code petrotest.provision.browser_override}.
 *
 * <p>5's guiding principle, which every method here honours: never modify, delete or redistribute Vewd's files.
 * Only the executable bit of {@link #RUNBROWSER_PATH} is toggled, and {@link #disable} is what puts it back.
 *
 * <p>Every remote command below is the Python's, byte for byte; each shape records something that broke on the real
 * register (the Python's docstrings carry the incidents, summarised on each method):
 * <ul>
 *   <li>{@link #restartTopazBrowser}: {@code /home/topaz/bin/stopapp}'s own browser-stop stanza (TERM, 2 s, KILL,
 *       2 s, QUIT, 1 s, remove the pidfile), then {@code topaz.sh} as {@code topaz} under {@code nohup} with the
 *       recorded startup mode as its argument.</li>
 *   <li>{@link #startCdpRelay}: the boot script when pushed; otherwise the double-backgrounded
 *       {@code nohup su - topaz -c '...' &}, because a single level of {@code &} hung the SSH channel.</li>
 *   <li>{@link #installRelayBootHook}: one guarded block in {@code rc.local}, before its final {@code exit 0}.</li>
 *   <li>{@link #enable}: no restart when loopback CDP already answers and the override files are unchanged since that
 *       browser launched (by content when stamped, by date otherwise).</li>
 * </ul>
 *
 * <p>Deviations from the Python: the CDP probe uses the JDK's {@link HttpClient} (HTTP/1.1) where the Python uses
 * httpx; {@code rc.local} is split into lines on {@code \n} only (Python's {@code splitlines} also splits on
 * {@code \r} and other separators, which a Unix {@code rc.local} does not hold); a relay port of {@code null} is the
 * Python's {@code relay_port=None}. {@link EnableBrowserOverride} is {@linkplain ChecklistStep#required() required}:
 * the Python has no such notion, but a register that opted into the override serves no CDP without it, so a step that
 * did not run must fail the target.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's chmod toggle and relay functions were verified on topaz101;
 * this port has only run against {@code InMemoryTransport} and a loopback HTTP server.
 */
public final class BrowserOverride {

    /** Vewd's own launcher. Only its executable bit is ever touched. */
    public static final String RUNBROWSER_PATH = "/usr/local/bin/runbrowser";

    /** The wrapper the {@code chromium_override} artifact pushes: a new file beside Vewd's, never an overwrite. */
    public static final String CHROMIUM_WRAPPER_PATH = "/usr/local/bin/chromium";

    /** The Chrome for Testing managed policy the same artifact pushes; read only at launch. */
    public static final String CHROMIUM_POLICY_PATH =
            "/etc/opt/chrome_for_testing/policies/managed/petrotest-no-infobars.json";

    /** {@code topaz.sh}'s pidfile for the browser it launched. It no-ops on launch while this names a live process. */
    public static final String VM_PID_FILE = "/var/run/apps/vm.pid";

    /** The sha256sum of the wrapper and policy the running browser launched with. /var/run is emptied by a reboot. */
    public static final String LAUNCH_STAMP_FILE = "/var/run/apps/petrotest-override.sha256";

    /**
     * How long a restarted browser gets to answer CDP through the relay. On topaz101, 2026-09-18, it answered after
     * the 30 s SSH timeout had run out (HANDOFF.md 1.62). A bound, not a pause.
     */
    public static final Duration BROWSER_START = Duration.ofSeconds(90);

    /** {@code topaz.sh} refuses to run as any user but {@code topaz}. */
    static final String TOPAZ_SH = "/home/topaz/bin/topaz.sh";

    /** The default timeout of {@link #restartTopazBrowser} as the checklist's restart callback, as in Python. */
    public static final Duration RESTART_TIMEOUT = Duration.ofSeconds(30);

    /** Where the {@code cdp_relay} artifact pushes the relay source. */
    public static final String RELAY_JAVA_PATH = "/home/topaz/browser/chromium/CdpRelay.java";

    public static final String RELAY_PID_FILE = "/tmp/cdp-relay.pid";
    public static final String RELAY_LOG_FILE = "/tmp/cdp-relay.log";

    /** Pushed beside {@code CdpRelay.java}: keeps the relay running, and relaunches it at boot. */
    public static final String RELAY_BOOT_SCRIPT_PATH = "/home/topaz/browser/chromium/cdp-relay-boot.sh";

    /** The platform's own boot-customization hook: {@code rc.run} backgrounds it near the end of boot. */
    public static final String RC_LOCAL_PATH = "/etc/rc.d/rc.local";

    static final String BOOT_HOOK_BEGIN = "# >>> petrotest cdp_relay boot hook (BROWSER_STRATEGY.md §7) >>>";
    static final String BOOT_HOOK_END = "# <<< petrotest cdp_relay boot hook <<<";

    /** {@code java} is not on PATH for a bare SSH exec on the register; the bundled JRE's path. */
    public static final String JAVA_BIN = "/usr/java/j2se/bin/java";

    /** {@code chromium-wrapper.sh}'s hardcoded {@code --remote-debugging-port}. */
    public static final int DEFAULT_CDP_PORT = 9220;

    /** The relay port 5.6/7's hand-run relay proved on topaz101. */
    public static final int DEFAULT_RELAY_PORT = 19220;

    /** Matches only the relay's own process, by command line. One relay per register. */
    static final String RELAY_PROCESS_PATTERN = "CdpRelay.java";

    private static final String OVERRIDE_FILES = CHROMIUM_WRAPPER_PATH + " " + CHROMIUM_POLICY_PATH;

    /** The longest single CDP probe inside the retry loop, as the Python's {@code min(5.0, timeout)}. */
    private static final Duration PROBE_CAP = Duration.ofSeconds(5);

    private static final Duration VERIFY_INTERVAL = Duration.ofSeconds(1);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One client for every probe: a JDK 17 client cannot be closed, so one per probe would leak its selector. */
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private BrowserOverride() {}

    /**
     * Whatever makes the register's browser re-evaluate which binary to launch. Null means "do not restart": this
     * class assumes no mechanism a caller did not supply. May throw {@link DeviceError}.
     */
    @FunctionalInterface
    public interface Restart {
        void restart(Transport transport);
    }

    /** The checklist's restart callback: {@link #restartTopazBrowser} with the Python's default timeout. */
    public static final Restart TOPAZ_RESTART = transport -> restartTopazBrowser(transport, RESTART_TIMEOUT);

    /**
     * The real {@link Restart} for a Topaz register: {@code stopapp}'s browser-stop stanza, then {@code topaz.sh} as
     * {@code topaz}.
     *
     * <p>{@code nohup} around the whole {@code topaz.sh} (2026-09-11: without it the SSH session's SIGHUP killed the
     * browser within seconds of the connection closing). Not {@code setsid}: the 1052 image has none (2026-09-21).
     * The recorded mode in {@code ~/.topaz.startup} goes back in as the argument, or an unpaired register is sent to
     * {@code appselectorclient} and {@code psi-init} finds no pairing screen.
     *
     * @throws DeviceError when the launch exits non-zero, so a register is never silently left with no browser
     */
    public static void restartTopazBrowser(Transport transport, Duration timeout) {
        transport.run("pid=$(cat " + VM_PID_FILE + " 2>/dev/null); "
                + "if [ -n \"$pid\" ] && ps -p \"$pid\" > /dev/null 2>&1; then "
                + "kill -TERM \"$pid\"; sleep 2; "
                + "ps -p \"$pid\" > /dev/null 2>&1 && kill -KILL \"$pid\"; sleep 2; "
                + "ps -p \"$pid\" > /dev/null 2>&1 && kill -QUIT \"$pid\"; sleep 1; "
                + "fi; rm -f " + VM_PID_FILE, timeout);
        CommandResult started = transport.run(topazLaunchCommand(), timeout);
        if (!started.ok()) {
            String said = (started.stderr().isEmpty() ? started.stdout() : started.stderr()).strip();
            throw new DeviceError("restarting the browser: `su - topaz -c 'nohup " + TOPAZ_SH + " $mode &'` exited "
                    + started.exitStatus() + ": " + said.substring(0, Math.min(300, said.length())));
        }
    }

    static String topazLaunchCommand() {
        return "su - topaz -c 'mode=$(cat /home/topaz/.topaz.startup 2>/dev/null); "
                + "nohup " + TOPAZ_SH + " $mode </dev/null >/dev/null 2>&1 &'";
    }

    /**
     * Launches {@code CdpRelay.java} in the background, as {@code topaz}. Idempotent.
     *
     * <p>The boot script, when pushed, is the one way to start it (2026-10-14): it keeps the JVM alive and replaces a
     * relay running an older source. Otherwise the whole {@code su -c} is backgrounded one level further out: a single
     * level of {@code &} left the SSH channel open past its timeout (2026-08-27). The PID is found afterwards by
     * {@code pgrep -f}, not {@code $!}.
     */
    public static StepResult startCdpRelay(Transport transport, int relayPort, int cdpPort, Duration timeout) {
        String step = "start_cdp_relay";
        if (!transport.run("test -f " + RELAY_JAVA_PATH, timeout).ok()) {
            return new StepResult(step, StepStatus.FAILED, RELAY_JAVA_PATH
                    + " is missing — push the `cdp_relay` artifact first");
        }
        if (transport.run("test -x " + RELAY_BOOT_SCRIPT_PATH, timeout).ok()) {
            CommandResult launched = transport.run("nohup " + RELAY_BOOT_SCRIPT_PATH
                    + " < /dev/null > /dev/null 2>&1 &", timeout);
            if (!launched.ok()) {
                return new StepResult(step, StepStatus.FAILED, RELAY_BOOT_SCRIPT_PATH + " exited "
                        + launched.exitStatus() + ": " + launched.stderr().strip());
            }
            return new StepResult(step, StepStatus.DONE, RELAY_BOOT_SCRIPT_PATH + " run: the relay 0.0.0.0:"
                    + relayPort + " -> 127.0.0.1:" + cdpPort + " is kept running (restarted if it exits, replaced"
                    + " if its source changed) — see verify_cdp_reachable");
        }

        CommandResult existing = transport.run("pgrep -f " + RELAY_PROCESS_PATTERN, timeout);
        List<String> matches = existing.ok() ? words(existing.stdout()) : List.of();
        if (!matches.isEmpty()) {
            return new StepResult(step, StepStatus.DONE, "relay already running (matching pid(s): "
                    + String.join(", ", matches) + ") — left alone");
        }

        String relayDir = RELAY_JAVA_PATH.substring(0, RELAY_JAVA_PATH.lastIndexOf('/'));
        String inner = "cd " + relayDir + " && nohup " + JAVA_BIN + " CdpRelay.java " + relayPort + " " + cdpPort
                + " < /dev/null > " + RELAY_LOG_FILE + " 2>&1 & "
                + "sleep 1; pgrep -f " + RELAY_PROCESS_PATTERN + " | head -1 > " + RELAY_PID_FILE;
        CommandResult launch = transport.run("nohup su - topaz -c '" + inner + "' < /dev/null > /dev/null 2>&1 &",
                timeout);
        if (!launch.ok()) {
            return new StepResult(step, StepStatus.FAILED, "launching the relay exited " + launch.exitStatus() + ": "
                    + launch.stderr().strip());
        }
        return new StepResult(step, StepStatus.DONE, "relay launch requested: 0.0.0.0:" + relayPort
                + " -> 127.0.0.1:" + cdpPort + " — not yet confirmed reachable off-box (nor that it has actually"
                + " started — the launch itself is backgrounded); see verify_cdp_reachable");
    }

    /**
     * Kills the relay by command line ({@code pkill -f}), never by its pidfile: during the launch window
     * {@code pgrep}'s first match can be the wrapper, not java (2026-08-27). Never fails on "nothing was running".
     */
    public static StepResult stopCdpRelay(Transport transport, Duration timeout) {
        transport.run("pkill -f " + RELAY_PROCESS_PATTERN + " 2>/dev/null; rm -f " + RELAY_PID_FILE + "; true",
                timeout);
        return new StepResult("stop_cdp_relay", StepStatus.DONE, "relay stopped (or was not running)");
    }

    static String bootHookBlock() {
        return BOOT_HOOK_BEGIN + "\n"
                + "[ -x " + RELAY_BOOT_SCRIPT_PATH + " ] && " + RELAY_BOOT_SCRIPT_PATH + " &\n"
                + BOOT_HOOK_END + "\n";
    }

    /**
     * Makes the relay survive a reboot: one guarded block in {@code rc.local}, inserted before its last non-blank
     * line (its final {@code exit 0}). Idempotent by marker. A read or write that fails or times out is a FAILED
     * result, never a throw (2026-09-16: a slow read once failed a whole stage through here). Not yet exercised through
     * an actual reboot, in Python or here.
     */
    public static StepResult installRelayBootHook(Transport transport, Duration timeout) {
        String step = "install_relay_boot_hook";
        byte[] current;
        try {
            current = transport.get(RC_LOCAL_PATH, timeout).orElse(null);
        } catch (DeviceError | Bounded.TimeoutExpired e) {
            return new StepResult(step, StepStatus.FAILED, "could not read " + RC_LOCAL_PATH + ": " + e.getMessage());
        }
        if (current == null) {
            return new StepResult(step, StepStatus.FAILED, RC_LOCAL_PATH
                    + " is missing or unreadable — cannot install the boot hook");
        }
        String text = new String(current, StandardCharsets.UTF_8);
        if (text.contains(BOOT_HOOK_BEGIN)) {
            return new StepResult(step, StepStatus.DONE, "boot hook already present in " + RC_LOCAL_PATH
                    + " — left alone");
        }
        List<String> lines = linesKeepingEnds(text);
        int insertAt = lines.size();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).strip().isEmpty()) {
                insertAt = i;
                break;
            }
        }
        StringBuilder updated = new StringBuilder();
        for (int i = 0; i < insertAt; i++) {
            updated.append(lines.get(i));
        }
        updated.append(bootHookBlock());
        for (int i = insertAt; i < lines.size(); i++) {
            updated.append(lines.get(i));
        }
        try {
            transport.put(RC_LOCAL_PATH, updated.toString().getBytes(StandardCharsets.UTF_8), 0755, timeout);
        } catch (DeviceError | Bounded.TimeoutExpired e) {
            return new StepResult(step, StepStatus.FAILED, "could not write " + RC_LOCAL_PATH + ": " + e.getMessage());
        }
        return new StepResult(step, StepStatus.DONE, "inserted a guarded call to " + RELAY_BOOT_SCRIPT_PATH
                + " before " + RC_LOCAL_PATH + "'s final command — the relay now relaunches itself on the next"
                + " boot");
    }

    /** Undoes {@link #installRelayBootHook}: deletes exactly the marked block. Reports trouble, never throws. */
    public static StepResult removeRelayBootHook(Transport transport, Duration timeout) {
        String step = "remove_relay_boot_hook";
        byte[] current;
        try {
            current = transport.get(RC_LOCAL_PATH, timeout).orElse(null);
        } catch (DeviceError | Bounded.TimeoutExpired e) {
            return new StepResult(step, StepStatus.FAILED, "could not read " + RC_LOCAL_PATH + ": " + e.getMessage());
        }
        if (current == null) {
            return new StepResult(step, StepStatus.DONE, RC_LOCAL_PATH
                    + " is missing or unreadable — nothing to remove");
        }
        String text = new String(current, StandardCharsets.UTF_8);
        if (!text.contains(BOOT_HOOK_BEGIN)) {
            return new StepResult(step, StepStatus.DONE, "boot hook not present — nothing to remove");
        }
        StringBuilder kept = new StringBuilder();
        boolean skipping = false;
        for (String line : linesKeepingEnds(text)) {
            String stripped = line.strip();
            if (stripped.equals(BOOT_HOOK_BEGIN)) {
                skipping = true;
                continue;
            }
            if (stripped.equals(BOOT_HOOK_END)) {
                skipping = false;
                continue;
            }
            if (!skipping) {
                kept.append(line);
            }
        }
        try {
            transport.put(RC_LOCAL_PATH, kept.toString().getBytes(StandardCharsets.UTF_8), 0755, timeout);
        } catch (DeviceError | Bounded.TimeoutExpired e) {
            return new StepResult(step, StepStatus.FAILED, "could not write " + RC_LOCAL_PATH + ": " + e.getMessage());
        }
        return new StepResult(step, StepStatus.DONE, "removed the boot hook from " + RC_LOCAL_PATH);
    }

    /**
     * One raw-CDP reachability probe: GET {@code /json/version}, then {@code /json/list}. HTTP, not a websocket to a
     * guessed path: stock Chromium 404s a bare {@code /devtools/browser} (2026-08-27). A 200 whose body is not the
     * JSON object DevTools serves is a browser not ready yet. Bounded by {@code timeout}.
     */
    public static StepResult verifyCdpReachable(String host, int port, Duration timeout) {
        String versionUrl = "http://" + host + ":" + port + "/json/version";
        String listUrl = "http://" + host + ":" + port + "/json/list";
        String browser;
        String listBody;
        try {
            String versionBody = get(versionUrl, timeout);
            JsonNode version = JSON.readTree(versionBody);
            if (version == null || !version.isObject()) {
                throw new IllegalStateException("not a JSON object: "
                        + versionBody.substring(0, Math.min(80, versionBody.length())));
            }
            browser = version.has("Browser") ? version.get("Browser").asText() : "?";
            listBody = get(listUrl, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StepResult("verify_cdp_reachable", StepStatus.FAILED, "could not reach " + versionUrl
                    + ": interrupted");
        } catch (Exception e) {
            return new StepResult("verify_cdp_reachable", StepStatus.FAILED, "could not reach " + versionUrl + ": "
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        int targets;
        try {
            JsonNode list = JSON.readTree(listBody);
            if (list == null || !list.isContainerNode()) {
                throw new IllegalStateException("not JSON");
            }
            targets = list.size();
        } catch (Exception e) {
            return new StepResult("verify_cdp_reachable", StepStatus.DONE, "connected, browser=" + browser + ", but "
                    + listUrl + " wasn't JSON");
        }
        return new StepResult("verify_cdp_reachable", StepStatus.DONE, "connected, browser=" + browser + ", "
                + targets + " target(s) reported");
    }

    private static String get(String url, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        HttpResponse<String> response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    /**
     * Polls {@link #verifyCdpReachable} until it answers or {@code timeout} passes: the relay launch is backgrounded,
     * so a single probe straight after it can race (2026-08-27). Returns the last probe's result.
     */
    static StepResult verifyCdpReachableWithRetry(String host, int port, Duration timeout) {
        Duration each = timeout.compareTo(PROBE_CAP) < 0 ? timeout : PROBE_CAP;
        StepResult[] last = new StepResult[1];
        try {
            Poll.until(() -> {
                last[0] = verifyCdpReachable(host, port, each);
                return last[0].status() == StepStatus.DONE ? last[0] : null;
            }, timeout, VERIFY_INTERVAL, "cdp relay reachability");
        } catch (Bounded.TimeoutExpired e) {
            // The last probe's own detail says why; the deadline adds nothing.
        }
        return last[0];
    }

    /**
     * True when {@code 127.0.0.1:<cdpPort>} on the register already answers CDP's {@code /json/version}: curl to
     * loopback, not through the relay, which can be down for reasons that have nothing to do with the browser.
     */
    static boolean locallyServingCdp(Transport transport, int cdpPort, Duration timeout) {
        long seconds = Math.max(1, Math.min(timeout.getSeconds(), 5));
        CommandResult result = transport.run("curl -s -o /dev/null -w '%{http_code}' --max-time " + seconds
                + " http://127.0.0.1:" + cdpPort + "/json/version", timeout);
        return result.ok() && result.stdout().strip().equals("200");
    }

    /**
     * True when the wrapper or policy differs from what the running browser launched with: by content when
     * {@link #LAUNCH_STAMP_FILE} is there (a {@code provision --force} of the same bytes must not restart the POS, run
     * {@code 4a04258badad}), by date against {@link #VM_PID_FILE} when it is not.
     */
    static boolean overrideChangedSinceLaunch(Transport transport, Duration timeout) {
        CommandResult result = transport.run("if [ -f " + LAUNCH_STAMP_FILE + " ]; then "
                + "[ \"$(sha256sum " + OVERRIDE_FILES + " 2>/dev/null)\" = \"$(cat " + LAUNCH_STAMP_FILE + ")\" ] "
                + "|| echo changed; "
                + "else find " + OVERRIDE_FILES + " -newer " + VM_PID_FILE + " 2>/dev/null; fi", timeout);
        return !result.stdout().strip().isEmpty();
    }

    /** Records the wrapper and policy the running browser launched with. Best effort. */
    static void stampLaunchedOverride(Transport transport, Duration timeout) {
        transport.run("sha256sum " + OVERRIDE_FILES + " > " + LAUNCH_STAMP_FILE + " 2>/dev/null "
                + "|| rm -f " + LAUNCH_STAMP_FILE, timeout);
    }

    /**
     * Makes the register run the pushed Chromium instead of Vewd, for this session.
     *
     * <ol>
     *   <li>The wrapper must be present and executable: enabling nothing is FAILED.</li>
     *   <li>{@code chmod -x} Vewd's {@code runbrowser}.</li>
     *   <li>If loopback CDP already answers and the override files are unchanged since that browser launched, no
     *       restart (2026-09-11: bouncing a fine browser broke a working register). Otherwise {@code restart}, or,
     *       with none, a DONE that says plainly the browser was not restarted.</li>
     *   <li>Unless {@code relayPort} is null: start the relay (its failure fails this), install the boot hook (best
     *       effort, never fails this), and poll off-box CDP reachability, up to {@link #BROWSER_START} after a
     *       restart.</li>
     * </ol>
     *
     * @throws DeviceError from the transport or the restart callback, as the Python's {@code TransportError}
     */
    public static StepResult enable(Transport transport, Duration timeout, Restart restart, Integer relayPort,
            int cdpPort) {
        String step = "enable_browser_override";
        if (!transport.run("test -x " + CHROMIUM_WRAPPER_PATH, timeout).ok()) {
            return new StepResult(step, StepStatus.FAILED, CHROMIUM_WRAPPER_PATH + " is missing or not executable"
                    + " — push the `chromium_override` artifact (provision --rig ...) before enabling");
        }
        CommandResult chmod = transport.run("chmod -x " + RUNBROWSER_PATH, timeout);
        if (!chmod.ok()) {
            return new StepResult(step, StepStatus.FAILED, "chmod -x " + RUNBROWSER_PATH + " exited "
                    + chmod.exitStatus() + ": " + chmod.stderr().strip());
        }

        boolean restarted = false;
        boolean serving = locallyServingCdp(transport, cdpPort, timeout);
        boolean stale = serving && overrideChangedSinceLaunch(transport, timeout);
        String detail;
        if (serving && !stale) {
            stampLaunchedOverride(transport, timeout);
            detail = RUNBROWSER_PATH + " disabled, " + CHROMIUM_WRAPPER_PATH + " confirmed executable; 127.0.0.1:"
                    + cdpPort + " already answering CDP — restart skipped, nothing to gain from bouncing a"
                    + " register that is already serving the override";
        } else if (restart == null) {
            return new StepResult(step, StepStatus.DONE, RUNBROWSER_PATH + " disabled and " + CHROMIUM_WRAPPER_PATH
                    + " confirmed executable; no restart callback supplied — the browser process has NOT been"
                    + " restarted, and nothing here confirms Chromium is actually serving posuiapp (§5.5)");
        } else {
            restart.restart(transport);
            restarted = true;
            stampLaunchedOverride(transport, timeout);
            detail = RUNBROWSER_PATH + " disabled, " + CHROMIUM_WRAPPER_PATH + " confirmed executable, restart"
                    + " callback invoked"
                    + (stale ? " (wrapper or policy changed since the running browser launched)" : "")
                    + " — NOT independently confirmed to be serving Chromium (§5.5 needs a CDP"
                    + " Browser.getVersion probe for that, not done here)";
        }

        if (relayPort == null) {
            return new StepResult(step, StepStatus.DONE, detail + "; relay_port=None, relay not started", restarted);
        }
        StepResult relay = startCdpRelay(transport, relayPort, cdpPort, timeout);
        detail += "; " + relay.detail();
        if (relay.status() != StepStatus.DONE) {
            return new StepResult(step, StepStatus.FAILED, detail, restarted);
        }
        // Best-effort hardening for the next boot: never turns a working enable into a FAILED one.
        StepResult bootHook = installRelayBootHook(transport, timeout);
        detail += "; boot hook: " + bootHook.detail();

        Duration reach = restarted && BROWSER_START.compareTo(timeout) > 0 ? BROWSER_START : timeout;
        StepResult verify = verifyCdpReachableWithRetry(transport.host(), relayPort, reach);
        detail += "; off-box reachability: " + verify.detail();
        return new StepResult(step, verify.status() == StepStatus.DONE ? StepStatus.DONE : StepStatus.FAILED, detail,
                restarted);
    }

    /** {@link #enable} with the relay on its default ports. */
    public static StepResult enable(Transport transport, Duration timeout, Restart restart) {
        return enable(transport, timeout, restart, DEFAULT_RELAY_PORT, DEFAULT_CDP_PORT);
    }

    /**
     * Restores Vewd: stops the relay and removes its boot hook (when {@code stopRelay}), {@code chmod +x}
     * {@code runbrowser}, removes the wrapper, then restarts if a callback is given. Run at teardown, unconditionally.
     * A wrapper that cannot be removed is reported but is not FAILED: {@code runbrowser} is already restored.
     */
    public static StepResult disable(Transport transport, Duration timeout, Restart restart, boolean stopRelay) {
        String step = "disable_browser_override";
        List<String> parts = new ArrayList<>();
        if (stopRelay) {
            parts.add(stopCdpRelay(transport, timeout).detail());
            parts.add(removeRelayBootHook(transport, timeout).detail());
        }
        CommandResult chmod = transport.run("chmod +x " + RUNBROWSER_PATH, timeout);
        if (!chmod.ok()) {
            parts.add("chmod +x " + RUNBROWSER_PATH + " exited " + chmod.exitStatus() + ": " + chmod.stderr().strip());
            return new StepResult(step, StepStatus.FAILED, String.join("; ", parts));
        }
        CommandResult remove = transport.run("rm -f " + CHROMIUM_WRAPPER_PATH, timeout);
        if (!remove.ok()) {
            parts.add(RUNBROWSER_PATH + " restored; failed to remove " + CHROMIUM_WRAPPER_PATH + " (exit "
                    + remove.exitStatus() + ": " + remove.stderr().strip() + ")");
        } else {
            parts.add(RUNBROWSER_PATH + " restored, " + CHROMIUM_WRAPPER_PATH + " removed");
        }
        if (restart != null) {
            restart.restart(transport);
            parts.add("restart callback invoked");
        } else {
            parts.add("no restart callback supplied — browser process not restarted");
        }
        return new StepResult(step, StepStatus.DONE, String.join("; ", parts));
    }

    private static List<String> words(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? List.of() : Arrays.asList(stripped.split("\\s+"));
    }

    /** {@code text} split after each {@code \n}, each line keeping its end. */
    static List<String> linesKeepingEnds(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /**
     * The checklist step around {@link #enable}, for a register whose rig entry sets {@code browser_override}.
     * {@linkplain #required() Required}: such a register serves no CDP until this has run, so a skip fails the target.
     */
    public record EnableBrowserOverride(Restart restart, Integer relayPort, int cdpPort) implements ChecklistStep {

        /** No restart callback, the relay on its default ports: the Python's {@code EnableBrowserOverride()}. */
        public EnableBrowserOverride() {
            this(null, DEFAULT_RELAY_PORT, DEFAULT_CDP_PORT);
        }

        public EnableBrowserOverride(Restart restart) {
            this(restart, DEFAULT_RELAY_PORT, DEFAULT_CDP_PORT);
        }

        @Override
        public String name() {
            return "enable_browser_override";
        }

        @Override
        public boolean required() {
            return true;
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            return enable(transport, timeout, restart, relayPort, cdpPort);
        }
    }

    /** The checklist step around {@link #disable}. Not in any default checklist, as in Python. */
    public record DisableBrowserOverride(Restart restart, boolean stopRelay) implements ChecklistStep {

        public DisableBrowserOverride() {
            this(null, true);
        }

        @Override
        public String name() {
            return "disable_browser_override";
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            return disable(transport, timeout, restart, stopRelay);
        }
    }
}
