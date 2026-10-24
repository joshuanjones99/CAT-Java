package com.verifone.cat.devices.commander;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.devices.commander.CgiReplies.CgiFunction;
import com.verifone.cat.devices.commander.CgiReplies.CloudConnectStatus;
import com.verifone.cat.devices.commander.CgiReplies.FepDetail;
import com.verifone.cat.preflight.Bounded;

/**
 * The Commander's CGILink configuration client: the Java port of the Python CAT's
 * {@code petrotest.devices.config_shim.PDKShim} (named {@code ConfigShimClient} until
 * 2026-09-18). Read the Python CAT's {@code docs/commander-config-surface.md} before changing
 * it: the surface is CGILink over HTTP, not RMI, and it enumerates itself.
 *
 * <p>The surface is one endpoint, {@code POST http://<commander>/cgi-bin/CGILink?} on port 80,
 * that takes {@code cmd=...&...}, a blank line and an optional XML payload, and answers XML.
 * Failures come back as HTTP 200 with a fault body. A session cookie from {@code validate}
 * authenticates every other command; some commands want a one-time password, which the fault
 * that refuses them mints and which is then read over SSH ({@code cat /var/logins/tat}) by the
 * caller's {@code readOtp}. The transport is {@link CgiLink}; the reply parsers are
 * {@link CgiReplies}.
 *
 * <p>Errors: a transport failure or a non-2xx status is {@link DeviceUnreachable}; a reply that
 * is not XML, or not the shape a parser needs, is {@link DeviceProtocolError}; a fault is a
 * {@link CommanderFault} (a {@link DeviceError}); a surface with no command behind it is
 * {@link DeviceUnavailable}, never {@code false}.
 *
 * <p>Two families of call, as in the Python. The PATS-shaped ones ({@link #sendCommand},
 * {@link #importConfigurationFolder}, {@link #viewConfiguration}, {@link #updateConfiguration},
 * {@link #performInitialization}) retry attempt for attempt as petroapps'
 * {@code CommanderHttpClient} does and return the reply text. The rest post once (one login
 * retry, one OTP retry) and return the parsed reply.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Nothing in this package has been sent to a
 * Commander; the Python it ports has, for the calls its Javadoc says so of.
 */
public final class PdkShim implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PdkShim.class);

    /** CGILink's port: plain HTTP on 80, not 443, not Tomcat's 8080 (config-surface doc section 1). */
    public static final int DEFAULT_PORT = 80;

    /** The Python's default per-request timeout, for a client built without one. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The read timeout every production client is built with (commander-config, day close,
     * DEV-mode init, PSI init). <b>Deliberately not PATS's.</b> PATS's {@code http.timeout.mils}
     * is unset, so {@code CommanderHttpClient} reads for 90 s; the Commander gives each CGI command
     * 120 s ({@code Default Socket timeout of'120000'}, HANDOFF.md §1.59), so a 90-120 s import was
     * abandoned by the client, applied by the Commander anyway, and retried on top of itself. Rig
     * owner's decision, 2026-09-18: 150 s, the Commander's 120 s plus 30 s for the reply to arrive.
     * Past that the Commander has given up, so reading longer buys nothing. UNVERIFIED AGAINST
     * HARDWARE: no command has been sent with it.
     */
    public static final Duration COMMANDER_READ_TIMEOUT = Duration.ofSeconds(150);

    /** {@code request.retries=15} (deployed {@code automn_app_config.prop:274}): attempts per command. */
    public static final int PATS_REQUEST_RETRIES = 15;

    /**
     * {@code request.retries.sleep=20000} (deployed {@code automn_app_config.prop:275}), paid before
     * every attempt after the fourth, and after every init command's reply. petroapps' copy says
     * 60000; the reference is the PATS deployed on the controller (rig owner, 2026-09-18,
     * {@code docs/run-time-cuts.md} §5). UNVERIFIED AGAINST HARDWARE: no CAT run has used 20 s.
     */
    public static final Duration PATS_REQUEST_RETRIES_SLEEP = Duration.ofSeconds(20);

    /** {@code pats.genreic.sleep.10sec=10000}: paid after an I/O error and after a skipped file. */
    public static final Duration PATS_SLEEP_10_SECONDS = Duration.ofSeconds(10);

    /**
     * What deployed PATS pauses after each imported file: {@code Automn.jar}'s
     * {@code ConfigurationManager.importConfiguration} calls {@code Utility.dummySleep(500L)}.
     * Recorded, not used: see {@link #IMPORT_FILE_PAUSE}.
     */
    public static final Duration PATS_IMPORT_FILE_SLEEP = Duration.ofMillis(500);

    /**
     * What CAT pauses after each imported file instead: nothing. The Commander answers an import
     * once it has applied it (lab Commander's syslog, 2026-10-11). Rig owner's request, 2026-10-11
     * (lever 3); departs from PATS. Run once on the lab rig from the Python (HANDOFF.md §1.134).
     */
    public static final Duration IMPORT_FILE_PAUSE = Duration.ZERO;

    /**
     * {@code response.errormessages}: reply text counted as a failed attempt. petroapps' key; the
     * deployed file has none, kept as the Python keeps it ({@code docs/run-time-cuts.md} §5).
     */
    public static final List<String> PATS_RESPONSE_ERROR_MESSAGES =
            List.of("Unable to process your request. Please try again later.");

    /** {@code otp.errorcode} ({@code automn_app_config.prop:269}). */
    public static final String OTP_REQUIRED_FAULT = "CGIPortal.OTPRequired";

    /** {@code cgi.error} ({@code automn_app_config.prop:276}): the fault an init command is retried on. */
    public static final String PATS_CGI_ERROR = "CGIPortal.InternalError";

    /** The section of the Python CAT's DESIGN.md that owns this client, quoted in every DeviceUnavailable. */
    public static final String SECTION = "§6.2";

    /**
     * The CGILink user. Hardcoded by the rig owner's decision of 2026-09-11 (HANDOFF.md §1.24 in
     * the Python CAT; its CLAUDE.md hard rule 6, first carve-out): identical on every Commander
     * this harness will ever run against, gating a lab-only surface with no real data behind it.
     * The carve-out covers this login and nothing else.
     */
    public static final String COMMANDER_USER = "manager";

    /** The CGILink password: the same rig-owner decision and carve-out as {@link #COMMANDER_USER}. */
    public static final String COMMANDER_PASSWORD = "A111111";

    /** How much of an import's reply is logged. A clean reply is a few dozen characters. */
    public static final int IMPORT_REPLY_LOG_CHARS = 500;

    /** "Refresh Configuration" ({@code FunctionCmdEnum.CMD_CREFRESHCFG}). */
    public static final String REFRESH_COMMAND = "crefreshcfg";

    /** {@link #refreshConfiguration}'s default budget: {@code request.retries} x {@code request.retries.sleep}. */
    public static final Duration REFRESH_TIMEOUT = PATS_REQUEST_RETRIES_SLEEP.multipliedBy(PATS_REQUEST_RETRIES);

    /** {@link #refreshConfiguration}'s default spacing: {@code request.retries.sleep}. */
    public static final Duration REFRESH_INTERVAL = PATS_REQUEST_RETRIES_SLEEP;

    /** Namespace of {@code cloudConnectProfile} ({@code cgiClassMap} {@code ucloudconnectprofile}'s docType). */
    static final String DOMAIN_NS = "urn:vfi-sapphire:np.domain.2001-07-01";

    private static final Pattern NEWLINES = Pattern.compile("\r\n|\r|\n");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FAULT_CODE =
            Pattern.compile("<(?:\\w+:)?faultCode>([^<]*)</(?:\\w+:)?faultCode>", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FAULT_STRING =
            Pattern.compile("<(?:\\w+:)?faultString>([^<]*)</(?:\\w+:)?faultString>", Pattern.UNICODE_CHARACTER_CLASS);

    private final String commanderHost;
    private final boolean passwordMayBeExpired;
    private final CgiLink link;

    /** A client with the Python's defaults: 5 s timeout, no OTP reader, the lab password. */
    public PdkShim(String commanderHost) {
        this(commanderHost, DEFAULT_TIMEOUT, null);
    }

    /**
     * @param commanderHost the Commander, from the rig file (never a literal)
     * @param timeout       connect and per-request read timeout; production passes {@link #COMMANDER_READ_TIMEOUT}
     * @param readOtp       the OTP source for every OTP-aware call that passes none, or {@code null};
     *                      typically an SSH {@code cat /var/logins/tat} that connects only when called
     */
    public PdkShim(String commanderHost, Duration timeout, Supplier<String> readOtp) {
        this(commanderHost, timeout, readOtp, COMMANDER_PASSWORD, false);
    }

    /**
     * @param password             the user's password; only a fresh PSI install differs (it generates
     *                             one, which PSI init logs in with once to set {@link #COMMANDER_PASSWORD})
     * @param passwordMayBeExpired log in even when {@code validate} says the password has expired:
     *                             only for a caller that is changing it ({@code changepasswd} is one of
     *                             the few functions left then)
     */
    public PdkShim(String commanderHost, Duration timeout, Supplier<String> readOtp, String password,
            boolean passwordMayBeExpired) {
        this(commanderHost, DEFAULT_PORT, timeout, readOtp, password, passwordMayBeExpired, CgiLink.Pauser.REAL);
    }

    /** The full constructor: a test passes a loopback port and a recording pauser. */
    PdkShim(String commanderHost, int port, Duration timeout, Supplier<String> readOtp, String password,
            boolean passwordMayBeExpired, CgiLink.Pauser pauser) {
        this.commanderHost = commanderHost;
        this.passwordMayBeExpired = passwordMayBeExpired;
        this.link = new CgiLink(commanderHost, port, timeout, readOtp, password, passwordMayBeExpired, pauser);
    }

    public String commanderHost() {
        return commanderHost;
    }

    /** {@code http://<host>/cgi-bin/CGILink?}, the literal {@code ?} included. */
    public String baseUrl() {
        return link.endpoint().toString();
    }

    public Duration timeout() {
        return link.timeout();
    }

    public String password() {
        return link.password();
    }

    public boolean passwordMayBeExpired() {
        return passwordMayBeExpired;
    }

    /**
     * The OTP source this client was built with, for a caller that speaks CGILink itself (the
     * Python's PATS BDD {@code SEND_HTTP_REQUEST}).
     */
    public Supplier<String> otpReader() {
        return link.defaultReadOtp();
    }

    /** Nothing to release: each request has its own connection. Kept so a caller can use try-with-resources. */
    @Override
    public void close() {}

    /** The cached session cookie, or {@code null}: for tests and diagnostics. */
    String cookie() {
        return link.cookie();
    }

    /** Seeds the session cookie, as the Python's tests set {@code _cookie}. */
    void adoptCookie(String cookie) {
        link.adoptCookie(cookie);
    }

    /** The session cookie, logging in if none is cached: the Python's {@code _authenticate}. */
    String authenticate() {
        return link.authenticate();
    }

    // ======================================================================
    // Configuration status and control: no command behind them
    // ======================================================================

    /**
     * The active configuration folder: no command for it is known.
     *
     * <p>The Python raises the same: the command names for configuration queries are not
     * enumerated in the config-surface doc. Determine one from the funcList ({@link #functionList})
     * before implementing this.
     *
     * @throws DeviceUnavailable always
     */
    public String activeConfigFolder() {
        throw unknownCommand("ConfigStatus.active_config_folder()", "(likely a 'v*cfg' command) by reading "
                + "the funcList from the validate response");
    }

    /**
     * Whether {@code expectedFolder} is the active configuration: the preflight
     * {@code config_loaded} check's question. No command for it is known.
     *
     * @throws DeviceUnavailable always
     */
    public boolean isConfigLoaded(String expectedFolder) {
        throw unknownCommand("ConfigStatus.is_config_loaded()", "(likely a 'v*cfg' command) by reading "
                + "the funcList from the validate response");
    }

    /**
     * Loads a configuration folder on the Commander: {@code config_loaded}'s repair. No command
     * for it is known; PATS's way of pushing a folder is {@link #importConfigurationFolder}.
     *
     * @throws DeviceUnavailable always
     */
    public boolean loadConfig(String folder) {
        throw unknownCommand("ConfigControl.load_config()", "(likely a 'u*cfg' command) by reading "
                + "the funcList from the validate response");
    }

    /**
     * Every configuration folder on the Commander. No command for it is known.
     *
     * @throws DeviceUnavailable always
     */
    public List<String> listAvailableConfigs() {
        throw unknownCommand("ConfigStatus.list_available_configs()", "by reading the funcList from the "
                + "validate response (or by inspecting the Commander's filesystem)");
    }

    private static DeviceUnavailable unknownCommand(String surface, String how) {
        return new DeviceUnavailable(surface, SECTION + ": Configuration command names are not enumerated in "
                + "docs/commander-config-surface.md. Determine the command name " + how + ", then update this method.");
    }

    // ======================================================================
    // Configuration import: PATS's IMPORT_CONFIGURATION
    // ======================================================================

    /** {@link #importConfigurationFolder(Path, Supplier)} with the client's own OTP reader. */
    public Map<String, String> importConfigurationFolder(Path folder) {
        return importConfigurationFolder(folder, null);
    }

    /**
     * Pushes every file in {@code folder} to the Commander, as PATS's {@code IMPORT_CONFIGURATION}
     * does. Step for step with petroapps' {@code ConfigurationManager.importConfiguration()}
     * (read 2026-09-15, HANDOFF.md §1.40):
     * <ul>
     *   <li>one command per file: {@code "u"} plus the filename without its last four characters.
     *       {@code fepcfg&fep=buypass.xml} becomes {@code ufepcfg&fep=buypass}, so the extra
     *       parameter rides in through the filename;</li>
     *   <li>the payload is the file's lines joined with no line breaks, as
     *       {@code CommanderHttpClient.readPayloadFile()} builds it;</li>
     *   <li>each command goes through {@link #sendCommand}'s retries, then {@link #IMPORT_FILE_PAUSE}
     *       (nothing; deployed PATS pauses 0.5 s, petroapps' 1 s);</li>
     *   <li>a command that still fails after PATS's retries is logged ("skipping") and followed by a
     *       10 s pause, and the next file is sent. The import does not raise for it;</li>
     *   <li>each reply is logged, and a fault in one is a warning ({@link #importFaultWarning}). It
     *       still counts as imported, as in PATS.</li>
     * </ul>
     * No {@code crefreshcfg} follows: PATS's action sends none. Files go in sorted order, since
     * {@code File.listFiles()} promises none.
     *
     * @param folder  a local directory of payload files
     * @param readOtp the OTP source for a command that faults {@code OTPRequired}, or {@code null}
     *                for the client's own
     * @return the reply text of each command that succeeded, by command, in send order; a skipped
     *         command is absent
     * @throws UncheckedIOException the folder or a file in it cannot be read
     */
    public Map<String, String> importConfigurationFolder(Path folder, Supplier<String> readOtp) {
        List<Path> paths;
        try (Stream<Path> listing = Files.list(folder)) {
            paths = listing.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the configuration folder " + folder, e);
        }
        Map<String, String> responses = new LinkedHashMap<>();
        int index = 0;
        for (Path path : paths) {
            index++;
            String name = path.getFileName().toString();
            String command = "u" + name.substring(0, Math.max(0, name.length() - 4));
            String payload;
            try {
                payload = NEWLINES.matcher(Files.readString(path, StandardCharsets.UTF_8)).replaceAll("");
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the configuration file " + path, e);
            }
            log.info("importing {} ({} of {})", name, index, paths.size());
            try {
                String reply = link.sendAsPats(command, payload, readOtp);
                responses.put(command, reply);
                logImportReply(command, reply);
                link.pauser().pause(IMPORT_FILE_PAUSE);
            } catch (RuntimeException e) { // PATS catches every exception here
                log.error("Failed to import configuration for {}, skipping: {}", command, e.getMessage());
                log.info("waiting {} s after the failed import (PATS's pause)", PATS_SLEEP_10_SECONDS.toSeconds());
                link.pauser().pause(PATS_SLEEP_10_SECONDS);
            }
        }
        return responses;
    }

    /**
     * The log line for one imported file's reply: whitespace folded, at most
     * {@link #IMPORT_REPLY_LOG_CHARS} characters, {@code (empty)} for nothing.
     */
    static String importReplyLine(String command, String text) {
        String reply = WHITESPACE.matcher(text).replaceAll(" ").strip();
        String shown = reply.length() > IMPORT_REPLY_LOG_CHARS ? reply.substring(0, IMPORT_REPLY_LOG_CHARS) : reply;
        return command + " reply: " + (shown.isEmpty() ? "(empty)" : shown);
    }

    /**
     * The warning for a fault in an imported file's reply, if it holds one. The import still counts
     * it as imported, as PATS does; this only makes it visible. On 2026-10-13 run
     * {@code 36d9831ba431} lost the grade prompt after a {@code ufuelcfg} that answered HTTP 200 in
     * 0.5 s, and nothing recorded whether the Commander had taken the file (HANDOFF.md §1.139).
     * A regular expression, not XML parsing: the reply is judged whatever shape it is in.
     */
    static Optional<String> importFaultWarning(String command, String text) {
        Matcher code = FAULT_CODE.matcher(text);
        boolean hasCode = code.find();
        if (!hasCode && !text.contains("Fault")) {
            return Optional.empty();
        }
        Matcher detail = FAULT_STRING.matcher(text);
        String detailText = detail.find() ? ": " + detail.group(1).strip() : "";
        return Optional.of(command + ": the Commander answered with a fault ("
                + (hasCode ? code.group(1).strip() : "no faultCode") + detailText
                + "); PATS counts it as imported, so the case carries on, but the file may not have been applied");
    }

    private static void logImportReply(String command, String text) {
        log.info("{}", importReplyLine(command, text));
        importFaultWarning(command, text).ifPresent(warning -> log.warn("{}", warning));
    }

    /**
     * Posts {@code command} with {@code payload} as PATS's {@code CommanderHttpClient.sendCommand}
     * does: the retry loop documented on {@link CgiLink#sendAsPats}. Any fault other than login,
     * OTP or busy comes back in the text, as in PATS, so the caller checks it.
     *
     * @param readOtp the OTP source, or {@code null} for the client's own
     * @throws DeviceError no attempt succeeded, or an OTP was needed and there is no reader
     */
    public String sendCommand(String command, String payload, Supplier<String> readOtp) {
        return link.sendAsPats(command, payload == null ? "" : payload, readOtp);
    }

    /** {@link #sendCommand(String, String, Supplier)} with the client's own OTP reader. */
    public String sendCommand(String command, String payload) {
        return sendCommand(command, payload, null);
    }

    // ======================================================================
    // View and update: PATS's VIEW_CONFIGURATION / ROUNDTRIP_CONFIGURATION
    // ======================================================================

    /**
     * {@code v<command>}: the Commander's current configuration, as the XML text it sent.
     *
     * <p>PATS's {@code ConfigurationManager.viewConfiguration} sends {@code "v" + command} and
     * compares the reply with a {@code *-View-Config-Reference.xml}, so the reply is the same
     * document a {@code u<command>} payload is: {@code vfepcfg&fep=PropFleet} answers a bare
     * {@code viper:fepConfig}. Sent as {@link #sendCommand} sends any command.
     *
     * @throws CommanderFault the reply is a CGILink fault
     * @throws DeviceProtocolError the reply is not XML
     */
    public String viewConfiguration(String command, Supplier<String> readOtp) {
        String text = link.sendAsPats("v" + command, "", readOtp);
        raiseOnFaultText("v" + command, text);
        return text;
    }

    /** {@link #viewConfiguration(String, Supplier)} with the client's own OTP reader. */
    public String viewConfiguration(String command) {
        return viewConfiguration(command, null);
    }

    /**
     * {@code u<command>} with {@code payload}, its line breaks removed as an import's are. One file
     * of {@link #importConfigurationFolder}, but a fault raises instead of being skipped: a caller
     * sending one document needs to know it did not land.
     *
     * @throws CommanderFault the reply is a CGILink fault
     */
    public String updateConfiguration(String command, String payload, Supplier<String> readOtp) {
        String joined = NEWLINES.matcher(payload).replaceAll("");
        String text = link.sendAsPats("u" + command, joined, readOtp);
        if (text.contains("Fault")) {
            raiseOnFaultText("u" + command, text);
        }
        return text;
    }

    /** {@link #updateConfiguration(String, String, Supplier)} with the client's own OTP reader. */
    public String updateConfiguration(String command, String payload) {
        return updateConfiguration(command, payload, null);
    }

    private static void raiseOnFaultText(String command, String text) {
        CgiReplies.checkFault(CgiReplies.parse(text.strip(), command));
    }

    /**
     * The funcList of a fresh {@code validate}: every command this login may send.
     *
     * <p>The catalogue is not static (it grew by five commands in two hours on one Commander) and
     * depends on the user's roles (config-surface doc section 3), so it is read when it is needed
     * and never stored as authoritative. The new session's cookie replaces any cached one. An
     * expired password leaves about 14 functions, so it raises {@link CommanderPasswordExpired}
     * rather than return a list that would silently cover almost nothing.
     */
    public List<CgiFunction> functionList() {
        Element root = link.post("validate", "", CgiLink.params("user", COMMANDER_USER, "passwd", link.password()));
        link.checkPasswordExpiry(root);
        Element cookie = CgiReplies.descendant(root, "cookie");
        if (cookie != null && !CgiReplies.text(cookie).isEmpty()) {
            link.adoptCookie(CgiReplies.text(cookie));
        }
        return CgiReplies.functionList(root);
    }

    /** {@code vfepdetails}: every FEP in the Commander's FEP table, enabled or not, by name. */
    public Map<String, FepDetail> fepDetails(Supplier<String> readOtp) {
        return CgiReplies.fepDetails(viewConfiguration("fepdetails", readOtp));
    }

    /** {@link #fepDetails(Supplier)} with the client's own OTP reader. */
    public Map<String, FepDetail> fepDetails() {
        return fepDetails(null);
    }

    // ======================================================================
    // Control functions and inits
    // ======================================================================

    /**
     * {@code cmd=<initType>}: PATS's {@code CONTROL_FUNCTION DEVICE=SITECONTROLLER}.
     *
     * <p>{@code SiteControllerService.controlFunction()} sends the corpus's {@code INIT_TYPE} as
     * the CGILink command verbatim, with no prefix ({@code cmd=cfuelinit&cookie=...}), unlike an
     * import's {@code u}. This sends once (one login retry, one OTP retry); PATS's retry loop is
     * {@link #performInitialization}.
     *
     * @throws CommanderFault the reply is a fault
     */
    public Element controlFunction(String initType, Supplier<String> readOtp) {
        String cookie = link.authenticate();
        return link.postOtpAware(initType, CgiLink.params("cookie", cookie), "", readOtp);
    }

    /** {@link #controlFunction(String, Supplier)} with the client's own OTP reader. */
    public Element controlFunction(String initType) {
        return controlFunction(initType, null);
    }

    /**
     * {@code cmd=<initCommand>}, attempt for attempt as petroapps' {@code InitilizationManager}.
     *
     * <p>{@code performInitialization} sends the command through {@code sendCommand} with no
     * payload. A reply containing {@code cgi.error} ({@link #PATS_CGI_ERROR}) is retried, up to
     * {@code request.retries} times. PATS waits {@link #PATS_REQUEST_RETRIES_SLEEP} after every
     * reply, a successful one included, and waits it again before every retry from the third
     * attempt on; the deployed {@code Automn.jar} does the same.
     *
     * @param pauseAfterReply {@code false} leaves out the 20 s after a <i>successful</i> reply, for
     *                        a caller that waits on the init's own signal instead; the pauses around
     *                        a retry are unchanged
     * @return the successful reply text
     * @throws DeviceError the reply still carried {@code cgi.error} after the last retry
     */
    public String performInitialization(String initCommand, Supplier<String> readOtp, boolean pauseAfterReply) {
        int count = 0;
        while (true) {
            String text = link.sendAsPats(initCommand, "", readOtp);
            if (!text.contains(PATS_CGI_ERROR) && !pauseAfterReply) {
                log.info("{} answered", initCommand);
                return text;
            }
            log.info("{} answered; waiting {} s (PATS's request.retries.sleep, after every response)",
                    initCommand, PATS_REQUEST_RETRIES_SLEEP.toSeconds());
            link.pauser().pause(PATS_REQUEST_RETRIES_SLEEP);
            if (!text.contains(PATS_CGI_ERROR)) {
                return text;
            }
            log.warn("{} attempt {}: {}", initCommand, count + 1, PATS_CGI_ERROR);
            if (count >= PATS_REQUEST_RETRIES) {
                throw new DeviceError(initCommand + ": " + PATS_CGI_ERROR + " after " + (count + 1) + " attempts");
            }
            if (count >= 2) {
                log.info("waiting {} s before retrying {}", PATS_REQUEST_RETRIES_SLEEP.toSeconds(), initCommand);
                link.pauser().pause(PATS_REQUEST_RETRIES_SLEEP);
            }
            count++;
        }
    }

    /** {@link #performInitialization(String, Supplier, boolean)} with the client's reader and PATS's pause. */
    public String performInitialization(String initCommand) {
        return performInitialization(initCommand, null, true);
    }

    /** {@link #refreshConfiguration(Supplier, Duration, Duration)} with PATS's 15 x 20 s and the client's reader. */
    public Element refreshConfiguration() {
        return refreshConfiguration(null, REFRESH_TIMEOUT, REFRESH_INTERVAL);
    }

    /**
     * {@code cmd=crefreshcfg}: make an imported configuration take effect.
     *
     * <p>PATS sends it after importing base configuration
     * ({@code PatsBddDevModeInitializer.performSystemInitOperations}) through
     * {@code InitilizationManager.performInitialization}: a reply carrying
     * {@code CGIPortal.InternalError} is retried, {@code request.retries=15} times
     * {@code request.retries.sleep} apart, and anything else is success. The defaults are that
     * same 15 x 20 s. Any other fault raises at once. Live from the Python, 2026-09-14 (HANDOFF.md
     * §1.32): the lab Commander answered the first attempt with an empty {@code <Response/>}, and
     * the register's item panel went from "No items to display" to 96 buttons.
     *
     * @throws Bounded.TimeoutExpired {@code InternalError} did not clear within {@code timeout}
     *                                (the Python's {@code TimeoutExpired})
     * @throws DeviceError            any other fault
     */
    public Element refreshConfiguration(Supplier<String> readOtp, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                return controlFunction(REFRESH_COMMAND, readOtp);
            } catch (DeviceError e) {
                if (!String.valueOf(e.getMessage()).contains("InternalError")) {
                    throw e;
                }
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new Bounded.TimeoutExpired(REFRESH_COMMAND + " exceeded its " + budget(timeout) + "s budget");
            }
            link.pauser().pause(Duration.ofNanos(Math.min(interval.toNanos(), left)));
        }
    }

    /** Seconds to three significant figures, as the Python's {@code f"{timeout:.3g}"}. */
    private static String budget(Duration timeout) {
        BigDecimal seconds = BigDecimal.valueOf(timeout.toNanos(), 9).round(new MathContext(3)).stripTrailingZeros();
        return seconds.scale() < 0 ? seconds.setScale(0).toPlainString() : seconds.toPlainString();
    }

    // ======================================================================
    // Commander Connect
    // ======================================================================

    /**
     * {@code cmd=ucloudconnectprofile}: chooses the Commander Connect environment.
     *
     * <p>{@code profile} is {@code CloudProfileType}'s enum name ({@code PROD}, {@code CST},
     * {@code QAT}, {@code DEV}): petroapps' {@code CloudConnectProfileContentHandler} calls
     * {@code valueOf} on it. The handler only rewrites
     * {@code /home/common/config/cloud_connect_profile.prop}; the profile is read at coresvcs
     * start, so it takes effect after a coresvcs restart.
     */
    public void setCloudConnectProfile(String profile) {
        String payload = "<domain:cloudConnectProfile xmlns:domain=\"" + DOMAIN_NS + "\">"
                + "<profile>" + profile + "</profile></domain:cloudConnectProfile>";
        link.post("ucloudconnectprofile", payload, CgiLink.params("cookie", link.authenticate()));
    }

    /**
     * {@code cmd=ccloudconnect&user=&passwd=}: starts onboarding to Commander Connect.
     *
     * <p>{@code CloudConnectHostMgr} validates the email, signs in to the cloud and starts the
     * certificate enrolment on background threads, so a clean return means <i>started</i>; poll
     * {@link #cloudConnectStatus}. Values are percent-encoded only where they would break the form
     * body ({@link #quote}). The password is the operator's Commander Connect sign-in, passed by
     * the caller; nothing here stores it.
     */
    public void cloudConnect(String email, String password) {
        Map<String, String> params = CgiLink.params(
                "cookie", link.authenticate(),
                "user", quote(email),
                "passwd", quote(password));
        link.post("ccloudconnect", "", params);
    }

    /**
     * {@code cmd=vcloudconnectstatus}: whether the Commander is registered.
     *
     * <p>Registered means the reply carries a {@code registrationTimeStamp}, which only says the
     * Commander was onboarded once; {@link #cloudConnectivity} says whether the link is live.
     */
    public CloudConnectStatus cloudConnectStatus() {
        Element root = link.post("vcloudconnectstatus", "", CgiLink.params("cookie", link.authenticate()));
        if (CgiReplies.descendant(root, "registrationTimeStamp") != null) {
            return new CloudConnectStatus(true, "registered");
        }
        Element state = CgiReplies.descendant(root, "cloudConnectStatus");
        if (state == null) {
            return new CloudConnectStatus(false, "no status in the response");
        }
        String code = state.hasAttribute("code") ? state.getAttribute("code") : "?";
        return new CloudConnectStatus(false, rstrip(code + ": " + CgiReplies.text(state).strip(), ": "));
    }

    /**
     * {@code vcloudconnectstatus}'s {@code cloudConnectivityStatus}: "Online", "Offline", or "".
     * A registered Commander can still be Offline, its MQTT broker's cloud bridge down
     * (HANDOFF.md §1.146).
     */
    public String cloudConnectivity() {
        Element root = link.post("vcloudconnectstatus", "", CgiLink.params("cookie", link.authenticate()));
        Element status = CgiReplies.descendant(root, "cloudConnectivityStatus");
        return status == null ? "" : CgiReplies.text(status).strip();
    }

    /**
     * Python's {@code urllib.parse.quote(value, safe="@.-_")}: UTF-8, every byte but ASCII
     * letters, digits, {@code _.-~} and {@code @} percent-encoded in upper-case hex.
     */
    static String quote(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~' || c == '@';
            if (safe) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static String rstrip(String text, String chars) {
        int end = text.length();
        while (end > 0 && chars.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end);
    }

    // ======================================================================
    // TLog: the transaction log comes from the Commander, not the POS shim
    // ======================================================================

    /** {@link #listTransactionNumbers(String, String)} for period 1 (Current Shift), file {@code current}. */
    public List<String> listTransactionNumbers() {
        return listTransactionNumbers("1", "current");
    }

    /**
     * Every transaction sequence number recorded for a period: {@code cmd=vtransnumlist}.
     *
     * <p>Live-probed from the Python: with no completed transactions it returns a bare
     * {@code <transNumList/>} (2026-08-07). Populated (2026-09-14, HANDOFF.md §1.34), each entry is
     * {@code <trTickNum><posNum>101</posNum><trSeq>1010007</trSeq></trTickNum>}, newest first. Only
     * the {@code trSeq} leaves are returned. This is what makes {@link #fetchTlog} need no RMI: the
     * Commander enumerates its own transaction numbers.
     *
     * @param period   {@code vtlogpdlist}'s period sysid: "1" Current Shift, "2" Current Day
     * @param filename the period file; "current" is the only value seen documented
     * @return the numbers in the Commander's order (newest first, as observed)
     */
    public List<String> listTransactionNumbers(String period, String filename) {
        String cookie = link.authenticate();
        Element root = link.post("vtransnumlist", "",
                CgiLink.params("cookie", cookie, "period", period, "filename", filename));
        List<String> numbers = new ArrayList<>();
        for (Element element : CgiReplies.all(root)) {
            // ElementTree's root.iter("trSeq"): the bare tag only, and only when it has text.
            String text = CgiReplies.text(element);
            if (CgiReplies.isBare(element, "trSeq") && !text.isEmpty()) {
                numbers.add(text.strip());
            }
        }
        return numbers;
    }

    /**
     * A Commander report: PATS's {@code TestCGIViewCmd.getReportFromSapphire}.
     *
     * <p>{@code cmd=<cmd>&period=<period>&filename=<filename>&reptname=<report>}, plus
     * {@code cashierNum}/{@code regNum} for a cashier report. The Cadence plan asks for
     * {@code vrubyrept} (period reports) and {@code vviperrept} (EPS network reports). PATS sleeps
     * 5 s before every report fetch; this does not. UNVERIFIED AGAINST HARDWARE: the request shape
     * is PATS's; no reply has been read.
     *
     * @param cashier  the cashier number, or {@code null} for a report that is not a cashier report
     * @param register the register number, sent only with a cashier; {@code null} sends it empty
     */
    public Element fetchReport(String cmd, String report, String period, String filename, String cashier,
            String register) {
        Map<String, String> params = CgiLink.params(
                "cookie", link.authenticate(),
                "period", period,
                "filename", filename,
                "reptname", report);
        if (cashier != null) {
            params.put("cashierNum", cashier);
            params.put("regNum", register == null ? "" : register);
        }
        return link.post(cmd, "", params);
    }

    /** {@link #fetchReport(String, String, String, String, String, String)} for period 1, file current, no cashier. */
    public Element fetchReport(String cmd, String report) {
        return fetchReport(cmd, report, "1", "current", null, null);
    }

    /**
     * One transaction's full detail, by sequence number: {@code cmd=vtransdetails&trseq=<n>}.
     *
     * <p>Live-probed from the Python, 2026-08-07: the parameter is {@code trseq} (an earlier
     * {@code transNum} faulted {@code "no value called trseq found"}). Live 2026-09-14: the reply
     * is {@code <transList><trans type="sale">...</trans></transList>}, and PATS's
     * {@code -Tlog.xml} baselines are rooted at {@code <trans>}, so the {@code <trans>} is returned.
     * This supersedes PATS's {@code vtransset} walk ({@link CgiReplies#selectTransactionByTrseq}).
     *
     * @throws DeviceProtocolError a {@code <transList>} with no {@code <trans>}
     * @throws CommanderFault      a fault, "not found" included
     */
    public Element fetchTlogByTrseq(String trseq) {
        String cookie = link.authenticate();
        Element root = link.post("vtransdetails", "", CgiLink.params("cookie", cookie, "trseq", trseq));
        if (CgiReplies.isBare(root, "transList")) {
            for (Element child : CgiReplies.all(root)) {
                if (child.getParentNode() == root && CgiReplies.isBare(child, "trans")) {
                    return child;
                }
            }
            throw new DeviceProtocolError("vtransdetails trseq=" + trseq, "<transList> has no <trans>");
        }
        return root;
    }

    /**
     * The transaction log for one sale: the Python's {@code assert_tlog} source.
     *
     * @param trseq the sequence number, or {@code null} for the newest: the first that
     *              {@link #listTransactionNumbers()} lists (PATS's {@code TransactionCommitNumber},
     *              the last sale committed). Not the highest: {@code trSeq} counts up per register,
     *              so after a DCR sale (register 901) the highest was an older 904 prepay
     *              completion (PETROCORE-118399, Reg101, 2026-09-23).
     * @throws DeviceError {@code trseq} is {@code null} and no transaction has completed this shift
     */
    public Element fetchTlog(String trseq) {
        String wanted = trseq;
        if (wanted == null) {
            List<String> numbers = listTransactionNumbers();
            if (numbers.isEmpty()) {
                throw new DeviceError("vtransnumlist returned no transaction numbers for period 1 (Current Shift) — "
                        + "no sale has completed yet this shift, so there is nothing for fetch_tlog() to select "
                        + "without an explicit trseq.");
            }
            wanted = numbers.get(0);
        }
        return fetchTlogByTrseq(wanted);
    }

    /** {@link #fetchTlog(String)} for the newest transaction. */
    public Element fetchTlog() {
        return fetchTlog(null);
    }

    /**
     * The transaction-set report, {@code cmd=vtransset&period=1&filename=current&reptname=<name>}:
     * PATS's TLog source, kept beside {@link CgiReplies#selectTransactionByTrseq} as the fallback.
     */
    Element fetchVtranssetReport(String reptname) {
        String cookie = link.authenticate();
        return link.post("vtransset", "",
                CgiLink.params("cookie", cookie, "period", "1", "filename", "current", "reptname", reptname));
    }

    // ======================================================================
    // Device upgrade control: the AU/Classic path
    // ======================================================================
    //
    // UNCONFIRMED SHAPE, unlike vtransdetails above: the three command names are read out of
    // Overarch's working 02-update.bat, which runs them in production, but no captured request
    // and reply pair was available, so the parameters beyond `cookie` and the reply's element
    // names are not established. Confirm against a live Commander before trusting these.

    /**
     * {@code cmd=vupgradesummary}: the AU path's status and token surface. Read once before
     * {@code cbeginupgrade} for the token ({@link CgiReplies#upgradeToken}) and polled after it
     * ({@link CgiReplies#isUpgradeComplete}). OTP-aware, since whether it needs one is untested.
     */
    public Element upgradeSummary(Supplier<String> readOtp) {
        String cookie = link.authenticate();
        return link.postOtpAware("vupgradesummary", CgiLink.params("cookie", cookie), "", readOtp);
    }

    /**
     * {@code cmd=cclosepdnow}: closes the current period. It closes the day, so it is never a
     * probe: its callers are an upgrade the caller already decided on, and the end of every run
     * ({@code petrotest close-day}, the rig owner's rule of 2026-09-15).
     */
    public Element closeDay(Supplier<String> readOtp) {
        String cookie = link.authenticate();
        return link.postOtpAware("cclosepdnow", CgiLink.params("cookie", cookie), "", readOtp);
    }

    /**
     * {@code cmd=cbeginupgrade}: starts the AU upgrade {@code Auto_Upgrade.jar} staged.
     *
     * @param token the value {@link CgiReplies#upgradeToken} read from a prior
     *              {@link #upgradeSummary}, sent as {@code token}. <b>The parameter name is
     *              unconfirmed</b>; if the Commander expects another, this faults and the fault
     *              names the problem rather than silently doing nothing.
     */
    public Element beginUpgrade(String token, Supplier<String> readOtp) {
        String cookie = link.authenticate();
        return link.postOtpAware("cbeginupgrade", CgiLink.params("cookie", cookie, "token", token), "", readOtp);
    }
}
