package com.verifone.cat.devices.commander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Element;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.devices.commander.CgiReplies.CloudConnectStatus;
import com.verifone.cat.devices.commander.FakeCommander.Reply;
import com.verifone.cat.preflight.Bounded;

/**
 * {@link PdkShim} against {@link FakeCommander} on loopback: ports of the Python CAT's
 * {@code test_config_shim_with_mocks.py}, {@code test_config_shim_upgrade.py},
 * {@code test_config_integration.py}, and the {@code PDKShim} tests of
 * {@code test_commander_connect.py}, {@code test_run_time_levers.py} and
 * {@code test_commander_snapshot.py}.
 *
 * <p>Where the Python monkeypatched {@code _post_cgi_command}, these drive the real client over
 * HTTP to the fake instead, so each body asserted is what goes on the wire. PATS's pauses go to
 * a recorder, so nothing waits. Evidence about this repository, not about a Commander.
 */
class PdkShimTest {

    private static final Duration S20 = Duration.ofSeconds(20);
    private static final Duration S10 = Duration.ofSeconds(10);

    private final List<FakeCommander> fakes = new ArrayList<>();
    private final List<Duration> pauses = Collections.synchronizedList(new ArrayList<>());

    private FakeCommander fake(java.util.function.Function<FakeCommander.Request, Reply> answer) {
        FakeCommander fake = new FakeCommander(answer);
        fakes.add(fake);
        return fake;
    }

    private FakeCommander fakeOk(String body) {
        return fake(request -> Reply.ok(body));
    }

    @AfterEach
    void closeFakes() {
        fakes.forEach(FakeCommander::close);
    }

    // ==================================================================== structure

    @Nested
    class Structure {

        @Test
        void theUrlIsPort80sCgiLinkWithItsLiteralQuestionMark() {
            try (PdkShim client = new PdkShim("commander.testrig.invalid")) {
                assertEquals("commander.testrig.invalid", client.commanderHost());
                assertEquals("http://commander.testrig.invalid/cgi-bin/CGILink?", client.baseUrl());
                assertEquals(PdkShim.DEFAULT_TIMEOUT, client.timeout());
                assertEquals(PdkShim.COMMANDER_PASSWORD, client.password());
            }
        }

        @Test
        void productionsReadBudgetIsTheCommandersPlusThirtySeconds() {
            assertEquals(Duration.ofSeconds(150), PdkShim.COMMANDER_READ_TIMEOUT);
            assertEquals(15, PdkShim.PATS_REQUEST_RETRIES);
            assertEquals(S20, PdkShim.PATS_REQUEST_RETRIES_SLEEP);
            assertEquals(Duration.ZERO, PdkShim.IMPORT_FILE_PAUSE);
        }

        @Test
        void theLabLoginIsTheRigOwnersCarveOut() {
            assertEquals("manager", PdkShim.COMMANDER_USER);
            assertEquals("A111111", PdkShim.COMMANDER_PASSWORD);
        }

        @Test
        void theOtpReaderGivenIsTheOneHandedBack() {
            java.util.function.Supplier<String> reader = () -> "1";
            PdkShim client = new PdkShim("commander.testrig.invalid", PdkShim.COMMANDER_READ_TIMEOUT, reader);
            assertEquals(reader, client.otpReader());
            assertEquals(PdkShim.COMMANDER_READ_TIMEOUT, client.timeout());
        }

        @Test
        void aRequestIsAPostOfTheBodyWithPatsHeadersToTheLiteralUrl() {
            FakeCommander fake = fakeOk("<transNumList/>");
            fake.clientWithCookie(pauses, "test-cookie").listTransactionNumbers();
            FakeCommander.Request request = fake.requests.get(0);
            assertEquals("POST", request.method());
            // The JDK's HttpClient drops an empty query from the request line, where httpx keeps
            // the "?" (see CgiLink's class comment): pinned here so a JDK that changes is noticed.
            assertEquals("/cgi-bin/CGILink", request.target());
            assertEquals("application/xml", request.accept());
            assertEquals("text/xml; charset=UTF-8", request.contentType());
            assertEquals("cmd=vtransnumlist&cookie=test-cookie&period=1&filename=current", request.body());
        }

        @Test
        void theClientKeepsNoConnectionAlive() {
            // PATS's RequestManager disconnects after every request (HANDOFF.md §1.41).
            FakeCommander fake = fakeOk("<transNumList/>");
            PdkShim client = fake.clientWithCookie(pauses, "c");
            client.listTransactionNumbers();
            client.listTransactionNumbers();
            client.listTransactionNumbers();
            assertEquals(3, new HashSet<>(fake.requests.stream().map(FakeCommander.Request::remotePort).toList()).size());
        }
    }

    // ==================================================================== no command behind them

    @Nested
    class ConfigurationMethodsAreUnavailable {

        private final PdkShim client = new PdkShim("commander.testrig.invalid");

        private void assertUnavailable(String surface, org.junit.jupiter.api.function.Executable call) {
            DeviceUnavailable e = assertThrows(DeviceUnavailable.class, call);
            assertTrue(e.getMessage().contains(surface), e.getMessage());
            assertTrue(e.getMessage().toLowerCase().contains("command names are not enumerated"), e.getMessage());
            assertTrue(e.getMessage().contains(PdkShim.SECTION));
        }

        @Test
        void activeConfigFolder() {
            assertUnavailable("ConfigStatus.active_config_folder()", client::activeConfigFolder);
        }

        @Test
        void isConfigLoaded() {
            assertUnavailable("ConfigStatus.is_config_loaded()", () -> client.isConfigLoaded("suite_cadence"));
        }

        @Test
        void loadConfig() {
            assertUnavailable("ConfigControl.load_config()", () -> client.loadConfig("suite_cadence"));
        }

        @Test
        void listAvailableConfigs() {
            assertUnavailable("ConfigStatus.list_available_configs()", client::listAvailableConfigs);
        }
    }

    // ==================================================================== errors

    @Nested
    class Errors {

        @Test
        void aCommanderThatIsNotThereIsUnreachable() throws IOException {
            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            PdkShim client = new PdkShim(InetAddress.getLoopbackAddress().getHostAddress(), port,
                    Duration.ofSeconds(2), null, PdkShim.COMMANDER_PASSWORD, false, pauses::add);
            DeviceUnreachable e = assertThrows(DeviceUnreachable.class, client::authenticate);
            assertTrue(e.getMessage().contains("PDKShim at"), e.getMessage());
        }

        @Test
        void aNon2xxStatusIsUnreachable() {
            FakeCommander fake = fake(request -> Reply.status(503, "starting"));
            DeviceUnreachable e = assertThrows(DeviceUnreachable.class,
                    () -> fake.clientWithCookie(pauses, "c").listTransactionNumbers());
            assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());
        }

        @Test
        void aReplyThatIsNotXmlIsAProtocolError() {
            FakeCommander fake = fakeOk("Unable to process your request. Please try again later.");
            assertThrows(DeviceProtocolError.class, () -> fake.clientWithCookie(pauses, "c").listTransactionNumbers());
        }

        @Test
        void aValidateWithNoCookieIsAProtocolError() {
            FakeCommander fake = fakeOk("<credential/>");
            DeviceProtocolError e = assertThrows(DeviceProtocolError.class, () -> fake.client(pauses).authenticate());
            assertTrue(e.getMessage().contains("no <cookie>"), e.getMessage());
        }

        @Test
        void validateSendsTheUserAndPasswordAndTheCookieIsCached() {
            FakeCommander fake = fake(request -> request.cmd().equals("validate")
                    ? Reply.ok(FakeCommander.fixture("validate-funclist.xml"))
                    : Reply.ok("<transNumList/>"));
            PdkShim client = fake.client(pauses);
            client.listTransactionNumbers();
            client.listTransactionNumbers();
            assertEquals(List.of("cmd=validate&user=manager&passwd=A111111",
                    "cmd=vtransnumlist&cookie=c00k1e&period=1&filename=current",
                    "cmd=vtransnumlist&cookie=c00k1e&period=1&filename=current"), fake.bodies());
        }
    }

    // ==================================================================== TLog

    @Nested
    class TlogViaVtransdetails {

        private final String populated = FakeCommander.fixture("transnumlist-populated.xml");

        @Test
        void anEmptyListIsTheConfirmedLiveShape() {
            FakeCommander fake = fakeOk("<transNumList/>");
            assertEquals(List.of(), fake.clientWithCookie(pauses, "test-cookie").listTransactionNumbers());
        }

        @Test
        void onlyTrseqIsReturnedNotPosNum() {
            FakeCommander fake = fakeOk(populated);
            assertEquals(List.of("1010007", "1010006", "1010005"),
                    fake.clientWithCookie(pauses, "test-cookie").listTransactionNumbers());
        }

        @Test
        void periodAndFilenameArePassed() {
            FakeCommander fake = fakeOk("<transNumList/>");
            fake.clientWithCookie(pauses, "test-cookie").listTransactionNumbers("2", "current");
            assertEquals("vtransnumlist", fake.requests.get(0).cmd());
            assertEquals(Map.of("cmd", "vtransnumlist", "cookie", "test-cookie", "period", "2", "filename", "current"),
                    fake.requests.get(0).fields());
        }

        @Test
        void vtransdetailsTakesTrseqNotTransNum() {
            FakeCommander fake = fakeOk("<trans/>");
            fake.clientWithCookie(pauses, "test-cookie").fetchTlogByTrseq("55");
            assertEquals("cmd=vtransdetails&cookie=test-cookie&trseq=55", fake.bodies().get(0));
        }

        @Test
        void theLiveTransListWrapperIsUnwrapped() {
            FakeCommander fake = fakeOk("<transList><trans type=\"sale\"><trHeader/></trans></transList>");
            Element trans = fake.clientWithCookie(pauses, "c").fetchTlogByTrseq("1010007");
            assertEquals("trans", trans.getTagName());
            assertEquals("sale", trans.getAttribute("type"));
        }

        @Test
        void aTransListWithNoTransIsAProtocolError() {
            FakeCommander fake = fakeOk("<transList><other/></transList>");
            assertThrows(DeviceProtocolError.class, () -> fake.clientWithCookie(pauses, "c").fetchTlogByTrseq("1"));
        }

        @Test
        void anExplicitTrseqSkipsTheListCall() {
            FakeCommander fake = fakeOk("<trans/>");
            fake.clientWithCookie(pauses, "c").fetchTlog("55");
            assertEquals(List.of("vtransdetails"), fake.requests.stream().map(FakeCommander.Request::cmd).toList());
        }

        @Test
        void noTrseqFetchesTheNewestTheFirstListed() {
            FakeCommander fake = fake(request -> Reply.ok(request.cmd().equals("vtransnumlist") ? populated : "<trans/>"));
            fake.clientWithCookie(pauses, "test-cookie").fetchTlog();
            assertEquals(List.of("vtransnumlist", "vtransdetails"),
                    fake.requests.stream().map(FakeCommander.Request::cmd).toList());
            assertEquals("cmd=vtransdetails&cookie=test-cookie&trseq=1010007", fake.bodies().get(1));
        }

        @Test
        void theNewestNotTheHighestAcrossRegisters() {
            // After a DCR sale the highest trSeq was an older 904 prepay (PETROCORE-118399).
            String listed = "<transNumList>"
                    + "<trTickNum><posNum>901</posNum><trSeq>9010002</trSeq></trTickNum>"
                    + "<trTickNum><posNum>904</posNum><trSeq>9040009</trSeq></trTickNum>"
                    + "<trTickNum><posNum>101</posNum><trSeq>1010007</trSeq></trTickNum>"
                    + "</transNumList>";
            FakeCommander fake = fake(request -> Reply.ok(request.cmd().equals("vtransnumlist") ? listed : "<trans/>"));
            fake.clientWithCookie(pauses, "c").fetchTlog();
            assertEquals("9010002", fake.requests.get(1).fields().get("trseq"));
        }

        @Test
        void noTransactionYetIsADeviceError() {
            FakeCommander fake = fakeOk("<transNumList/>");
            DeviceError e = assertThrows(DeviceError.class, () -> fake.clientWithCookie(pauses, "c").fetchTlog());
            assertTrue(e.getMessage().contains("no transaction"), e.getMessage());
        }

        @Test
        void theVtranssetReportAsksForPeriodOneCurrent() {
            FakeCommander fake = fakeOk("<report/>");
            fake.clientWithCookie(pauses, "c").fetchVtranssetReport("summary");
            assertEquals("cmd=vtransset&cookie=c&period=1&filename=current&reptname=summary", fake.bodies().get(0));
        }

        @Test
        void aReportCarriesItsPeriodFileAndReportName() {
            FakeCommander fake = fakeOk("<report/>");
            PdkShim client = fake.clientWithCookie(pauses, "c");
            client.fetchReport("vrubyrept", "summary", "2", "current", null, null);
            client.fetchReport("vrubyrept", "cashier", "1", "current", "5", null);
            assertEquals("cmd=vrubyrept&cookie=c&period=2&filename=current&reptname=summary", fake.bodies().get(0));
            assertEquals("cmd=vrubyrept&cookie=c&period=1&filename=current&reptname=cashier&cashierNum=5&regNum=",
                    fake.bodies().get(1));
        }
    }

    // ==================================================================== IMPORT_CONFIGURATION

    @Nested
    class ImportConfigurationFolder {

        @TempDir
        Path folder;

        private void write(String name, byte[] bytes) throws IOException {
            Files.write(folder.resolve(name), bytes);
        }

        private void write(String name, String text) throws IOException {
            write(name, text.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void aFileIsOneCommandWithItsLinesJoined() throws IOException {
            write("charitycfg.xml", "<?xml version=\"1.0\"?>\r\n<charityConfig>\r\n  <a/>\r\n</charityConfig>\r\n");
            FakeCommander fake = fakeOk(FakeCommander.OK);
            Map<String, String> responses = fake.clientWithCookie(pauses, "test-cookie").importConfigurationFolder(folder);
            assertEquals(List.of("cmd=ucharitycfg&cookie=test-cookie\n\n"
                    + "<?xml version=\"1.0\"?><charityConfig>  <a/></charityConfig>"), fake.bodies());
            assertEquals(Map.of("ucharitycfg", FakeCommander.OK), responses);
            assertEquals(List.of(Duration.ZERO), pauses); // no per-file pause: the reply is the import done
        }

        @Test
        void anAmpersandFilenameBecomesALiteralQueryParameter() throws IOException {
            write("fepcfg&fep=buypass.xml", "<fepConfig/>");
            FakeCommander fake = fakeOk(FakeCommander.OK);
            fake.clientWithCookie(pauses, "test-cookie").importConfigurationFolder(folder);
            assertEquals(List.of("cmd=ufepcfg&fep=buypass&cookie=test-cookie\n\n<fepConfig/>"), fake.bodies());
        }

        @Test
        void everyFileGoesInSortedOrderWithNoRefresh() throws IOException {
            for (String name : List.of("paymentcfg.xml", "charitycfg.xml", "mobilecfg.xml")) {
                write(name, "<x/>");
            }
            Files.createDirectory(folder.resolve("sub.xml")); // a folder is not a file to import
            FakeCommander fake = fakeOk(FakeCommander.OK);
            Map<String, String> responses = fake.clientWithCookie(pauses, "c").importConfigurationFolder(folder);
            assertEquals(List.of("ucharitycfg", "umobilecfg", "upaymentcfg"), fake.commands());
            assertEquals(List.of("ucharitycfg", "umobilecfg", "upaymentcfg"), List.copyOf(responses.keySet()));
            assertEquals(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO), pauses);
        }

        @Test
        void otpRequiredMakesLaterAttemptsSendTheOtpFirst() throws IOException {
            write("mobilecfg.xml", "<m/>");
            FakeCommander fake = fake(request -> request.body().startsWith("otp=")
                    ? Reply.ok(FakeCommander.OK)
                    : Reply.ok(FakeCommander.bareFault("CGIPortal.OTPRequired")));
            fake.clientWithCookie(pauses, "test-cookie").importConfigurationFolder(folder, () -> "0854");
            assertEquals(List.of("cmd=umobilecfg&cookie=test-cookie\n\n<m/>",
                    "otp=0854&cmd=umobilecfg&cookie=test-cookie\n\n<m/>"), fake.bodies());
            // No OTP pause (rig owner, 2026-09-18, run-time-cuts #6): only the file pause.
            assertEquals(List.of(Duration.ZERO), pauses);
        }

        @Test
        void otpRequiredWithNoReaderSkipsTheFile() throws IOException {
            write("mobilecfg.xml", "<m/>");
            FakeCommander fake = fakeOk(FakeCommander.bareFault("CGIPortal.OTPRequired"));
            assertEquals(Map.of(), fake.clientWithCookie(pauses, "c").importConfigurationFolder(folder));
            assertEquals(1, fake.requests.size());
            assertEquals(List.of(S10), pauses);
        }

        @Test
        void aReadTimeoutPausesTenSecondsAndTriesAgain() throws IOException {
            write("vipercfg.xml", "<v/>");
            AtomicInteger seen = new AtomicInteger();
            FakeCommander fake = fake(request -> seen.incrementAndGet() == 1
                    ? Reply.late(Duration.ofSeconds(5), FakeCommander.OK)
                    : Reply.ok(FakeCommander.OK));
            PdkShim client = fake.client(pauses, Duration.ofMillis(400), null, PdkShim.COMMANDER_PASSWORD, false);
            client.adoptCookie("c");
            Map<String, String> responses = client.importConfigurationFolder(folder);
            assertEquals(2, fake.requests.size());
            assertEquals(Map.of("uvipercfg", FakeCommander.OK), responses);
            assertEquals(List.of(S10, Duration.ZERO), pauses);
        }

        @Test
        void non200AndThePatsErrorTextAreTriedAgain() throws IOException {
            write("acfg.xml", "<a/>");
            FakeCommander fake = fake(FakeCommander.inOrder(List.of(
                    Reply.status(500, "oops"),
                    Reply.ok("Unable to process your request. Please try again later."),
                    Reply.ok(FakeCommander.OK))));
            assertEquals(Map.of("uacfg", FakeCommander.OK), fake.clientWithCookie(pauses, "c").importConfigurationFolder(folder));
            assertEquals(3, fake.requests.size());
        }

        @Test
        void anyOtherFaultCountsAsSuccess() throws IOException {
            // PATS's isNoErrorInResponse checks only the OTP code and response.errormessages.
            write("acfg.xml", "<a/>");
            String fault = FakeCommander.bareFault("CGIPortal.InternalError");
            FakeCommander fake = fakeOk(fault);
            assertEquals(Map.of("uacfg", fault), fake.clientWithCookie(pauses, "c").importConfigurationFolder(folder));
            assertEquals(1, fake.requests.size());
        }

        @Test
        void eachReplyIsLoggedAndACleanOneIsNoWarning() {
            assertEquals("uacfg reply: <VFI:Response/>", PdkShim.importReplyLine("uacfg", FakeCommander.OK));
            assertEquals("uacfg reply: (empty)", PdkShim.importReplyLine("uacfg", " \r\n "));
            assertEquals("u reply: a b", PdkShim.importReplyLine("u", " a \n\t b "));
            assertEquals(Optional.empty(), PdkShim.importFaultWarning("uacfg", FakeCommander.OK));
            String longReply = "x".repeat(PdkShim.IMPORT_REPLY_LOG_CHARS + 50);
            assertEquals("u reply: " + "x".repeat(PdkShim.IMPORT_REPLY_LOG_CHARS), PdkShim.importReplyLine("u", longReply));
        }

        @Test
        void aFaultInAReplyIsAWarningAndStillImported() throws IOException {
            // HANDOFF §1.139: a refused ufuelcfg must not pass silently.
            write("fuelcfg.xml", "<f/>");
            String fault = "<VFI:Response><VFI:Fault><faultCode>CGIPortal.InternalError</faultCode>"
                    + "<faultString>fueling positions in use</faultString></VFI:Fault></VFI:Response>";
            FakeCommander fake = fakeOk(fault);
            assertEquals(Map.of("ufuelcfg", fault), fake.clientWithCookie(pauses, "c").importConfigurationFolder(folder));
            String warning = PdkShim.importFaultWarning("ufuelcfg", fault).orElseThrow();
            assertTrue(warning.startsWith("ufuelcfg"), warning);
            assertTrue(warning.contains("CGIPortal.InternalError: fueling positions in use"), warning);
            assertEquals("u: the Commander answered with a fault (no faultCode); PATS counts it as imported, so the"
                    + " case carries on, but the file may not have been applied",
                    PdkShim.importFaultWarning("u", "<Fault/>").orElseThrow());
        }

        @Test
        void loginRequiredLogsInAgainWithAFreshCookie() throws IOException {
            write("charitycfg.xml", "<c/>");
            FakeCommander fake = fake(request -> {
                if (request.body().startsWith("cmd=validate")) {
                    return Reply.ok(FakeCommander.FRESH_COOKIE);
                }
                if (request.body().contains("cookie=test-cookie")) {
                    return Reply.ok(FakeCommander.bareFault("CGIPortal.LoginRequired"));
                }
                return Reply.ok(FakeCommander.OK);
            });
            fake.clientWithCookie(pauses, "test-cookie").importConfigurationFolder(folder);
            List<String> bodies = fake.bodies();
            assertEquals("cmd=ucharitycfg&cookie=test-cookie\n\n<c/>", bodies.get(0));
            assertTrue(bodies.get(1).startsWith("cmd=validate"));
            assertEquals("cmd=ucharitycfg&cookie=fresh\n\n<c/>", bodies.get(2));
        }

        @Test
        void aCommandThatNeverSucceedsIsSkippedAndTheNextFileStillGoes() throws IOException {
            // PATS logs a failed file and carries on (importConfiguration's catch).
            write("acfg.xml", "<a/>");
            write("bcfg.xml", "<b/>");
            FakeCommander fake = fake(request -> request.body().contains("cmd=uacfg")
                    ? Reply.ok("Unable to process your request. Please try again later.")
                    : Reply.ok(FakeCommander.OK));
            Map<String, String> responses = fake.clientWithCookie(pauses, "c").importConfigurationFolder(folder);
            List<String> expected = new ArrayList<>(Collections.nCopies(15, "uacfg"));
            expected.add("ubcfg");
            assertEquals(expected, fake.commands());
            assertEquals(Map.of("ubcfg", FakeCommander.OK), responses);
            // Deployed request.retries.sleep=20000 before attempts 5-15 (run-time-cuts #2).
            List<Duration> expectedPauses = new ArrayList<>(Collections.nCopies(11, S20));
            expectedPauses.add(S10);
            expectedPauses.add(Duration.ZERO);
            assertEquals(expectedPauses, pauses);
        }

        @Test
        void aFolderThatIsNotThereRaises() {
            PdkShim client = new PdkShim("commander.testrig.invalid");
            assertThrows(java.io.UncheckedIOException.class,
                    () -> client.importConfigurationFolder(folder.resolve("missing")));
        }
    }

    // ==================================================================== inits

    @Nested
    class PerformInitialization {

        private static final String INTERNAL_ERROR = "<VFI:Fault><faultCode>CGIPortal.InternalError</faultCode></VFI:Fault>";

        @Test
        void onePayloadFreeCommandThenPats20SecondPause() {
            FakeCommander fake = fakeOk(FakeCommander.OK);
            assertEquals(FakeCommander.OK, fake.clientWithCookie(pauses, "test-cookie").performInitialization("cfueldrvinit"));
            assertEquals(List.of("cmd=cfueldrvinit&cookie=test-cookie\n\n"), fake.bodies());
            assertEquals(List.of(S20), pauses);
        }

        @Test
        void internalErrorIsRetriedWithASecondPauseFromTheThirdAttempt() {
            List<Reply> replies = new ArrayList<>(Collections.nCopies(3, Reply.ok(INTERNAL_ERROR)));
            replies.add(Reply.ok(FakeCommander.OK));
            FakeCommander fake = fake(FakeCommander.inOrder(replies));
            fake.clientWithCookie(pauses, "c").performInitialization("cdcrdriverinit");
            assertEquals(4, fake.requests.size());
            assertEquals(Collections.nCopies(5, S20), pauses);
        }

        @Test
        void internalErrorOnEveryAttemptFailsAfterPatsSixteen() {
            FakeCommander fake = fakeOk(INTERNAL_ERROR);
            DeviceError e = assertThrows(DeviceError.class,
                    () -> fake.clientWithCookie(pauses, "c").performInitialization("cfuelinit"));
            assertTrue(e.getMessage().contains("cfuelinit"), e.getMessage());
            assertEquals(16, fake.requests.size());
        }

        @Test
        void withoutThePauseAfterAReplyOnlyTheFaultsPauseRemains() {
            FakeCommander fake = fake(FakeCommander.inOrder(List.of(
                    Reply.ok("<x>CGIPortal.InternalError</x>"), Reply.ok("<ok/>"))));
            assertEquals("<ok/>", fake.clientWithCookie(pauses, "c").performInitialization("cfuelinit", null, false));
            // The fault's reply is still followed by PATS's 20 s; the good one is not.
            assertEquals(List.of(S20), pauses);
        }
    }

    @Nested
    class RefreshConfiguration {

        private PdkShim realPauses(FakeCommander fake) {
            PdkShim client = new PdkShim(fake.host(), fake.port(), Duration.ofSeconds(5), null,
                    PdkShim.COMMANDER_PASSWORD, false, CgiLink.Pauser.REAL);
            client.adoptCookie("test-cookie");
            return client;
        }

        @Test
        void anInternalErrorIsRetriedUntilTheCommanderAnswers() {
            FakeCommander fake = fake(FakeCommander.inOrder(List.of(
                    Reply.ok(FakeCommander.fault("CGIPortal.InternalError")), Reply.ok("<Response/>"))));
            Element response = realPauses(fake).refreshConfiguration(null, Duration.ofSeconds(5), Duration.ofMillis(10));
            assertEquals(List.of("crefreshcfg", "crefreshcfg"), fake.requests.stream().map(FakeCommander.Request::cmd).toList());
            assertEquals("Response", response.getTagName());
        }

        @Test
        void anyOtherFaultRaisesAtOnce() {
            FakeCommander fake = fakeOk(FakeCommander.fault("bad command"));
            DeviceError e = assertThrows(DeviceError.class,
                    () -> realPauses(fake).refreshConfiguration(null, Duration.ofSeconds(5), Duration.ofMillis(10)));
            assertTrue(e.getMessage().contains("bad command"));
            assertEquals(1, fake.requests.size());
        }

        @Test
        void anInternalErrorThatNeverClearsTimesOut() {
            FakeCommander fake = fakeOk(FakeCommander.fault("CGIPortal.InternalError"));
            Bounded.TimeoutExpired e = assertThrows(Bounded.TimeoutExpired.class,
                    () -> realPauses(fake).refreshConfiguration(null, Duration.ofMillis(50), Duration.ofMillis(10)));
            assertEquals("crefreshcfg exceeded its 0.05s budget", e.getMessage());
        }

        @Test
        void byDefaultItPolls15Times20SecondsAsDeployedPats() {
            assertEquals(Duration.ofSeconds(300), PdkShim.REFRESH_TIMEOUT);
            assertEquals(S20, PdkShim.REFRESH_INTERVAL);
            FakeCommander fake = fakeOk("<Response/>");
            assertEquals("Response", fake.clientWithCookie(pauses, "c").refreshConfiguration().getTagName());
            assertEquals(List.of(), pauses);
        }
    }

    @Nested
    class ControlFunction {

        @Test
        void theInitTypeIsSentAsTheLiteralCommandWithNoPrefix() {
            FakeCommander fake = fakeOk("<Response/>");
            fake.clientWithCookie(pauses, "test-cookie").controlFunction("cfuelinit");
            assertEquals(List.of("cmd=cfuelinit&cookie=test-cookie"), fake.bodies());
        }

        @Test
        void aSecondInitTypeIsIndependent() {
            FakeCommander fake = fakeOk("<Response/>");
            PdkShim client = fake.clientWithCookie(pauses, "c");
            client.controlFunction("cfuelinit");
            client.controlFunction("cdcrinit");
            assertEquals(List.of("cfuelinit", "cdcrinit"), fake.requests.stream().map(FakeCommander.Request::cmd).toList());
        }

        @Test
        void aFaultRaisesDeviceError() {
            FakeCommander fake = fakeOk(FakeCommander.fault("bad init type"));
            DeviceError e = assertThrows(DeviceError.class, () -> fake.clientWithCookie(pauses, "c").controlFunction("cbogusinit"));
            assertTrue(e.getMessage().contains("bad init type"));
        }
    }

    // ==================================================================== sessions

    @Nested
    class StaleCookie {

        private final String loginRequired = FakeCommander.bareFault("CGIPortal.LoginRequired");

        @Test
        void loginRequiredLogsInAgainAndRetriesOnce() {
            FakeCommander fake = fake(request -> {
                if (request.body().startsWith("cmd=validate")) {
                    return Reply.ok(FakeCommander.FRESH_COOKIE);
                }
                if (request.body().contains("cookie=fresh")) {
                    return Reply.ok("<transNumList><trSeq>7</trSeq></transNumList>");
                }
                return Reply.ok(loginRequired);
            });
            PdkShim client = fake.clientWithCookie(pauses, "stale");
            assertEquals(List.of("7"), client.listTransactionNumbers());
            assertEquals(List.of("vtransnumlist", "validate", "vtransnumlist"),
                    fake.requests.stream().map(FakeCommander.Request::cmd).toList());
            assertTrue(fake.bodies().get(0).contains("cookie=stale"));
            assertTrue(fake.bodies().get(2).contains("cookie=fresh"));
            assertEquals("fresh", client.cookie());
            client.listTransactionNumbers(); // the next call uses the new cookie straight away
            assertEquals(4, fake.requests.size());
            assertTrue(fake.bodies().get(3).contains("cookie=fresh"));
        }

        @Test
        void aSecondLoginRequiredRaises() {
            FakeCommander fake = fake(request -> request.body().startsWith("cmd=validate")
                    ? Reply.ok(FakeCommander.FRESH_COOKIE)
                    : Reply.ok(loginRequired));
            DeviceError e = assertThrows(DeviceError.class, () -> fake.clientWithCookie(pauses, "stale").listTransactionNumbers());
            assertTrue(e.getMessage().contains("LoginRequired"));
            assertEquals(List.of("vtransnumlist", "validate", "vtransnumlist"),
                    fake.requests.stream().map(FakeCommander.Request::cmd).toList());
        }

        @Test
        void validateItselfIsNotRetried() {
            FakeCommander fake = fakeOk(loginRequired);
            DeviceError e = assertThrows(DeviceError.class, () -> fake.client(pauses).listTransactionNumbers());
            assertTrue(e.getMessage().contains("LoginRequired"));
            assertEquals(1, fake.requests.size());
        }
    }

    @Nested
    class AnExpiredPasswordIsNamedAtLogin {

        private FakeCommander commander(String days, String expire) {
            return fake(request -> request.cmd().equals("validate")
                    ? Reply.ok("<credential><cookie>c00k1e</cookie><user name='manager'>"
                            + "<passwd expire='" + expire + "' days='" + days + "' minLen='7' maxLen='40'/>"
                            + "</user></credential>")
                    : Reply.ok("<posScreenConfig/>"));
        }

        private List<String> cmds(FakeCommander fake) {
            return fake.requests.stream().map(FakeCommander.Request::cmd).toList();
        }

        @Test
        void anExpiredPasswordStopsAtLoginAndSaysSo() {
            FakeCommander fake = commander("-17", "1");
            CommanderPasswordExpired e = assertThrows(CommanderPasswordExpired.class,
                    () -> fake.client(pauses).viewConfiguration("posscreencfg"));
            assertInstanceOf(DeviceError.class, e);
            assertTrue(e.getMessage().contains("expired 17 day"), e.getMessage());
            assertEquals(-17, e.days());
            assertEquals(List.of("validate"), cmds(fake)); // nothing sent on a session that would be refused
        }

        @ParameterizedTest
        @CsvSource({"82,1", "-5,0", "soon,1"})
        void aLiveOrNeverExpiringPasswordLogsIn(String days, String expire) {
            FakeCommander fake = commander(days, expire);
            fake.client(pauses).viewConfiguration("posscreencfg");
            assertEquals(List.of("validate", "vposscreencfg"), cmds(fake));
        }

        @Test
        void aCallerChangingThePasswordMayLogInWithAnExpiredOne() {
            FakeCommander fake = commander("-17", "1");
            fake.client(pauses, Duration.ofSeconds(5), null, "temp-pw", true).viewConfiguration("posscreencfg");
            assertEquals("validate", cmds(fake).get(0));
            assertEquals("cmd=validate&user=manager&passwd=temp-pw", fake.bodies().get(0));
        }

        @Test
        void theFuncListRefusesAnExpiredSessionToo() {
            FakeCommander fake = commander("0", "1");
            assertThrows(CommanderPasswordExpired.class, () -> fake.client(pauses).functionList());
        }
    }

    // ==================================================================== OTP and upgrade control

    @Nested
    class OtpAwarePost {

        @Test
        void noOtpNeededPassesThrough() {
            FakeCommander fake = fakeOk("<ok/>");
            assertEquals("ok", fake.clientWithCookie(pauses, "x").closeDay(null).getTagName());
            assertEquals(1, fake.requests.size());
        }

        @Test
        void otpRequiredRetriesWithTheOtpFromTheCallback() {
            FakeCommander fake = fake(request -> request.fields().containsKey("otp")
                    ? Reply.ok("<ok/>")
                    : Reply.ok(FakeCommander.fault("CGIPortal.OTPRequired")));
            Element result = fake.clientWithCookie(pauses, "x").beginUpgrade("tok", () -> "0854");
            assertEquals("ok", result.getTagName());
            assertEquals("cmd=cbeginupgrade&cookie=x&token=tok", fake.bodies().get(0));
            assertEquals("cmd=cbeginupgrade&cookie=x&token=tok&otp=0854", fake.bodies().get(1));
        }

        @Test
        void theClientsOwnReaderIsTheFallback() {
            FakeCommander fake = fake(request -> request.fields().containsKey("otp")
                    ? Reply.ok("<ok/>")
                    : Reply.ok(FakeCommander.fault("CGIPortal.OTPRequired")));
            PdkShim client = fake.client(pauses, Duration.ofSeconds(5), () -> "4321", PdkShim.COMMANDER_PASSWORD, false);
            client.adoptCookie("x");
            client.upgradeSummary(null);
            assertEquals("4321", fake.requests.get(1).fields().get("otp"));
        }

        @Test
        void otpRequiredWithoutAReaderPropagates() {
            FakeCommander fake = fakeOk(FakeCommander.fault("CGIPortal.OTPRequired"));
            DeviceError e = assertThrows(DeviceError.class, () -> fake.clientWithCookie(pauses, "x").beginUpgrade("t", null));
            assertTrue(e.getMessage().contains("OTPRequired"));
            assertEquals(1, fake.requests.size());
        }

        @Test
        void aFaultThatIsNotAnOtpFaultIsNotRetried() {
            FakeCommander fake = fakeOk(FakeCommander.fault("CGIPortal.InternalError"));
            assertThrows(DeviceError.class, () -> fake.clientWithCookie(pauses, "x").beginUpgrade("t", () -> "0854"));
            assertEquals(1, fake.requests.size());
        }
    }

    @Nested
    class UpgradeControlCommands {

        @Test
        void upgradeSummarySendsVupgradesummaryWithTheCookie() {
            FakeCommander fake = fakeOk("<summary/>");
            fake.clientWithCookie(pauses, "test-cookie").upgradeSummary(null);
            assertEquals(List.of("cmd=vupgradesummary&cookie=test-cookie"), fake.bodies());
        }

        @Test
        void closeDaySendsCclosepdnow() {
            FakeCommander fake = fakeOk("<ok/>");
            fake.clientWithCookie(pauses, "test-cookie").closeDay(null);
            assertEquals(List.of("cmd=cclosepdnow&cookie=test-cookie"), fake.bodies());
        }

        @Test
        void beginUpgradeSendsTheToken() {
            FakeCommander fake = fakeOk("<ok/>");
            fake.clientWithCookie(pauses, "test-cookie").beginUpgrade("tok123", null);
            assertEquals(List.of("cmd=cbeginupgrade&cookie=test-cookie&token=tok123"), fake.bodies());
        }
    }

    // ==================================================================== Commander Connect

    @Nested
    class CloudConnect {

        @Test
        void theProfilePayloadNamesTheEnumValue() {
            FakeCommander fake = fakeOk("<r/>");
            fake.clientWithCookie(pauses, "ck").setCloudConnectProfile("QAT");
            String[] parts = fake.bodies().get(0).split("\n\n", 2);
            assertEquals("cmd=ucloudconnectprofile&cookie=ck", parts[0]);
            assertTrue(parts[1].contains("<profile>QAT</profile>"));
            assertTrue(parts[1].contains(PdkShim.DOMAIN_NS));
        }

        @Test
        void connectEncodesWhatWouldBreakTheBody() {
            FakeCommander fake = fakeOk("<r/>");
            fake.clientWithCookie(pauses, "ck").cloudConnect("a+b@x.com", "p&w%");
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("cmd", "ccloudconnect");
            expected.put("cookie", "ck");
            expected.put("user", "a%2Bb@x.com");
            expected.put("passwd", "p%26w%25");
            assertEquals(expected, fake.requests.get(0).fields());
        }

        @Test
        void quoteKeepsPythonsSafeSetAndEncodesUtf8() {
            assertEquals("Az09_.-~@", PdkShim.quote("Az09_.-~@"));
            assertEquals("a%20b%2F%C3%A9", PdkShim.quote("a b/é"));
        }

        @Test
        void statusReadsRegisteredAndInProgress() {
            FakeCommander fake = fakeOk("<cloudConnect><registrationTimeStamp>t</registrationTimeStamp></cloudConnect>");
            assertEquals(new CloudConnectStatus(true, "registered"), fake.clientWithCookie(pauses, "ck").cloudConnectStatus());
            fake.answer(request -> Reply.ok("<cloudConnect><cloudConnectStatus code=\"AUTH\">bad</cloudConnectStatus></cloudConnect>"));
            assertEquals(new CloudConnectStatus(false, "AUTH: bad"), fake.clientWithCookie(pauses, "ck").cloudConnectStatus());
            fake.answer(request -> Reply.ok("<cloudConnect><cloudConnectStatus code=\"INITIATE_CLOUD_CONNECT\"/></cloudConnect>"));
            assertEquals(new CloudConnectStatus(false, "INITIATE_CLOUD_CONNECT"),
                    fake.clientWithCookie(pauses, "ck").cloudConnectStatus());
            fake.answer(request -> Reply.ok("<cloudConnect/>"));
            assertEquals(new CloudConnectStatus(false, "no status in the response"),
                    fake.clientWithCookie(pauses, "ck").cloudConnectStatus());
            assertEquals("cmd=vcloudconnectstatus&cookie=ck", fake.bodies().get(0));
        }

        @Test
        void connectivityIsReadApartFromRegistration() {
            // The lab Commander's reply on 2026-10-13, namespace and all.
            FakeCommander fake = fakeOk(FakeCommander.fixture("cloudconnect-lab.xml"));
            PdkShim client = fake.clientWithCookie(pauses, "ck");
            assertEquals(new CloudConnectStatus(true, "registered"), client.cloudConnectStatus());
            assertEquals("Offline", client.cloudConnectivity());
            fake.answer(request -> Reply.ok("<cloudConnect/>"));
            assertEquals("", client.cloudConnectivity());
        }
    }

    // ==================================================================== view, update, funcList, FEPs

    @Nested
    class ViewUpdateAndCatalogue {

        @Test
        void viewSendsVPlusTheAreaAndReturnsTheText() {
            String config = "<domain:fuelConfig xmlns:domain=\"" + PdkShim.DOMAIN_NS + "\"><grade>1</grade></domain:fuelConfig>";
            FakeCommander fake = fakeOk(config);
            assertEquals(config, fake.clientWithCookie(pauses, "c").viewConfiguration("fuelcfg"));
            assertEquals("cmd=vfuelcfg&cookie=c\n\n", fake.bodies().get(0));
        }

        @Test
        void aViewThatFaultsRaises() {
            FakeCommander fake = fakeOk(FakeCommander.fault("CGIPortal.UnknownCommand"));
            CommanderFault fault = assertThrows(CommanderFault.class,
                    () -> fake.clientWithCookie(pauses, "c").viewConfiguration("mysterycfg"));
            assertEquals("CGIPortal.UnknownCommand", fault.code());
        }

        @Test
        void aViewThatIsNotXmlIsAProtocolError() {
            FakeCommander fake = fakeOk("plain words");
            assertThrows(DeviceProtocolError.class, () -> fake.clientWithCookie(pauses, "c").viewConfiguration("x"));
        }

        @Test
        void anUpdateJoinsItsLinesAndAFaultRaises() {
            FakeCommander fake = fakeOk(FakeCommander.NS_OK);
            PdkShim client = fake.clientWithCookie(pauses, "c");
            assertEquals(FakeCommander.NS_OK, client.updateConfiguration("fuelcfg", "<a>\r\n<b/>\n</a>\r"));
            assertEquals("cmd=ufuelcfg&cookie=c\n\n<a><b/></a>", fake.bodies().get(0));
            fake.answer(request -> Reply.ok(FakeCommander.fault("CGIPortal.InternalError")));
            assertThrows(CommanderFault.class, () -> client.updateConfiguration("fuelcfg", "<a/>"));
        }

        @Test
        void sendCommandReturnsAFaultAsTextForTheCaller() {
            String fault = FakeCommander.fault("CGIPortal.InternalError");
            FakeCommander fake = fakeOk(fault);
            assertEquals(fault, fake.clientWithCookie(pauses, "c").sendCommand("cpsichallenge", "<x/>"));
            assertEquals("cmd=cpsichallenge&cookie=c\n\n<x/>", fake.bodies().get(0));
        }

        @Test
        void theFuncListComesFromAFreshValidateWhoseCookieIsAdopted() {
            FakeCommander fake = fakeOk(FakeCommander.fixture("validate-funclist.xml"));
            PdkShim client = fake.clientWithCookie(pauses, "old");
            List<CgiReplies.CgiFunction> functions = client.functionList();
            assertEquals(List.of("vfuelcfg", "vpairingotp", "ucloudconnectprofile"),
                    functions.stream().map(CgiReplies.CgiFunction::cmd).toList());
            assertEquals(List.of("vpairingotp", "ucloudconnectprofile"),
                    functions.stream().filter(CgiReplies.CgiFunction::otpRequired).map(CgiReplies.CgiFunction::cmd).toList());
            assertEquals("cmd=validate&user=manager&passwd=A111111", fake.bodies().get(0));
            assertEquals("c00k1e", client.cookie());
        }

        @Test
        void fepDetailsAreViewedAndParsed() {
            FakeCommander fake = fakeOk(FakeCommander.fixture("fepdetails.xml"));
            Map<String, CgiReplies.FepDetail> details = fake.clientWithCookie(pauses, "c").fepDetails();
            assertTrue(details.get("buypass").primary());
            assertFalse(details.get("PropFleet").enabled());
            assertEquals("vfepdetails", fake.requests.get(0).cmd());
        }

        @Test
        void anOtpGatedViewReadsTheOtpOnlyAfterTheFaultMintsIt() {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            FakeCommander fake = fake(request -> {
                order.add("post " + (request.fields().containsKey("otp") ? "with otp" : "without"));
                return request.fields().containsKey("otp")
                        ? Reply.ok("<domain:p xmlns:domain=\"" + PdkShim.DOMAIN_NS + "\"><y>1</y></domain:p>")
                        : Reply.ok(FakeCommander.fault("CGIPortal.OTPRequired"));
            });
            fake.clientWithCookie(pauses, "c").viewConfiguration("pairingsecret", () -> {
                order.add("read otp");
                return "4321";
            });
            assertEquals(List.of("post without", "read otp", "post with otp"), order);
            assertNotEquals(fake.bodies().get(0), fake.bodies().get(1));
            assertEquals("otp=4321&cmd=vpairingsecret&cookie=c\n\n", fake.bodies().get(1));
        }
    }
}
