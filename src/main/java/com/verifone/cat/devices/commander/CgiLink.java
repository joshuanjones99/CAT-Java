package com.verifone.cat.devices.commander;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnreachable;

/**
 * The CGILink transport: one POST per command, the session cookie, the OTP retry, and PATS's
 * per-command retry loop. The transport half of the Python CAT's
 * {@code petrotest.devices.config_shim.PDKShim} ({@code _post_cgi_command},
 * {@code _authenticate}, {@code _post_cgi_command_otp_aware}, {@code _send_command_as_pats}),
 * which {@link PdkShim} builds on.
 *
 * <p>A request is {@code cmd=<name>&k=v...}, a blank line, then the XML payload, posted to
 * {@code http://<host>/cgi-bin/CGILink?} with {@code Accept: application/xml} and
 * {@code Content-Type: text/xml; charset=UTF-8} (docs/commander-config-surface.md section 1).
 * The parameters ride in the body, not the URL, and are sent raw, as the Python sends them.
 *
 * <p><b>One connection per request.</b> PATS's {@code RequestManager.executeRequest()} opens a
 * connection per request and disconnects after reading the reply, and the Python CAT copies it
 * (no keep-alive, HANDOFF.md §1.41). The JDK's {@code HttpClient} refuses a
 * {@code Connection: close} header and keeps a pool per client, so each POST here gets a client
 * of its own: no request ever rides a connection an earlier one opened. The JDK closes that
 * client's idle socket when the client is collected, where PATS closes it at once; the
 * Commander's own idle timeout bounds it either way.
 *
 * <p><b>The trailing {@code ?} does not reach the wire.</b> The URL is PATS's property verbatim,
 * {@code ...CGILink?}, and the Python's httpx sends the request line
 * {@code POST /cgi-bin/CGILink? HTTP/1.1}. The JDK's {@code HttpClient} drops an empty query and
 * sends {@code POST /cgi-bin/CGILink HTTP/1.1}, and offers no way to keep it. To Apache's CGI the
 * two differ only in {@code REQUEST_URI}; {@code QUERY_STRING} is empty either way, and the
 * parameters are in the body. Expected to be harmless, and not yet seen on a Commander:
 * {@code PdkShimTest} pins the request line so the difference stays visible.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Nothing here has been sent to a Commander.
 */
final class CgiLink {

    private static final Logger log = LoggerFactory.getLogger(CgiLink.class);

    private static final String LOGIN_REQUIRED = "CGIPortal.LoginRequired";

    /**
     * Where a pause goes: a bounded wall-clock wait in production, a recording double in tests,
     * so a test of the 20-second retry spacing does not wait 20 seconds. The Python's
     * {@code bounded_wait}.
     */
    interface Pauser {

        /** The production pauser: parks until the deadline; an interrupt becomes a {@link DeviceError}. */
        Pauser REAL = duration -> {
            long deadline = System.nanoTime() + duration.toNanos();
            for (long left = duration.toNanos(); left > 0; left = deadline - System.nanoTime()) {
                LockSupport.parkNanos(left);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new DeviceError("interrupted during a pause in the Commander's retry schedule");
                }
            }
        };

        void pause(Duration duration);
    }

    private final String host;
    private final URI endpoint;
    private final Duration timeout;
    private final String password;
    private final boolean passwordMayBeExpired;
    private final Supplier<String> defaultReadOtp;
    private final Pauser pauser;
    private String cookie;

    /**
     * @param host                 the Commander, from the rig file; never a literal here
     * @param port                 80 on a real Commander; a loopback port in tests
     * @param timeout              connect and per-request read timeout
     * @param defaultReadOtp       OTP source used when a call passes none; may be {@code null}
     * @param password             the Commander user's password
     * @param passwordMayBeExpired log in even when {@code validate} says the password expired
     * @param pauser               where the retry pauses go
     */
    CgiLink(String host, int port, Duration timeout, Supplier<String> defaultReadOtp, String password,
            boolean passwordMayBeExpired, Pauser pauser) {
        this.host = host;
        String authority = port == PdkShim.DEFAULT_PORT ? host : host + ":" + port;
        this.endpoint = URI.create("http://" + authority + "/cgi-bin/CGILink?");
        this.timeout = timeout;
        this.defaultReadOtp = defaultReadOtp;
        this.password = password;
        this.passwordMayBeExpired = passwordMayBeExpired;
        this.pauser = pauser;
    }

    /** A parameter map that keeps its order, which is the order the body is sent in. */
    static Map<String, String> params(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    URI endpoint() {
        return endpoint;
    }

    Duration timeout() {
        return timeout;
    }

    Supplier<String> defaultReadOtp() {
        return defaultReadOtp;
    }

    String password() {
        return password;
    }

    Pauser pauser() {
        return pauser;
    }

    /** The cached session cookie, or {@code null}. */
    String cookie() {
        return cookie;
    }

    /** Uses a cookie the Commander has issued (or a test seeds), in place of the cached one. */
    void adoptCookie(String value) {
        cookie = value;
    }

    /**
     * One command: the Python's {@code _post_cgi_command}. A command sent with a session
     * {@code cookie} that faults {@code LoginRequired} (the Commander restarted, or the session
     * aged out) is sent once more with a fresh cookie; a second refusal raises. {@code validate}
     * itself carries no cookie, so it is never retried.
     *
     * @throws DeviceUnreachable the transport failed, or the status is not 2xx
     * @throws DeviceProtocolError the reply is not XML
     * @throws CommanderFault the reply is a fault
     */
    Element post(String command, String payload, Map<String, String> params) {
        try {
            return postOnce(command, payload, params);
        } catch (DeviceError e) {
            // The Python tests the exception's text, so a reply quoting the code is retried too.
            if (params == null || !params.containsKey("cookie") || !String.valueOf(e.getMessage()).contains("LoginRequired")) {
                throw e;
            }
            log.info("{}: {}; logging in again", command, LOGIN_REQUIRED);
            cookie = null;
            Map<String, String> retried = new LinkedHashMap<>(params);
            retried.put("cookie", authenticate());
            return postOnce(command, payload, retried);
        }
    }

    private Element postOnce(String command, String payload, Map<String, String> params) {
        StringBuilder body = new StringBuilder("cmd=").append(command);
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                body.append('&').append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        if (payload != null && !payload.isEmpty()) {
            body.append("\n\n").append(payload);
        }

        HttpResponse<String> response;
        try {
            response = send(body.toString());
        } catch (IOException e) {
            throw new DeviceUnreachable("PDKShim at " + host,
                    "POST " + endpoint + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            // httpx's raise_for_status, which the Python calls, refuses every non-2xx.
            throw new DeviceUnreachable("PDKShim at " + host,
                    "POST " + endpoint + ": HTTPStatusError: HTTP " + status);
        }
        Element root = CgiReplies.parse(response.body(), "POST " + endpoint);
        CgiReplies.checkFault(root);
        return root;
    }

    /**
     * The session cookie, from {@code validate}; cached until something says it is stale.
     *
     * @throws DeviceProtocolError the reply has no cookie
     * @throws CommanderPasswordExpired the password expired and the caller did not allow it
     */
    String authenticate() {
        if (cookie != null && !cookie.isEmpty()) {
            return cookie;
        }
        Element root = post("validate", "", params("user", PdkShim.COMMANDER_USER, "passwd", password));
        Element cookieElement = CgiReplies.descendant(root, "cookie");
        if (cookieElement == null || CgiReplies.text(cookieElement).isEmpty()) {
            throw new DeviceProtocolError("validate",
                    "Response has no <cookie> element: " + CgiReplies.snippet(CgiReplies.serialise(root)));
        }
        checkPasswordExpiry(root);
        cookie = CgiReplies.text(cookieElement);
        return cookie;
    }

    /**
     * Raises {@link CommanderPasswordExpired} when {@code validate} says the password has
     * expired: {@code <passwd expire="1" days="n">} with {@code n} zero or below. An unreadable
     * {@code days} counts as 1, not expired, as in the Python.
     */
    void checkPasswordExpiry(Element root) {
        Element passwd = CgiReplies.descendant(root, "passwd");
        if (passwd == null || !"1".equals(passwd.getAttribute("expire")) || passwordMayBeExpired) {
            return;
        }
        int days;
        try {
            days = Integer.parseInt(passwd.getAttribute("days").strip());
        } catch (NumberFormatException e) {
            days = 1;
        }
        if (days <= 0) {
            throw new CommanderPasswordExpired(host, days);
        }
    }

    /**
     * {@link #post}, retried once with an OTP if the Commander demands one: the Python's
     * {@code _post_cgi_command_otp_aware}.
     *
     * <p>The OTP does not exist until the command is asked for without one: the
     * {@code CGIPortal.OTPRequired} fault is what mints it, and it must then be read over SSH as
     * root ({@code cat /var/logins/tat}) and the command retried with {@code otp=}
     * (config-surface doc section 4). With no reader, from the call or the client, the fault
     * propagates like any other.
     */
    Element postOtpAware(String command, Map<String, String> params, String payload, Supplier<String> readOtp) {
        Supplier<String> otp = readOtp != null ? readOtp : defaultReadOtp;
        try {
            return post(command, payload, params);
        } catch (DeviceError e) {
            if (otp == null || !String.valueOf(e.getMessage()).contains("OTPRequired")) {
                throw e;
            }
            Map<String, String> retried = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
            retried.put("otp", otp.get());
            return post(command, payload, retried);
        }
    }

    /**
     * One command, attempt for attempt as petroapps' {@code CommanderHttpClient.sendCommand()}:
     * the Python's {@code _send_command_as_pats}.
     *
     * <p>Up to {@link PdkShim#PATS_REQUEST_RETRIES} attempts, with a
     * {@link PdkShim#PATS_REQUEST_RETRIES_SLEEP} pause before each attempt after the fourth. An
     * attempt:
     * <ul>
     *   <li>posts {@code cmd=<command>&cookie=<cookie>}, a blank line, then the payload. After an
     *       attempt faults {@code CGIPortal.OTPRequired}, every later attempt reads the OTP and
     *       posts {@code otp=<otp>&cmd=<command>&cookie=<cookie>} at once. Each POST uses a new
     *       connection (see the class comment);</li>
     *   <li>on an I/O error, a read timeout included, pauses 10 s and tries again;</li>
     *   <li>on anything but HTTP 200, tries again;</li>
     *   <li>on {@code CGIPortal.LoginRequired}, logs in again and tries again;</li>
     *   <li>on {@code OTPRequired} or a {@code response.errormessages} text, tries again;</li>
     *   <li>otherwise succeeds. <b>Any other fault counts as success</b>, as in PATS: the caller
     *       reads the text.</li>
     * </ul>
     *
     * <p>One PATS quirk is not copied: {@code sendCommand} throws after its fifteenth attempt
     * even when that attempt succeeded. <b>One PATS wait is deliberately not copied:</b>
     * {@code OtpManager.getCommanderOtp()} pauses 3 x {@code otp.retries.sleep} (15 s) between
     * starting plink and returning the OTP; CAT posts as soon as it has the OTP (rig owner,
     * 2026-09-18, {@code docs/run-time-cuts.md} #6). The read still follows the probe that
     * minted the OTP. Every attempt is logged with its HTTP status and elapsed time.
     *
     * @return the text of the reply that succeeded
     * @throws DeviceError no attempt succeeded, or an OTP was needed and there is no reader
     * @throws DeviceUnreachable from logging in
     * @throws DeviceProtocolError from logging in
     */
    String sendAsPats(String command, String payload, Supplier<String> readOtp) {
        Supplier<String> otp = readOtp != null ? readOtp : defaultReadOtp;
        String session = authenticate();
        boolean withOtp = false;
        for (int attempt = 1; attempt <= PdkShim.PATS_REQUEST_RETRIES; attempt++) {
            if (attempt > 4) {
                pauser.pause(PdkShim.PATS_REQUEST_RETRIES_SLEEP);
            }
            String head;
            if (withOtp) {
                if (otp == null) {
                    throw new DeviceError(command + " needs an OTP and no read_otp was given");
                }
                head = "otp=" + otp.get() + "&cmd=" + command + "&cookie=" + session;
            } else {
                head = "cmd=" + command + "&cookie=" + session;
            }
            long started = System.nanoTime();
            HttpResponse<String> response;
            try {
                response = send(head + "\n\n" + payload);
            } catch (IOException e) {
                log.warn("{} attempt {}: {} after {} s: {}", command, attempt, e.getClass().getSimpleName(),
                        seconds(started), e.getMessage());
                pauser.pause(PdkShim.PATS_SLEEP_10_SECONDS);
                continue;
            }
            String text = response.body() == null ? "" : response.body();
            log.info("{} attempt {}{}: HTTP {} after {} s", command, attempt, withOtp ? " with OTP" : "",
                    response.statusCode(), seconds(started));
            if (response.statusCode() != 200) {
                continue;
            }
            if (text.contains(LOGIN_REQUIRED)) {
                cookie = null;
                session = authenticate();
            } else if (text.contains(PdkShim.OTP_REQUIRED_FAULT)) {
                withOtp = true;
            } else if (PdkShim.PATS_RESPONSE_ERROR_MESSAGES.stream().noneMatch(text::contains)) {
                return text;
            } else {
                log.warn("{} attempt {}: Commander busy: {}", command, attempt,
                        text.substring(0, Math.min(200, text.length())));
            }
        }
        throw new DeviceError(command + ": no successful response in " + PdkShim.PATS_REQUEST_RETRIES + " attempts");
    }

    /**
     * One POST of a raw body, on a connection of its own. Interrupts are restored and surface as
     * a {@link DeviceError}, never as a retryable I/O error.
     */
    HttpResponse<String> send(String body) throws IOException {
        // A client per request: see the class comment. HTTP/1.1 only, so no h2c upgrade is offered.
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/xml")
                .header("Content-Type", "text/xml; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceError("interrupted calling the Commander CGILink at " + host, e);
        }
    }

    private static String seconds(long started) {
        return String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9);
    }
}
