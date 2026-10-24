package com.verifone.cat.pats.runner.handlers.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.validate.PyRepr;

/**
 * {@code SEND_HTTP_REQUEST} with {@code device CMDR}: the Commander's CGILink, as PATS's
 * {@code CommanderConfigClient} sends it (the Python's {@code _commander_request}, attempt for attempt).
 *
 * <p>The command rides in the URL's query with the session cookie, and the payload file is the whole body. A cookie
 * is kept per user name for the run. A {@code LoginRequired} reply logs in again and sends once more; an
 * {@code OTPRequired} reply sends once more with the OTP the reader gives. A busy Commander, or a transport failure,
 * waits {@link HttpContext#commanderRetryPause} and tries again, up to {@link HttpContext#commanderAttempts} times.
 * A fault reply is a success, as in PATS.
 *
 * <p>Java's {@code HttpClient} sends the URL's query as it is; the Python's httpx does the same, so the
 * {@code %25} doubling of PATS's {@code UrlBuilder} is reproduced exactly.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the CGILink has not been seen answering a command sent with its {@code cmd} in
 * the query and its document as the body (PATS's new form).
 */
final class CommanderCgi {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** {@code CGIPortal.LoginRequired}: the session has gone, log in again. */
    static final String LOGIN_REQUIRED = "CGIPortal.LoginRequired";
    /** {@code CGIPortal.OTPRequired}: the command needs the one-time password. */
    static final String OTP_REQUIRED = "CGIPortal.OTPRequired";
    /** {@code response.errormessages}: the Commander is busy; try again later. */
    static final String BUSY = "Unable to process your request. Please try again later.";
    /** {@code request.cookie.command}: the command that issues a session cookie. */
    static final String COOKIE_COMMAND = "validate";

    private static final String CHARSET_XML = "text/xml; charset=UTF-8";
    private static final String HEX = "0123456789ABCDEF";

    private final HttpContext context;

    CommanderCgi(HttpContext context) {
        this.context = context;
    }

    /** One CGILink request for the table's command, method, user and password, as PATS sends it. */
    HttpReply request(CaseRun run, RunnableAction action, String command, String body) {
        String cmd = command.strip();
        if (cmd.isEmpty()) {
            throw new PatsStepError("Command cannot be blank");
        }
        for (char c : "?&=,".toCharArray()) {
            if (cmd.indexOf(c) >= 0) {
                throw new PatsStepError(PyRepr.reprString(command)
                        + ": a CGILink command with its own query is not ported");
            }
        }
        String method = action.get("METHOD", "GET").toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new PatsStepError("Unsupported HTTP method for the Commander: " + method);
        }
        PdkShim commander = run.fixture(FixtureName.COMMANDER_CONFIG, PdkShim.class);
        String user = action.get("USERNAME");
        if (user.strip().isEmpty()) {
            user = PdkShim.COMMANDER_USER;
        }
        String password = action.get("PASSWORD");
        if (password.strip().isEmpty()) {
            password = commander.password();
        }
        byte[] content = method.equals("POST") && body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        Session session = new Session(run, commander, cmd, method, user, password, content);
        for (int number = 1; number <= context.commanderAttempts(); number++) {
            try {
                HttpReply response = session.attempt();
                if (!text(response).contains(BUSY)) {
                    return asReply(response);
                }
                LOG.warn("{}: {} attempt {}: the Commander is busy", run.kase().key(), cmd, number);
            } catch (IOException e) {
                LOG.warn("{}: {} attempt {}: {}", run.kase().key(), cmd, number, e.toString());
            }
            if (number < context.commanderAttempts()) {
                context.pause(context.commanderRetryPause());
            }
        }
        throw new PatsStepError("Commander CGI request failed: " + cmd + ": no answer in "
                + context.commanderAttempts());
    }

    /** The reply's text, as PATS's {@code _text} reads it: empty when the reply has no Content-Type. */
    static String text(HttpReply reply) {
        return reply.contentType() == null ? "" : new String(reply.body(), StandardCharsets.UTF_8);
    }

    /** {@code RequestManager.readHTTPResponse}: with no Content-Type the payload is empty, unread. */
    private static HttpReply asReply(HttpReply raw) {
        return raw.contentType() == null ? new HttpReply(raw.status(), null, new byte[0]) : raw;
    }

    /** {@code URLEncoder.encode} as Python's {@code quote_plus(safe="*-._")}, then PATS's doubling of each {@code %}. */
    static String javaQuery(List<String[]> pairs) {
        StringBuilder out = new StringBuilder();
        for (String[] pair : pairs) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(encode(pair[0])).append('=').append(encode(pair[1]));
        }
        return out.toString();
    }

    private static String encode(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '_' || c == '.' || c == '-' || c == '~' || c == '*') {
                out.append((char) c);
            } else if (c == ' ') {
                out.append('+');
            } else {
                out.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xF));
            }
        }
        return out.toString().replace("%", "%25");
    }

    /** One step's conversation with the Commander: its base URL, its client, and its cookie. */
    private final class Session {

        private final CaseRun run;
        private final PdkShim commander;
        private final String cmd;
        private final String method;
        private final String user;
        private final String password;
        private final byte[] content;
        private final String base;
        private final HttpClient client;

        Session(CaseRun run, PdkShim commander, String cmd, String method, String user, String password,
                byte[] content) {
            this.run = run;
            this.commander = commander;
            this.cmd = cmd;
            this.method = method;
            this.user = user;
            this.password = password;
            this.content = content;
            String url = commander.baseUrl();
            this.base = url.endsWith("?") ? url.substring(0, url.length() - 1) : url;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(commander.timeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
        }

        /** One attempt: the command, then a re-login or an OTP if the reply asks for one. */
        HttpReply attempt() throws IOException {
            HttpReply response = send(cookie(false), null);
            if (text(response).contains(LOGIN_REQUIRED)) {
                response = send(cookie(true), null);
            }
            if (text(response).contains(OTP_REQUIRED)) {
                Supplier<String> reader = commander.otpReader();
                if (reader == null) {
                    throw new PatsStepError(cmd + " needs an OTP and the Commander has no reader");
                }
                response = send(cookie(false), reader.get());
            }
            return response;
        }

        private HttpReply send(String cookie, String otp) throws IOException {
            List<String[]> query = new ArrayList<>();
            query.add(new String[] {"cmd", cmd});
            query.add(new String[] {"cookie", cookie});
            if (otp != null && !otp.isEmpty()) {
                query.add(new String[] {"otp", otp});
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "?" + javaQuery(query)))
                    .timeout(commander.timeout())
                    .header("Accept", "application/xml")
                    .header("Content-Type", CHARSET_XML);
            if (method.equals("POST")) {
                request.POST(content == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(content));
            } else {
                request.GET();
            }
            return exchange(request.build());
        }

        /** The session cookie: from the cache, or from {@code validate} as this user. */
        private String cookie(boolean refresh) {
            if (!refresh && context.cookies().containsKey(user)) {
                return context.cookies().get(user);
            }
            String form = "cmd=" + COOKIE_COMMAND + "&user=" + user + "&passwd=" + password + "\n\n";
            HttpReply reply;
            try {
                reply = exchange(HttpRequest.newBuilder(URI.create(base + "?"))
                        .timeout(commander.timeout())
                        .header("Accept", "application/xml")
                        .header("Content-Type", CHARSET_XML)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(form.getBytes(StandardCharsets.UTF_8)))
                        .build());
            } catch (IOException e) {
                throw new PatsStepError("Commander authentication failed for " + user + ": " + e, e);
            }
            String text = new String(reply.body(), StandardCharsets.UTF_8);
            if (text.strip().isEmpty()) {
                throw new PatsStepError("Empty Commander authentication response");
            }
            int start = text.indexOf("<cookie>");
            int end = text.indexOf("</cookie>");
            if (start < 0 || end < 0 || start + "<cookie>".length() > end) {
                throw new PatsStepError("Cookie missing in Commander response (user " + user + ")");
            }
            String cookie = text.substring(start + "<cookie>".length(), end).strip();
            if (cookie.isEmpty()) {
                throw new PatsStepError("Commander returned an empty cookie");
            }
            context.cookies().put(user, cookie);
            return cookie;
        }

        /** One exchange on this step's own client, body kept whole; an interrupt is a step failure. */
        private HttpReply exchange(HttpRequest request) throws IOException {
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                return new HttpReply(response.statusCode(), contentType, response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PatsStepError("interrupted calling the Commander CGILink at " + base, e);
            }
        }
    }
}
