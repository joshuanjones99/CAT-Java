package com.verifone.cat.pats.runner.handlers.http;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLog;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.validate.PyRepr;

/**
 * PATS's BDD-only {@code SEND_HTTP_REQUEST}: {@code SendHttpRequestActionHandler.sendHttpRequest}, branch for branch.
 * Ported from the Python {@code petrotest.pats.runner.http._send_http_request}, which is the specification.
 *
 * <p>The table's {@code device} picks the processor: {@code CMDR} is the Commander's CGILink ({@link CommanderCgi});
 * {@code CSITE} is the cloud, through the {@link CloudSite} the orchestrator wires. The request's {@code {name}}
 * tokens are filled from the case's {@link ScenarioContext}, and {@code service_id} is read from the Commander's
 * {@code vsupportinfo} when it is first needed. The reply is kept as PATS's {@code CurrentPayload.<ext>} for
 * {@code VALIDATE_PAYLOAD}, and its JSON fields named by {@code captureFields} go into the scenario context.
 *
 * <p>Any HTTP status passes unless the table says {@code expectStatus}. A payload of a single space fails the step, as
 * PATS fails it (an {@code InvalidPathException} on Windows). Not ported, and refused by name: the table keys
 * {@code headers}, {@code params}, {@code uploadFile}, {@code formFields}, {@code dateFormat}, any date or timestamp
 * key, a {@code role} other than the default, and the {@code VISTA} and {@code VHQ} devices.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no {@code SEND_HTTP_REQUEST} has reached a Commander or the cloud from CAT-Java.
 */
final class SendHttpRequest implements ActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** {@code RequestContextFactory.HTTP_REQUEST_KEYS}' keys PATS does not carry over to CAT. */
    private static final List<String> NOT_PORTED_KEYS = List.of("HEADERS", "PARAMS", "UPLOADFILE", "FORMFIELDS",
            "DATEFORMAT");
    /** {@code CurrentPayload} extensions by the Content-Type word that names them, first match wins. */
    private static final List<String[]> EXTENSIONS = List.of(
            new String[] {"json", ".json"},
            new String[] {"xml", ".xml"},
            new String[] {"gzip", ".gz"},
            new String[] {"x-tar", ".tar"},
            new String[] {"zip", ".zip"});
    private static final Pattern TOKEN = Pattern.compile("\\$?\\{([a-zA-Z_][a-zA-Z0-9_.-]*)\\}");
    private static final Pattern STATUS_CLASS = Pattern.compile("[1-5]xx", Pattern.CASE_INSENSITIVE);
    private static final String SERVICE_ID = "service_id";

    private final HttpContext context;
    private final CommanderCgi commander;

    SendHttpRequest(HttpContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.commander = new CommanderCgi(context);
    }

    @Override
    public void handle(CaseRun run, RunnableAction action) {
        String command = required(action, "COMMAND");
        String payloadName = required(action, "PAYLOAD");
        String device = action.get("DEVICE").strip();
        if (device.isEmpty()) {
            throw new PatsStepError("Device name must not be null or blank (no | device | row)");
        }
        refuseUnported(action);
        String body = payloadBody(run, payloadName);
        HttpReply reply;
        String upper = device.toUpperCase(Locale.ROOT);
        if (upper.equals("CMDR")) {
            reply = poll(run, action, () -> commander.request(run, action, command, body));
        } else if (upper.equals("CSITE")) {
            reply = cloudRequest(run, action, command, body);
        } else {
            throw new PatsStepError("device " + PyRepr.reprString(device)
                    + " is not ported to CAT (CMDR and CSITE are)");
        }
        String extension = payloadExtension(reply.contentType());
        context.currentPayloads().put(extension, reply.body());
        LOG.info("{}: SEND_HTTP_REQUEST {} answered HTTP {} ({}, {} bytes, kept as CurrentPayload{})",
                run.kase().key(), command, reply.status(), Objects.toString(reply.contentType(), "None"),
                reply.body().length, extension);
        captureFields(run, action, reply);
        expectStatus(action, reply);
    }

    /** The cloud write's wait around the request: marked before, waited for after (cloud_settle). */
    private HttpReply cloudRequest(CaseRun run, RunnableAction action, String command, String body) {
        if (context.cloud().isEmpty()) {
            throw new PatsStepError("device CSITE is not wired in CAT-Java: no C-Site client is configured "
                    + "(the Python's csite client is not ported yet)");
        }
        CloudSettle settle = context.settle();
        String method = action.get("METHOD", "GET");
        Optional<CommanderLog> mark = settle.mark(run, method);
        HttpReply reply;
        boolean answered = false;
        try {
            reply = poll(run, action, () -> csiteRequest(run, action, command, body));
            answered = true;
        } finally {
            if (!answered) {
                settle.release(mark);
            }
        }
        settle.waitApplied(run, mark, reply.status(), command);
        return reply;
    }

    private HttpReply csiteRequest(CaseRun run, RunnableAction action, String command, String body) {
        CloudSite cloud = context.cloud().orElseThrow();
        String endpoint = command;
        if (cloud.environment().toLowerCase(Locale.ROOT).equals("dev") || cloud.stripServicePrefix()) {
            // normalizeEndpoint: drop the service prefix (pcms/) up to its first slash.
            int cut = endpoint.indexOf('/');
            endpoint = cut >= 0 ? endpoint.substring(cut + 1) : endpoint;
        }
        if (endpoint.contains("?")) {
            throw new PatsStepError(PyRepr.reprString(command) + ": a query in the command is not ported to CAT");
        }
        endpoint = enrich(run, endpoint);
        String enriched = body == null ? null : enrich(run, body);
        boolean retry = action.get("RETRYENABLED", "true").toLowerCase(Locale.ROOT).equals("true");
        return cloud.send(action.get("METHOD", "GET"), endpoint, enriched, retry);
    }

    // -- the table's own checks ---------------------------------------------------------------

    private static String required(RunnableAction action, String name) {
        String value = action.raw(name);
        if (value == null) {
            throw new PatsStepError(action.describe() + " has no " + name + " field (PATS would throw here)");
        }
        return value;
    }

    private static void refuseUnported(RunnableAction action) {
        for (String key : NOT_PORTED_KEYS) {
            if (action.raw(key) != null) {
                throw new PatsStepError("the table key " + PyRepr.reprString(key)
                        + " is not ported to CAT (no case uses it)");
            }
        }
        String role = action.get("ROLE").strip();
        if (!role.isEmpty() && !role.equals("default")) {
            throw new PatsStepError("role " + PyRepr.reprString(role) + " is not ported to CAT (only PATS's default is)");
        }
        for (RunnableAction.ActionField field : action.fields()) {
            if (field.name().endsWith("Date") || field.name().endsWith("Timestamp")) {
                throw new PatsStepError("the date table key " + PyRepr.reprString(field.name())
                        + " is not ported to CAT");
            }
        }
    }

    /**
     * {@code PayloadValidationHelper.resolveHttpPayloadContent}: the case's HTTP payload, read as UTF-8. No body for an
     * empty name; a name PATS cannot open fails the step, and so does a missing file.
     */
    private String payloadBody(CaseRun run, String name) {
        if (name.isEmpty()) {
            return null;
        }
        if (name.strip().isEmpty() || !name.equals(name.stripTrailing())) {
            throw new PatsStepError("payload " + PyRepr.reprString(name) + ": PATS opens Payloads\\HTTP\\" + name
                    + ", a path Windows refuses (InvalidPathException: trailing space), so PATS fails this step too");
        }
        byte[] data = context.files().httpPayload(name);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new PatsStepError("Failed to read payload file " + name + ": " + e, e);
        }
    }

    /** {@code payload_extension}: the {@code CurrentPayload} extension for a Content-Type. */
    static String payloadExtension(String contentType) {
        String kind = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        for (String[] marker : EXTENSIONS) {
            if (kind.contains(marker[0])) {
                return marker[1];
            }
        }
        return ".bin";
    }

    // -- the token fill ----------------------------------------------------------------------

    /** {@code HttpTokenEnricher}: each {@code {name}} from the scenario context; an unknown one stays as written. */
    String enrich(CaseRun run, String text) {
        Matcher match = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (match.find()) {
            String name = match.group(1);
            Map<String, String> scenario = ScenarioContext.of(run);
            if (name.equals(SERVICE_ID) && !scenario.containsKey(name)) {
                populateServiceId(run);
                scenario = ScenarioContext.of(run);
            }
            String replacement;
            if (scenario.containsKey(name)) {
                replacement = scenario.get(name);
            } else {
                LOG.warn("{}: no value for {}; left as written, as PATS does", run.kase().key(), "{" + name + "}");
                replacement = match.group(0);
            }
            match.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        match.appendTail(out);
        return out.toString();
    }

    /**
     * {@code DeviceContextProvider.populate}: {@code <storeServiceID>} from the Commander's {@code vsupportinfo}. A
     * failure is logged, as PATS logs it, and the token stays.
     */
    private void populateServiceId(CaseRun run) {
        String text;
        try {
            PdkShim pdk = run.fixture(FixtureName.COMMANDER_CONFIG, PdkShim.class);
            text = pdk.sendCommand("vsupportinfo", "");
        } catch (RuntimeException e) {
            LOG.error("{}: Failed to initialize cloud Service ID: {}", run.kase().key(), e.getMessage());
            return;
        }
        String open = "<storeServiceID>";
        String close = "</storeServiceID>";
        int start = text.indexOf(open);
        int end = text.indexOf(close);
        if (start < 0 || end < 0 || start >= end) {
            LOG.error("{}: Service ID tags not found in the config response", run.kase().key());
            return;
        }
        ScenarioContext.of(run).put(SERVICE_ID, text.substring(start + open.length(), end).strip());
    }

    // -- polling, capture and status ---------------------------------------------------------

    /** {@code HttpPollingExecutor.execute}: once, or until {@code pollUntilField} reads as wanted. */
    private HttpReply poll(CaseRun run, RunnableAction action, Supplier<HttpReply> request) {
        String field = action.get("POLLUNTILFIELD");
        if (field.strip().isEmpty()) {
            return request.get();
        }
        String wanted = action.raw("POLLUNTILVALUE");
        long limit = intOr(action.get("POLLMAXSECONDS"), HttpContext.POLL_MAX_SECONDS);
        long interval = intOr(action.get("POLLINTERVALSECONDS"), HttpContext.POLL_INTERVAL_SECONDS);
        long deadline = context.nanoTime() + Duration.ofSeconds(limit).toNanos();
        while (true) {
            HttpReply reply = request.get();
            if (!(reply.contentType() == null ? "" : reply.contentType()).toLowerCase(Locale.ROOT).contains("json")) {
                throw new PatsStepError("Polling requires JSON response, got " + PyRepr.repr(reply.contentType()));
            }
            JsonNode node = JsonFields.resolve(JsonFields.parse(reply.body()), field);
            if (node != null && (wanted == null || JsonFields.asText(node).equals(wanted))) {
                return reply;
            }
            if (context.nanoTime() >= deadline) {
                LOG.warn("{}: {} never read {}; carrying on", run.kase().key(), field, PyRepr.repr(wanted));
                return reply;
            }
            context.pause(Duration.ofSeconds(interval));
        }
    }

    private static long intOr(String text, int fallback) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code JsonFieldCapturer}: {@code path->alias|alias}, comma-separated, into the scenario context. */
    private void captureFields(CaseRun run, RunnableAction action, HttpReply reply) {
        String spec = action.get("CAPTUREFIELDS");
        if (spec.isEmpty() || !isJson(reply)) {
            return;
        }
        JsonNode root = JsonFields.parse(reply.body());
        if (root == null) {
            LOG.warn("{}: captureFields: the reply is not JSON", run.kase().key());
            return;
        }
        Map<String, String> scenario = ScenarioContext.of(run);
        for (String raw : spec.split(",", -1)) {
            String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int arrow = entry.indexOf("->");
            String path = arrow >= 0 ? entry.substring(0, arrow) : entry;
            List<String> names = arrow >= 0
                    ? List.of(entry.substring(arrow + 2).split("\\|", -1))
                    : List.of(path.substring(path.lastIndexOf('.') + 1));
            JsonNode node = JsonFields.resolve(root, path.strip());
            if (node == null) {
                LOG.warn("{}: captureFields: no {} in the reply", run.kase().key(), path);
                continue;
            }
            for (String name : names) {
                String alias = name.strip();
                if (!alias.isEmpty()) {
                    scenario.put(alias, JsonFields.asText(node));
                }
            }
        }
    }

    /** {@code StatusCodeValidator}: only when the table says {@code expectStatus}. */
    private static void expectStatus(RunnableAction action, HttpReply reply) {
        String expected = action.get("EXPECTSTATUS").strip();
        if (expected.isEmpty()) {
            return;
        }
        boolean ok;
        if (STATUS_CLASS.matcher(expected).matches()) {
            ok = reply.status() / 100 == expected.charAt(0) - '0';
        } else {
            ok = false;
            for (String code : expected.split(",", -1)) {
                try {
                    if (Integer.parseInt(code.strip()) == reply.status()) {
                        ok = true;
                    }
                } catch (NumberFormatException e) {
                    throw new PatsStepError("Invalid expectStatus format: " + PyRepr.reprString(expected), e);
                }
            }
        }
        if (!ok) {
            throw new PatsStepError("Expected status [" + expected + "] but got [" + reply.status() + "]. Body: "
                    + new String(reply.body(), StandardCharsets.UTF_8));
        }
    }

    private static boolean isJson(HttpReply reply) {
        return reply.contentType() != null && reply.contentType().toLowerCase(Locale.ROOT).contains("json");
    }
}
