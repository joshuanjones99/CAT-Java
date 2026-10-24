package com.verifone.cat.pats.runner.handlers.http;

import static com.verifone.cat.pats.runner.handlers.http.Harness.act;
import static com.verifone.cat.pats.runner.handlers.http.Harness.kase;
import static com.verifone.cat.pats.runner.handlers.http.Harness.run;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.ValidationsFailed;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.http.Harness.Files;
import com.verifone.cat.pats.runner.handlers.http.LoopbackServer.Answer;
import com.verifone.cat.pats.runner.handlers.http.LoopbackServer.Seen;

/**
 * {@code SEND_HTTP_REQUEST} against loopback stand-ins: the CGILink wire behaviour over a real {@code PdkShim}, the
 * table's checks, and the CSITE branch through a fake {@link CloudSite}. UNVERIFIED AGAINST HARDWARE.
 */
class SendHttpRequestTest {

    @TempDir
    Path folder;

    private static HttpContext context(Files files) {
        return new HttpContext(files, null, CloudSettle.disabled());
    }

    private static String head(Seen seen) {
        return seen.body().split("\n\n", 2)[0];
    }

    private static List<Seen> cmd(List<Seen> seen, String name) {
        return seen.stream().filter(s -> name.equals(s.query().get("cmd"))).toList();
    }

    @Test
    void cmdrLogsInAsTheTablesUserSendsTheCgiLinkFormAndKeepsTheReply() throws Exception {
        try (LoopbackServer server = new LoopbackServer(CgiFake.standard(true, true))) {
            Files files = new Files();
            files.httpPayloads.put("p1.xml", Harness.USERADMIN);
            files.references.put("ref.xml", ("<VFI:Response xmlns:VFI=\"urn:vfi-sapphire:np.domain.2001-07-01\" />")
                    .getBytes(StandardCharsets.UTF_8));
            files.rules.put("rule.txt", "TAG_NAMES=\nATTRIBUTE_NAMES=\n".getBytes(StandardCharsets.UTF_8));
            HttpContext context = context(files);
            CaseRun run = run(folder, kase(
                    act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "p1.xml", "DEVICE", "CMDR",
                            "METHOD", "POST", "USERNAME", "testuser", "PASSWORD", "TEST123"),
                    act(1, "VALIDATE_PAYLOAD", "SELECT_REFERENCE_FILE", "ref.xml", "SELECT_RULE_FILE", "rule.txt",
                            "VALIDATESCHEMA", "false")),
                    context, Harness.commander(server, "424242"));

            run.run();

            List<Seen> seen = server.seen();
            List<Seen> logins = seen.stream().filter(s -> s.body().contains("cmd=validate")).toList();
            assertEquals(List.of("testuser", "testuser"),
                    logins.stream().map(s -> LoopbackServer.parseQuery(head(s)).get("user")).toList());
            assertEquals("TEST123", LoopbackServer.parseQuery(head(logins.get(0))).get("passwd"));
            List<Seen> sent = cmd(seen, "uuseradmin");
            // LoginRequired: a fresh cookie, sent once more; OTPRequired: once more with the OTP.
            assertEquals(Arrays.asList(null, null, "424242"), sent.stream().map(s -> s.query().get("otp")).toList());
            assertNotEquals(sent.get(0).query().get("cookie"), sent.get(1).query().get("cookie"));
            String payload = new String(Harness.USERADMIN, StandardCharsets.UTF_8);
            assertTrue(sent.stream().allMatch(s -> s.method().equals("POST") && s.body().equals(payload)));
            assertTrue(sent.stream().allMatch(s -> "text/xml; charset=UTF-8".equals(s.headers().get("content-type"))));
            assertArrayEquals(CgiFake.OK_XML.getBytes(StandardCharsets.UTF_8), context.currentPayloads().get(".xml"));
        }
    }

    @Test
    void aPayloadThatDiffersFailsTheCaseAtItsEndNotAtTheStep() throws Exception {
        try (LoopbackServer server = new LoopbackServer(CgiFake.standard(false, false))) {
            Files files = new Files();
            files.httpPayloads.put("p1.xml", Harness.USERADMIN);
            files.references.put("ref.xml", ("<VFI:Response xmlns:VFI='urn:vfi-sapphire:np.domain.2001-07-01'>"
                    + "<VFI:Fault/></VFI:Response>").getBytes(StandardCharsets.UTF_8));
            files.rules.put("rule.txt", "TAG_NAMES=\nATTRIBUTE_NAMES=\n".getBytes(StandardCharsets.UTF_8));
            CaseRun run = run(folder, kase(
                    act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "p1.xml", "DEVICE", "CMDR",
                            "METHOD", "POST"),
                    act(1, "VALIDATE_PAYLOAD", "SELECT_REFERENCE_FILE", "ref.xml", "SELECT_RULE_FILE", "rule.txt")),
                    context(files), Harness.commander(server, null));

            ValidationsFailed failed = assertThrows(ValidationsFailed.class, run::run);
            assertTrue(failed.getMessage().contains("payload differs from ref.xml"), failed.getMessage());
        }
    }

    @Test
    void aCookieIsKeptPerUserNameAcrossCases() throws Exception {
        try (LoopbackServer server = new LoopbackServer(CgiFake.standard(false, false))) {
            Files files = new Files();
            files.httpPayloads.put("p1.xml", Harness.USERADMIN);
            HttpContext context = context(files);
            for (int i = 0; i < 2; i++) {
                run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "p1.xml",
                        "DEVICE", "CMDR", "METHOD", "POST")), context, Harness.commander(server, null)).run();
            }
            assertEquals(1, server.seen().stream().filter(s -> s.body().contains("cmd=validate")).count());
        }
    }

    @Test
    void aBusyCommanderIsTriedAgainAndANeverEndingBusyFailsTheStep() throws Exception {
        try (LoopbackServer server = new LoopbackServer(CgiFake.alwaysBusy())) {
            Files files = new Files();
            files.httpPayloads.put("p1.xml", Harness.USERADMIN);
            Harness.RecordingPause pause = new Harness.RecordingPause();
            HttpContext context = new HttpContext(files, null, CloudSettle.disabled(), pause,
                    System::nanoTime, Duration.ofSeconds(60), 3);
            CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "p1.xml",
                    "DEVICE", "CMDR", "METHOD", "POST")), context, Harness.commander(server, null));

            PatsStepError stop = assertThrows(PatsStepError.class, run::run);
            assertTrue(stop.getMessage().contains("Commander CGI request failed: uuseradmin"), stop.getMessage());
            assertEquals(3, cmd(server.seen(), "uuseradmin").size());
            assertEquals(List.of(Duration.ofSeconds(60), Duration.ofSeconds(60)), pause.waits);
        }
    }

    @Test
    void aBusyCommanderThatrecoversAnswersTheStep() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackServer server = new LoopbackServer(seen -> {
            if (!"uuseradmin".equals(seen.query().get("cmd"))) {
                return new Answer(200, "text/xml", "<cookie>abc</cookie>");
            }
            return calls.incrementAndGet() == 1
                    ? new Answer(200, "text/xml", CgiFake.BUSY)
                    : new Answer(200, "text/xml", CgiFake.OK_XML);
        })) {
            Files files = new Files();
            files.httpPayloads.put("p1.xml", Harness.USERADMIN);
            Harness.RecordingPause pause = new Harness.RecordingPause();
            HttpContext context = new HttpContext(files, null, CloudSettle.disabled(), pause,
                    System::nanoTime, Duration.ofSeconds(60), 15);

            run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "p1.xml",
                    "DEVICE", "CMDR", "METHOD", "POST")), context, Harness.commander(server, null)).run();

            assertEquals(2, cmd(server.seen(), "uuseradmin").size());
            assertEquals(1, pause.waits.size());
        }
    }

    @Test
    void aPayloadOfOneSpaceFailsTheStepAsItFailsInPats() {
        Files files = new Files();
        CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "pcms/v1/x", "PAYLOAD", " ",
                "DEVICE", "CSITE", "METHOD", "DELETE")), context(files), null);

        PatsStepError stop = assertThrows(PatsStepError.class, run::run);
        assertTrue(stop.getMessage().contains("InvalidPathException"), stop.getMessage());
    }

    @Test
    void theTableKeysAndRolesNotPortedAreRefusedByName() {
        String[][] refused = {
            {"HEADERS", "x"}, {"PARAMS", "x"}, {"dueDate", "x"}, {"ROLE", "admin"},
        };
        for (String[] pair : refused) {
            Files files = new Files();
            files.httpPayloads.put("p1.xml", Harness.USERADMIN);
            CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "p1.xml",
                    "DEVICE", "CMDR", pair[0], pair[1])), context(files), null);
            PatsStepError stop = assertThrows(PatsStepError.class, run::run, pair[0]);
            assertTrue(stop.getMessage().contains(pair[0]) || stop.getMessage().contains("role"),
                    stop.getMessage());
        }
    }

    @Test
    void captureFieldsFillTheScenarioContextAndExpectStatusPasses() throws Exception {
        try (LoopbackServer server = new LoopbackServer(seen -> "validate".equals(seen.query().get("cmd"))
                || seen.body().contains("cmd=validate")
                ? new Answer(200, "text/xml", "<cookie>abc</cookie>")
                : new Answer(200, "application/json", "{\"transactionId\":\"T9\",\"status\":\"ACK\",\"count\":2}"))) {
            HttpContext context = context(new Files());
            CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "",
                    "DEVICE", "CMDR", "METHOD", "GET", "CAPTUREFIELDS", "$.transactionId->tid|txn,$.count",
                    "EXPECTSTATUS", "2xx")), context, Harness.commander(server, null));

            run.run();

            assertEquals("T9", ScenarioContext.of(run).get("tid"));
            assertEquals("T9", ScenarioContext.of(run).get("txn"));
            assertEquals("2", ScenarioContext.of(run).get("count"));
        }
    }

    @Test
    void expectStatusThatDoesNotMatchFailsTheStepNamingBoth() throws Exception {
        try (LoopbackServer server = new LoopbackServer(seen -> seen.body().contains("cmd=validate")
                ? new Answer(200, "text/xml", "<cookie>abc</cookie>")
                : new Answer(200, "application/json", "{}"))) {
            CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "",
                    "DEVICE", "CMDR", "METHOD", "GET", "EXPECTSTATUS", "404")), context(new Files()),
                    Harness.commander(server, null));

            PatsStepError stop = assertThrows(PatsStepError.class, run::run);
            assertTrue(stop.getMessage().contains("Expected status [404] but got [200]"), stop.getMessage());
        }
    }

    @Test
    void pollUntilFieldReadsWantedAndWaitsBetweenReads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackServer server = new LoopbackServer(seen -> {
            if (seen.body().contains("cmd=validate")) {
                return new Answer(200, "text/xml", "<cookie>abc</cookie>");
            }
            return calls.incrementAndGet() == 1
                    ? new Answer(200, "application/json", "{\"status\":\"PENDING\"}")
                    : new Answer(200, "application/json", "{\"status\":\"ACK\"}");
        })) {
            Harness.RecordingPause pause = new Harness.RecordingPause();
            HttpContext context = new HttpContext(new Files(), null, CloudSettle.disabled(), pause,
                    System::nanoTime, Duration.ofSeconds(60), 15);
            run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "",
                    "DEVICE", "CMDR", "METHOD", "GET", "POLLUNTILFIELD", "$.status", "POLLUNTILVALUE", "ACK",
                    "POLLINTERVALSECONDS", "3")), context, Harness.commander(server, null)).run();

            assertEquals(2, calls.get());
            assertEquals(List.of(Duration.ofSeconds(3)), pause.waits);
        }
    }

    @Test
    void polledRepliesMustBeJson() throws Exception {
        try (LoopbackServer server = new LoopbackServer(seen -> seen.body().contains("cmd=validate")
                ? new Answer(200, "text/xml", "<cookie>abc</cookie>")
                : new Answer(200, "text/plain", "x"))) {
            CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "uuseradmin", "PAYLOAD", "",
                    "DEVICE", "CMDR", "METHOD", "GET", "POLLUNTILFIELD", "$.status")), context(new Files()),
                    Harness.commander(server, null));

            PatsStepError stop = assertThrows(PatsStepError.class, run::run);
            assertTrue(stop.getMessage().contains("Polling requires JSON response, got 'text/plain'"),
                    stop.getMessage());
        }
    }

    @Test
    void theCloudBranchFailsByNameWhenNoClientIsWired() {
        CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "pcms/v1/x", "PAYLOAD", "",
                "DEVICE", "CSITE", "METHOD", "GET")), context(new Files()), null);

        PatsStepError stop = assertThrows(PatsStepError.class, run::run);
        assertTrue(stop.getMessage().contains("no C-Site client is configured"), stop.getMessage());
    }

    @Test
    void aCloudRequestFillsTokensDropsTheDevPrefixAndSendsTheEnrichedBody() throws Exception {
        try (LoopbackServer server = new LoopbackServer(CgiFake.standard(false, false))) {
            Files files = new Files();
            files.httpPayloads.put("p.json", "{\"site\": \"{service_id}\"}".getBytes(StandardCharsets.UTF_8));
            FakeCloud cloud = new FakeCloud("dev", false, new HttpReply(200, "application/json", "{}".getBytes(
                    StandardCharsets.UTF_8)));
            HttpContext context = new HttpContext(files, cloud, CloudSettle.disabled());
            run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "pcms/v1/sites/{service_id}/ctrlcmd/cfuelinit",
                    "PAYLOAD", "p.json", "DEVICE", "CSITE", "METHOD", "POST", "RETRYENABLED", "false")), context,
                    Harness.commander(server, null)).run();

            assertEquals(List.of(new FakeCloud.Call("POST", "v1/sites/1234567/ctrlcmd/cfuelinit",
                    "{\"site\": \"1234567\"}", false)), cloud.calls);
            assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), context.currentPayloads().get(".json"));
        }
    }

    @Test
    void aCloudCommandWithItsOwnQueryIsRefused() {
        CaseRun run = run(folder, kase(act(0, "SEND_HTTP_REQUEST", "COMMAND", "pcms/v1/x?y=1", "PAYLOAD", "",
                "DEVICE", "CSITE", "METHOD", "GET")),
                new HttpContext(new Files(), new FakeCloud("qat", false, null), CloudSettle.disabled()), null);

        PatsStepError stop = assertThrows(PatsStepError.class, run::run);
        assertTrue(stop.getMessage().contains("a query in the command is not ported"), stop.getMessage());
    }

    /** A cloud client that records each call and answers with a fixed reply. */
    static final class FakeCloud implements CloudSite {

        record Call(String method, String endpoint, String body, boolean retry) {
        }

        final String environment;
        final boolean strip;
        final HttpReply reply;
        final List<Call> calls = new ArrayList<>();

        FakeCloud(String environment, boolean strip, HttpReply reply) {
            this.environment = environment;
            this.strip = strip;
            this.reply = reply;
        }

        @Override
        public String environment() {
            return environment;
        }

        @Override
        public boolean stripServicePrefix() {
            return strip;
        }

        @Override
        public HttpReply send(String method, String endpoint, String body, boolean retry) {
            calls.add(new Call(method, endpoint, body, retry));
            return reply;
        }
    }
}
