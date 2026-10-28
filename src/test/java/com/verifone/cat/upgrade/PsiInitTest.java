package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@link PsiInit} over a recording CGILink and a fake pairing screen: the Python's
 * {@code tests/unit/test_upgrade_psi_init.py}, plus the lockout ordering. No test reaches a device.
 */
@Timeout(30)
class PsiInitTest {

    @TempDir
    Path dir;

    private final List<String> sent = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final Say.Recorded say = new Say.Recorded();

    private Rig rig() throws URISyntaxException {
        return RigLoader.loadFile(Path.of(getClass().getResource("/upgrade/rigs/psi.yaml").toURI())).rig();
    }

    /** A CGILink that records "command password(mayBeExpired)" and answers from {@code reply}. */
    private PsiInit.CgiFactory cgi(java.util.function.BiFunction<String, String, String> reply) {
        return (host, password, mayBeExpired) -> (command, payload) -> {
            sent.add(command + " " + (password == null ? "lab" : password) + (mayBeExpired ? " expired-ok" : ""));
            return reply.apply(command, payload);
        };
    }

    private PsiInit psiInit(PsiInit.CgiFactory cgi, PsiInit.Registers registers) {
        return new PsiInit(cgi, registers, dir.resolve("psi_message.txt"), pauses::add, say);
    }

    private static final PsiInit.Registers NO_REGISTERS = new PsiInit.Registers() {
        @Override
        public PsiInit.PairingScreen open(Rig rig, Rig.Register register) {
            throw new AssertionError("no register should be opened");
        }

        @Override
        public boolean atLoginScreen(Rig rig, Rig.Register register) {
            throw new AssertionError("no register should be looked at");
        }
    };

    // ------------------------------------------------------------ read_psi_password

    @Test
    void readsThePasswordThroughMarkup() throws IOException {
        Path file = dir.resolve("psi_message.txt");
        Files.writeString(file, "<html><b>User</b>: manager<br/><b>Password</b> : Xk93Lm2<br/></html>");
        assertEquals("Xk93Lm2", PsiInit.readPsiPassword(file));
    }

    @Test
    void noPasswordLineIsAnError() throws IOException {
        Path file = dir.resolve("psi_message.txt");
        Files.writeString(file, "Installation Complete");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PsiInit.readPsiPassword(file));
        assertTrue(e.getMessage().contains("Password"));
    }

    // ------------------------------------------------------------ the reset

    @Test
    void aPasswordChangeIsRecordedEvenWhenTheChallengesFail() throws Exception {
        Path message = dir.resolve("psi_message.txt");
        Files.writeString(message, "Password : Xk93Lm2");
        boolean[] challengeFails = {true};
        PsiInit.CgiFactory cgi = cgi((command, payload) -> {
            if (command.equals("uchallengequestions") && challengeFails[0]) {
                throw new DeviceError("challenge refused");
            }
            return "<ok/>";
        });

        assertFalse(psiInit(cgi, NO_REGISTERS).run(rig(), false));
        // The PSI password, once, allowed to be expired; then the lab password for the challenges.
        assertEquals(List.of("changepasswd Xk93Lm2 expired-ok", "uchallengequestions lab"), sent);
        assertFalse(Files.exists(message), "the spent PSI password must not be read again");
        assertTrue(Files.isRegularFile(dir.resolve("psi_message.changed")));
        assertEquals("psi-init failed: challenge refused", say.lastErr());

        sent.clear();
        challengeFails[0] = false;
        assertTrue(psiInit(cgi, NO_REGISTERS).run(rig(), false));
        assertEquals(List.of("uchallengequestions lab"), sent);
        assertTrue(Files.isRegularFile(dir.resolve("psi_message.used")));
        assertFalse(Files.exists(dir.resolve("psi_message.changed")));
    }

    @Test
    void theResetSendsThePasswordChangeThenTheLabChallengesAndRetiresTheFile() throws Exception {
        Files.writeString(dir.resolve("psi_message.txt"), "Password : Ab1");
        List<String> payloads = new ArrayList<>();
        PsiInit.CgiFactory cgi = cgi((command, payload) -> {
            payloads.add(payload);
            return "<ok/>";
        });

        assertTrue(psiInit(cgi, NO_REGISTERS).run(rig(), false));
        assertEquals(List.of("changepasswd Ab1 expired-ok", "uchallengequestions lab"), sent);
        assertTrue(payloads.get(0).contains("<user name=\"manager\">"), payloads.get(0));
        assertTrue(payloads.get(0).contains("oldValue=\"Ab1\" newValue=\"" + PdkShim.COMMANDER_PASSWORD + "\""),
                payloads.get(0));
        assertEquals(LabAnswers.CHALLENGE_ANSWERS_XML, payloads.get(1));
        assertEquals(List.of(PsiInit.PATS_SLEEP_5), pauses);
        assertTrue(Files.isRegularFile(dir.resolve("psi_message.used")));
        assertTrue(say.outText().contains("Commander password reset; challenge questions set"));
    }

    @Test
    void noMessageFileMeansNoResetAndNoLogin() throws Exception {
        assertTrue(psiInit(cgi((c, p) -> "<ok/>"), NO_REGISTERS).run(rig(), false));
        assertTrue(sent.isEmpty(), sent.toString());
        assertTrue(say.outText().contains("absent: password not reset"));
    }

    @Test
    void aFaultingCommandIsRetriedThenGivenUp() throws Exception {
        Files.writeString(dir.resolve("psi_message.txt"), "Password : Ab1");
        PsiInit.CgiFactory cgi = cgi((command, payload) -> "<VFI:Fault>busy</VFI:Fault>");

        assertFalse(psiInit(cgi, NO_REGISTERS).run(rig(), false));
        assertEquals(PsiInit.MAX_RETRY + 1, sent.size());
        assertTrue(say.lastErr().contains("changepasswd still faulting after 6 attempts"), say.lastErr());
        // changepasswd never succeeded, so the PSI password is not spent.
        assertTrue(Files.isRegularFile(dir.resolve("psi_message.txt")));
    }

    @Test
    void aFaultThatClearsIsRetriedUntilItDoes() throws Exception {
        Files.writeString(dir.resolve("psi_message.txt"), "Password : Ab1");
        int[] faults = {2};
        PsiInit.CgiFactory cgi = cgi((command, payload) ->
                command.equals("changepasswd") && faults[0]-- > 0 ? "<VFI:Fault>busy</VFI:Fault>" : "<ok/>");

        assertTrue(psiInit(cgi, NO_REGISTERS).run(rig(), false));
        assertEquals(4, sent.size(), sent.toString());
    }

    // ------------------------------------------------------------ pairing

    /** The pairing pages as Reg101 showed them on build 1097: the first taps may be refused. */
    private static final class Register implements PsiInit.PairingScreen {
        String page;
        int refusals;
        String typed = "";
        boolean closed;

        Register(String page, int refusals) {
            this.page = page;
            this.refusals = refusals;
        }

        @Override
        public boolean isShowing() {
            return page.equals("begin") || isRefused();
        }

        @Override
        public boolean isRefused() {
            return page.equals("refused");
        }

        @Override
        public void backToStart(Duration timeout) {
            assertEquals("refused", page);
            page = "begin";
        }

        @Override
        public void start(Duration timeout) {
            assertEquals("begin", page);
            page = refusals > 0 ? "refused" : "keypad";
            refusals = Math.max(0, refusals - 1);
        }

        @Override
        public boolean awaitingCode() {
            return page.equals("keypad");
        }

        @Override
        public void enterCode(String code, Duration timeout) {
            assertEquals("keypad", page);
            typed = code;
            page = "paired";
        }

        @Override
        public void confirm(Duration timeout) {
            assertEquals("paired", page);
            page = "done";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private PsiInit.Registers registers(Register register) {
        return new PsiInit.Registers() {
            @Override
            public PsiInit.PairingScreen open(Rig rig, Rig.Register r) {
                return register;
            }

            @Override
            public boolean atLoginScreen(Rig rig, Rig.Register r) {
                return true;
            }
        };
    }

    private List<String> pair(Register register) throws Exception {
        PsiInit.CgiFactory cgi = cgi((command, payload) -> switch (command) {
            case "cpairingsvc" -> "<r>true</r>";
            case "vpairingotp" -> "<r>42</r>";
            default -> throw new AssertionError(command);
        });
        assertTrue(psiInit(cgi, registers(register)).run(rig(), true), say.errText());
        return sent.stream().map(line -> line.split(" ")[0]).toList();
    }

    @Test
    void aRefusedPairingGoesBackAndTriesAgain() throws Exception {
        Register register = new Register("refused", 1);
        assertEquals(List.of("cpairingsvc", "cpairingsvc", "vpairingotp"), pair(register));
        assertEquals("done", register.page);
        assertEquals("42", register.typed);
        assertTrue(register.closed);
        assertTrue(pauses.contains(PsiInit.PAIR_RETRY_SECONDS), pauses.toString());
        assertTrue(say.outText().contains("topaz101: back at its login screen"));
    }

    @Test
    void theRefusedPageIsNotTakenAsAlreadyPaired() throws Exception {
        Register register = new Register("refused", 0);
        assertTrue(pair(register).contains("vpairingotp"));
        assertEquals("done", register.page);
    }

    @Test
    void aRegisterWithNoPairingScreenIsTakenAsPaired() throws Exception {
        Register register = new Register("idle", 0);
        assertEquals(List.of(), pair(register));
        assertTrue(say.outText().contains("topaz101: no pairing screen; taken as already paired"));
    }

    @Test
    void refusedEveryTimeFailsPsiInitNamingTheRegister() throws Exception {
        Register register = new Register("begin", PsiInit.PAIR_ATTEMPTS);
        PsiInit.CgiFactory cgi = cgi((command, payload) -> "<r>true</r>");

        assertFalse(psiInit(cgi, registers(register)).run(rig(), true));
        assertEquals("psi-init failed: topaz101: pairing refused 5 times", say.lastErr());
    }

    // ------------------------------------------------------------ helpers

    @Test
    void rootTextIsTheTextBeforeTheFirstChild() {
        assertEquals("true", PsiInit.rootText("<r> true </r>"));
        assertEquals("", PsiInit.rootText("<r/>"));
        assertThrows(DeviceError.class, () -> PsiInit.rootText("not xml"));
    }

    @Test
    void quoteAttrIsPythonsQuoteattr() {
        assertEquals("\"a&amp;b&lt;\"", PsiInit.quoteAttr("a&b<"));
        assertEquals("'say \"hi\"'", PsiInit.quoteAttr("say \"hi\""));
        assertEquals("\"both &quot;'\"", PsiInit.quoteAttr("both \"'"));
    }

    @Test
    void theMessageFileIsTheInstallersInTheHome() {
        assertEquals(Path.of(System.getProperty("user.home"), "psi_message.txt"), PsiInit.defaultPsiMessageFile());
    }
}
