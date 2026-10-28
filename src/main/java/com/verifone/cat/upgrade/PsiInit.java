package com.verifone.cat.upgrade;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.RegisterEndpoint;
import com.verifone.cat.pos.pages.PairingPage;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * After a PSI install: PATS's {@code DefaultNewInstallInitializer.performNewInstallTask}, first half. A port of
 * {@code petrotest.upgrade.psi_init}.
 *
 * <ol>
 *   <li>{@code CommanderPWDResetUtil.resetCommanderPassword}: log in as {@code manager} with the password the
 *       installer generated ({@code ~/psi_message.txt}, {@code Password : <x>}), send {@code changepasswd} to set
 *       it to {@link PdkShim#COMMANDER_PASSWORD}, then {@code uchallengequestions};</li>
 *   <li>{@code RegisterPairingUtil.pairPOSDevices}: per register, {@code cpairingsvc} until it answers
 *       {@code true}, tap <i>Pair device</i> on the POS, {@code vpairingotp}, type that code on the POS keypad, OK,
 *       wait for "Successfully Paired", OK; then wait for the register to come back to its login screen.</li>
 * </ol>
 *
 * <p>PATS's retries are kept: each CGI command is retried up to {@link #MAX_RETRY} more times,
 * {@link #PATS_SLEEP_5} apart, while it faults. PATS's fixed pauses after pairing (15 s, then 90 + 60 s) become a
 * bounded poll for the POS's login screen ({@link #REGISTER_BACK_TIMEOUT}). Pairing drives the register's browser
 * over CDP, so {@code provision} must have pushed the Chromium override first.
 *
 * <p><b>The lockout.</b> Nothing may log in as {@code manager} with the lab password before the reset: each such
 * login is a failed attempt against the installer's password, and enough of them soft-lock the account (2026-09-21).
 * That is why step 4 runs this with {@code pair=false} the moment the install returns, and why
 * {@code psi_message.txt} is renamed {@code psi_message.changed} as soon as {@code changepasswd} succeeds (the PSI
 * password is spent, whatever the challenge questions then do) and {@code psi_message.used} once both are done.
 * Those renames are in the user's home, where the installer wrote the file and where the Python CAT renames it too,
 * so both ports agree on which password is spent: the one write CAT-Java makes outside {@code ~/.cat/java/}
 * (CLAUDE.md rule 6), and it is to the installer's file, never a Python CAT file.
 *
 * <p>The challenge answers are {@link LabAnswers#CHALLENGE_ANSWERS_XML}, PATS's {@code challengequestion.xml}: one
 * of the Python CAT's two documented lab carve-outs (CAT-Java CLAUDE.md hard rule 7; the Python CAT's rule 6, rig
 * owner 2026-09-18). The new password is the CGILink carve-out, {@link PdkShim#COMMANDER_PASSWORD}.
 *
 * <p>Deviations from the Python: a {@code cpairingsvc}/{@code vpairingotp} reply that is not XML is a
 * {@link DeviceError} (a failed psi-init) where the Python raised an uncaught {@code ParseError}; the pauses are a
 * bounded park, never {@code Thread.sleep}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no PSI install has been reset or paired from CAT-Java.
 */
public final class PsiInit {

    /** The installer's security-info file's name, in the user's home. */
    public static final String PSI_MESSAGE_FILE_NAME = LabAnswers.SECURITY_DATA_FILE;
    /** Times a faulting CGI command is retried, PATS's {@code MAX_RETRY}. */
    public static final int MAX_RETRY = 5;
    public static final Duration PATS_SLEEP_2 = Duration.ofSeconds(2);
    public static final Duration PATS_SLEEP_5 = Duration.ofSeconds(5);
    /** How long a register may take to come back to its login screen after pairing; PATS waits 15 + 90 + 60 s flat. */
    public static final Duration REGISTER_BACK_TIMEOUT = Duration.ofSeconds(600);
    /** How often the login screen is looked for while the register comes back. */
    public static final Duration REGISTER_BACK_INTERVAL = Duration.ofSeconds(5);
    /** Taps on "Pair device" before giving up, {@link #PAIR_RETRY_SECONDS} apart, when the POS shows the refused page. */
    public static final int PAIR_ATTEMPTS = 5;
    public static final Duration PAIR_RETRY_SECONDS = Duration.ofSeconds(30);
    /** How long to look for the refused page or the keypad after a tap. */
    public static final Duration REFUSED_CHECK_SECONDS = Duration.ofSeconds(10);
    static final Duration REFUSED_CHECK_INTERVAL = Duration.ofSeconds(1);
    /** The budget of one pairing-service poll: every retry of a faulting command, plus a minute. */
    static final Duration PAIRING_POLL_TIMEOUT = PATS_SLEEP_5.multipliedBy(MAX_RETRY + 1).plusSeconds(60);
    /** The pairing screen's tap budgets, the Python's {@code timeout=30} and {@code timeout=60}. */
    static final Duration TAP_TIMEOUT = Duration.ofSeconds(30);
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(60);
    static final String FAULT = "<VFI:Fault>";

    private static final Pattern MARKUP = Pattern.compile("<[^>]+>");
    private static final Pattern PASSWORD =
            Pattern.compile("Password\\s*:\\s*(\\w+)", Pattern.UNICODE_CHARACTER_CLASS);

    /** One CGILink session's sender: the Python's {@code PDKShim.send_command}. */
    @FunctionalInterface
    public interface Cgi {
        String send(String command, String payload);
    }

    /** Opens a CGILink sender; {@code password} null is the lab password. */
    @FunctionalInterface
    public interface CgiFactory {
        Cgi open(String host, String password, boolean passwordMayBeExpired);
    }

    /** One register's pairing pages: the Python's {@code PairingScreen} over a connected driver. */
    public interface PairingScreen extends AutoCloseable {
        boolean isShowing();

        boolean isRefused();

        void backToStart(Duration timeout);

        void start(Duration timeout);

        boolean awaitingCode();

        void enterCode(String code, Duration timeout);

        void confirm(Duration timeout);

        @Override
        void close();
    }

    /** The registers' browsers: a pairing screen on one, and whether one is back at its login screen. */
    public interface Registers {
        PairingScreen open(Rig rig, Rig.Register register);

        boolean atLoginScreen(Rig rig, Rig.Register register);
    }

    private final CgiFactory cgi;
    private final Registers registers;
    private final Path psiMessageFile;
    private final Consumer<Duration> pause;
    private final Say say;

    /**
     * @param psiMessageFile the installer's {@code psi_message.txt}; its {@code .changed} and {@code .used} siblings
     *                       are the renames
     * @param pause          a bounded pause between attempts; production parks, a test records
     */
    public PsiInit(CgiFactory cgi, Registers registers, Path psiMessageFile, Consumer<Duration> pause, Say say) {
        this.cgi = cgi;
        this.registers = registers;
        this.psiMessageFile = psiMessageFile;
        this.pause = pause;
        this.say = say;
    }

    /** The real thing for {@code rig}: CGILink with the Commander's OTP over SSH, the registers over CDP. */
    public static PsiInit forRig(Rig rig, Map<String, String> environ, Say say) {
        Supplier<String> readOtp = new CommanderAccess(rig, JschTransport::new, environ).otpReader();
        CgiFactory cgi = (host, password, mayBeExpired) -> {
            PdkShim shim = password == null
                    ? new PdkShim(host, PdkShim.COMMANDER_READ_TIMEOUT, readOtp)
                    : new PdkShim(host, PdkShim.COMMANDER_READ_TIMEOUT, readOtp, password, mayBeExpired);
            return shim::sendCommand;
        };
        return new PsiInit(cgi, CDP_REGISTERS, defaultPsiMessageFile(), Bounded::pause, say);
    }

    /** {@code ~/psi_message.txt}. */
    public static Path defaultPsiMessageFile() {
        return Path.of(System.getProperty("user.home")).resolve(PSI_MESSAGE_FILE_NAME);
    }

    /** {@code Password : <x>} from the installer's security-info file, as PATS reads it (markup ignored). */
    public static String readPsiPassword(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
        String text = MARKUP.matcher(new String(bytes, StandardCharsets.UTF_8)).replaceAll(" ");
        Matcher match = PASSWORD.matcher(text);
        if (!match.find()) {
            throw new IllegalArgumentException(path + " has no 'Password : ...' line");
        }
        return match.group(1);
    }

    /**
     * Resets the Commander's password if the installer changed it, then pairs every register.
     *
     * <p>{@code pair=false} is the reset alone: step 4 runs that the moment the install returns, before
     * {@code provision}, because Petro Simulator Suite logs in every 3 minutes with the lab password and six failures
     * soft-locked the account (2026-09-21). Pairing needs {@code provision}'s Chromium first.
     *
     * @return true when it all succeeded; a failure is written to {@code say}'s error stream
     */
    public boolean run(Rig rig, boolean pair) {
        String host = rig.commander().host();
        Path changed = sibling(".changed");
        try {
            if (Files.isRegularFile(psiMessageFile)) {
                resetCommanderPassword(host, readPsiPassword(psiMessageFile), LabAnswers.CHALLENGE_ANSWERS_XML,
                        () -> move(psiMessageFile, changed));
                move(changed, sibling(".used"));
                say.out("Commander password reset; challenge questions set");
            } else if (Files.isRegularFile(changed)) {
                // changepasswd done, challenges not yet: the PSI password is spent, but the challenges are owed.
                setChallengeQuestions(host, LabAnswers.CHALLENGE_ANSWERS_XML);
                move(changed, sibling(".used"));
                say.out("Commander password already reset; challenge questions set");
            } else {
                say.out(psiMessageFile + " absent: password not reset");
            }
            if (!pair) {
                return true;
            }
            Cgi client = cgi.open(host, null, false);
            for (Rig.Register register : rig.registers()) {
                pairRegister(rig, register, client);
            }
        } catch (DeviceError | UncheckedIOException | IllegalArgumentException | Bounded.TimedOut e) {
            say.err("psi-init failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * {@code changepasswd} from the PSI password to the lab password, then the challenges. {@code onPasswordChanged}
     * runs as soon as {@code changepasswd} has succeeded, before the challenges are tried.
     */
    void resetCommanderPassword(String host, String psiPassword, String challengeXml, Runnable onPasswordChanged) {
        String payload = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<domain:passwdConfig xmlns:domain=\"urn:vfi-sapphire:np.domain.2001-07-01\" "
                + "xmlns:vs=\"urn:vfi-sapphire:vs.2001-10-01\">\n"
                + "\t<user name=" + quoteAttr(PdkShim.COMMANDER_USER) + ">\n"
                + "\t\t<passwd oldValue=" + quoteAttr(psiPassword) + " newValue="
                + quoteAttr(PdkShim.COMMANDER_PASSWORD) + " />\n"
                + "\t</user>\n</domain:passwdConfig>\n";
        // Changing an expired password is the point, so this one session may log in with it.
        Cgi client = cgi.open(host, psiPassword, true);
        say.out(head(sendUntilNoFault(client, "changepasswd", payload)));
        onPasswordChanged.run();
        pause.accept(PATS_SLEEP_5);
        setChallengeQuestions(host, challengeXml);
    }

    /** {@code uchallengequestions}, logged in with the lab password. */
    void setChallengeQuestions(String host, String challengeXml) {
        say.out(head(sendUntilNoFault(cgi.open(host, null, false), "uchallengequestions", challengeXml)));
    }

    /** PATS's {@code do { ... } while (response contains <VFI:Fault>)}, capped at {@link #MAX_RETRY} + 1 tries. */
    String sendUntilNoFault(Cgi client, String command, String payload) {
        String text = "";
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            text = client.send(command, payload);
            if (!text.contains(FAULT)) {
                return text;
            }
            pause.accept(PATS_SLEEP_5);
        }
        throw new DeviceError(command + " still faulting after " + (MAX_RETRY + 1) + " attempts: " + head(text));
    }

    /** {@code RegisterPairingUtil.PairPOSTask.pairPOS} for one register. */
    void pairRegister(Rig rig, Rig.Register register, Cgi client) {
        try (PairingScreen screen = registers.open(rig, register)) {
            if (!screen.isShowing()) {
                say.out(register.id() + ": no pairing screen; taken as already paired");
                return;
            }
            boolean accepted = false;
            for (int attempt = 1; attempt <= PAIR_ATTEMPTS; attempt++) {
                boolean started = Bounded.pollUntil(
                        () -> "true".equals(rootText(client.send("cpairingsvc", ""))) ? Optional.of(Boolean.TRUE)
                                : Optional.empty(),
                        PAIRING_POLL_TIMEOUT, PATS_SLEEP_5, "cpairingsvc answering true");
                say.out("pairing service started: " + started);
                pause.accept(PATS_SLEEP_2);
                if (screen.isRefused()) {
                    screen.backToStart(TAP_TIMEOUT);
                }
                screen.start(TAP_TIMEOUT);
                // A 1097 Commander answered cpairingsvc true and still refused the tap (2026-09-29): then
                // vpairingotp never has a code. Wait for either page, and try again.
                try {
                    Bounded.pollUntil(() -> screen.isRefused() || screen.awaitingCode() ? Optional.of(Boolean.TRUE)
                            : Optional.empty(), REFUSED_CHECK_SECONDS, REFUSED_CHECK_INTERVAL,
                            "the pairing keypad or the refused page");
                } catch (Bounded.TimedOut e) {
                    // Neither: ask for the code, as before.
                }
                if (!screen.isRefused()) {
                    accepted = true;
                    break;
                }
                say.out(register.id() + ": pairing refused (attempt " + attempt + "); retrying");
                pause.accept(PAIR_RETRY_SECONDS);
            }
            if (!accepted) {
                throw new DeviceError(register.id() + ": pairing refused " + PAIR_ATTEMPTS + " times");
            }
            String code = Bounded.pollUntil(() -> {
                String text = rootText(client.send("vpairingotp", ""));
                return text.isEmpty() ? Optional.empty() : Optional.of(text);
            }, PAIRING_POLL_TIMEOUT, PATS_SLEEP_5, "a pairing code from vpairingotp");
            say.out(register.id() + ": entering pairing code");
            screen.enterCode(code, TAP_TIMEOUT);
            screen.confirm(CONFIRM_TIMEOUT);
            say.out(register.id() + ": Successfully Paired");
        }
        // The register reboots itself after pairing, which closes the browser this was reading: each look
        // reconnects, and a connection refused or closed under it is "not yet".
        Bounded.pollUntil(() -> registers.atLoginScreen(rig, register) ? Optional.of(Boolean.TRUE) : Optional.empty(),
                REGISTER_BACK_TIMEOUT, REGISTER_BACK_INTERVAL, register.id() + "'s login screen after pairing");
        say.out(register.id() + ": back at its login screen");
    }

    /** The registers over CDP: the {@link PairingPage} on a connected {@link PosDriver}. */
    static final Registers CDP_REGISTERS = new Registers() {
        @Override
        public PairingScreen open(Rig rig, Rig.Register register) {
            PosDriver driver = PosDriver.connect(RegisterEndpoint.of(register), Timeouts.of(rig.timeouts()),
                    rig.build() == null ? null : rig.build().expectedVersion(), null, null, false);
            PairingPage page = new PairingPage(driver);
            return new PairingScreen() {
                @Override
                public boolean isShowing() {
                    return page.isShowing();
                }

                @Override
                public boolean isRefused() {
                    return page.isRefused();
                }

                @Override
                public void backToStart(Duration timeout) {
                    page.backToStart(timeout);
                }

                @Override
                public void start(Duration timeout) {
                    page.start(timeout);
                }

                @Override
                public boolean awaitingCode() {
                    return page.awaitingCode();
                }

                @Override
                public void enterCode(String code, Duration timeout) {
                    page.enterCode(code, timeout);
                }

                @Override
                public void confirm(Duration timeout) {
                    page.confirm(timeout);
                }

                @Override
                public void close() {
                    driver.close();
                }
            };
        }

        @Override
        public boolean atLoginScreen(Rig rig, Rig.Register register) {
            try (PosDriver driver = PosDriver.connect(RegisterEndpoint.of(register), Timeouts.of(rig.timeouts()),
                    rig.build() == null ? null : rig.build().expectedVersion(), null, null, false)) {
                return driver.isPresent(Locators.IDLE_SCREEN_VIEW_IDLE_MSG)
                        || driver.isPresent(Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT);
            } catch (PlaywrightException | UncheckedIOException e) {
                // Playwright's connect or target-closed errors: the register is still coming back.
                return false;
            }
        }
    };

    /** The root element's text, stripped: the Python's {@code _root_text}. */
    static String rootText(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // no "[Fatal Error]" on stderr
            var root = builder
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            StringBuilder text = new StringBuilder();
            for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    break; // ElementTree's .text is only what precedes the first child element
                }
                if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                        || node.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                    text.append(node.getNodeValue());
                }
            }
            return text.toString().strip();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new DeviceError("the Commander's reply is not XML: " + head(xml), e);
        }
    }

    /** Python's {@code xml.sax.saxutils.quoteattr}: escaped, and quoted with whichever quote it does not hold. */
    static String quoteAttr(String value) {
        String escaped = value.replace("&", "&amp;").replace(">", "&gt;").replace("<", "&lt;")
                .replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;");
        if (escaped.contains("\"")) {
            if (escaped.contains("'")) {
                return "\"" + escaped.replace("\"", "&quot;") + "\"";
            }
            return "'" + escaped + "'";
        }
        return "\"" + escaped + "\"";
    }

    private Path sibling(String suffix) {
        String name = psiMessageFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return psiMessageFile.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + suffix);
    }

    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not rename " + from + " to " + to, e);
        }
    }

    private static String head(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300);
    }
}
