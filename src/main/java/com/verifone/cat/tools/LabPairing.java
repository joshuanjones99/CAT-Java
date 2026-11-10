package com.verifone.cat.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Say;
import com.verifone.cat.core.Waits;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.inspect.CdpPage;
import com.verifone.cat.pos.inspect.InspectError;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.upgrade.PsiInit;

/**
 * POS Pairing with no rig file (user, 2026-11-10: "Pair it from CAT without needing a rig file"): the lab's registers
 * ({@link LabRegisters}) paired with the lab's Commander ({@link LabRegisters#COMMANDER_HOST}), as step 4's
 * {@code psi-init} pairs a rig's, then the pos-shim pushed to each again, since pairing reboots a register. No Python
 * equivalent: the Python pairs only a rig.
 *
 * <ol>
 *   <li>Each lab register's DevTools is looked for (its own port, the browser override's relay, the stock browser's:
 *       {@link RegisterTarget#cdpPorts}); a register with none is said and left out.</li>
 *   <li>{@link PsiInit#run(String, Rig, List, boolean, BooleanSupplier)}: the Commander password reset only when the
 *       installer left its file <i>and</i> one lab-password login fails ({@link PdkShim#login}); then each register's
 *       pairing screen driven over raw CDP ({@link CdpPage}, the IDs and XPaths {@code PairingPage} uses, since
 *       Playwright cannot drive a stock register browser), the code from {@code cpairingsvc}/{@code vpairingotp}
 *       read over SSH as the run does ({@code cat /var/logins/tat}).</li>
 *   <li>{@link ShimPush} to each register that was looked at, so its shim is back after the reboot.</li>
 * </ol>
 *
 * <p>SSH to the Commander is {@code root} with {@link ShimPush#keyFile}, the key the shim push used on Reg101.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): {@code LabPairingTest} over fakes only. The raw-CDP queries were checked
 * read-only on Reg101's pairing page; no tap, code or Commander call has been made from here yet.
 */
public final class LabPairing {

    /** How long finding a register's DevTools, and each raw-CDP call, may take. */
    static final Duration CDP_TIMEOUT = Duration.ofSeconds(8);

    /** What the lab pairing reaches: production is {@link #real}; tests pass fakes. */
    public interface Seams {
        /** The first DevTools endpoint of {@code register}, or empty. */
        Optional<String> devTools(RegisterTarget register);

        /** The pairing, on these registers, each with its DevTools endpoint. */
        boolean pair(Map<RegisterTarget, String> registers, Say say);

        /** The shim push after pairing. */
        boolean pushShim(RegisterTarget register, Say say);
    }

    private LabPairing() {}

    /**
     * Pairs the lab registers that answer. Says each step; a failure is its last error line.
     *
     * @return whether every register that answered is paired (or was already) and has its shim back; false when none
     *         answered
     */
    public static boolean pair(List<RegisterTarget> registers, Seams seams, Say say) {
        Map<RegisterTarget, String> reachable = new LinkedHashMap<>();
        for (RegisterTarget register : registers) {
            Optional<String> devTools = seams.devTools(register);
            if (devTools.isEmpty()) {
                say.out(register.describe() + ": no DevTools endpoint answering on " + register.cdpPorts()
                        + "; not paired");
            } else {
                say.out(register.describe() + ": DevTools at " + devTools.get());
                reachable.put(register, devTools.get());
            }
        }
        if (reachable.isEmpty()) {
            say.err("no lab register answers DevTools, so none can be paired");
            return false;
        }
        if (!seams.pair(reachable, say)) {
            return false;
        }
        boolean ok = true;
        for (RegisterTarget register : reachable.keySet()) {
            say.out("push the pos-shim to " + register.describe() + " again: pairing reboots a register");
            ok &= seams.pushShim(register, say);
        }
        return ok;
    }

    /** The real seams: DevTools over HTTP, CGILink and SSH to the lab Commander, the shim push. */
    public static Seams real(Map<String, String> env) {
        return new Seams() {
            @Override
            public Optional<String> devTools(RegisterTarget register) {
                return CdpPage.devToolsAt(register.host(), register.cdpPorts(), CDP_TIMEOUT);
            }

            @Override
            public boolean pair(Map<RegisterTarget, String> registers, Say say) {
                return realPair(LabRegisters.COMMANDER_HOST, registers, env, say);
            }

            @Override
            public boolean pushShim(RegisterTarget register, Say say) {
                return ShimPush.push(register, env, ShimPush.REAL, say);
            }
        };
    }

    /** {@link PsiInit} on the lab Commander, its registers over raw CDP. */
    static boolean realPair(String commander, Map<RegisterTarget, String> registers, Map<String, String> env,
            Say say) {
        Path key = ShimPush.keyFile(env);
        if (!Files.isRegularFile(key)) {
            say.err("no SSH key at " + key + " for the Commander's pairing code: run Setup, or set $"
                    + com.verifone.cat.setup.SetupItems.SSH_KEY_ENV + "; nothing was paired");
            return false;
        }
        SshTarget ssh = new SshTarget("commander", commander, SshTarget.DEFAULT_PORT, ShimPush.SSH_USER,
                key.toString());
        Duration sshTimeout = Timeouts.defaults().ssh();
        Supplier<String> readOtp = () -> {
            try (Transport transport = new JschTransport(ssh)) {
                return transport.run(CommanderAccess.OTP_COMMAND, sshTimeout).stdout().strip();
            }
        };
        PsiInit.CgiFactory cgi = (host, password, mayBeExpired) -> {
            PdkShim shim = password == null
                    ? new PdkShim(host, PdkShim.COMMANDER_READ_TIMEOUT, readOtp)
                    : new PdkShim(host, PdkShim.COMMANDER_READ_TIMEOUT, readOtp, password, mayBeExpired);
            return shim::sendCommand;
        };
        BooleanSupplier labPasswordWorks = () -> {
            try {
                new PdkShim(commander, PdkShim.COMMANDER_READ_TIMEOUT, readOtp).login();
                return true;
            } catch (DeviceError e) {
                say.out("the lab password does not log in to the Commander (" + e.getMessage() + ")");
                return false;
            }
        };
        Map<String, String> urls = new LinkedHashMap<>();
        List<Rig.Register> asRegisters = new ArrayList<>();
        for (Map.Entry<RegisterTarget, String> entry : registers.entrySet()) {
            RegisterTarget r = entry.getKey();
            urls.put(r.id(), entry.getValue());
            asRegisters.add(new Rig.Register(r.id(), r.host(), r.cdpPort(), null, null, null));
        }
        PsiInit psi = new PsiInit(cgi, cdpRegisters(urls::get), PsiInit.defaultPsiMessageFile(), Waits::pause, say);
        say.out("pair with the lab Commander " + commander + ": " + String.join(", ", urls.keySet()));
        try {
            return psi.run(commander, null, asRegisters, true, labPasswordWorks);
        } catch (InspectError e) {
            // The register's screen over raw CDP, which PsiInit does not catch (it is not a DeviceError).
            say.err("pairing stopped on the register's screen: " + e.getMessage());
            return false;
        }
    }

    /** The registers' pairing screens over raw CDP, each at the endpoint {@code cdpUrl} names for its id. */
    static PsiInit.Registers cdpRegisters(Function<String, String> cdpUrl) {
        return new PsiInit.Registers() {
            @Override
            public PsiInit.PairingScreen open(Rig rig, Rig.Register register) {
                return new CdpPairingScreen(CdpPage.open(cdpUrl.apply(register.id()), CDP_TIMEOUT));
            }

            @Override
            public boolean atLoginScreen(Rig rig, Rig.Register register) {
                // The register reboots after pairing: each look reconnects, and a browser not back yet is "not yet".
                try (CdpPage page = CdpPage.open(cdpUrl.apply(register.id()), CDP_TIMEOUT)) {
                    return page.isShowing(Locators.IDLE_SCREEN_VIEW_IDLE_MSG)
                            || page.isShowing(Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT);
                } catch (InspectError e) {
                    return false;
                }
            }
        };
    }

    /** {@code PairingPage}, step for step, over a {@link CdpPage}. */
    static final class CdpPairingScreen implements PsiInit.PairingScreen {

        private final CdpPage page;

        CdpPairingScreen(CdpPage page) {
            this.page = page;
        }

        @Override
        public boolean isShowing() {
            return page.isShowing(Locators.PAIRING_PAIR_DEVICE) || isRefused();
        }

        @Override
        public boolean isRefused() {
            return page.isShowing(Locators.PAIRING_REFUSED_OK);
        }

        @Override
        public void backToStart(Duration timeout) {
            page.tap(Locators.PAIRING_REFUSED_OK, timeout);
            page.expectVisible(Locators.PAIRING_PAIR_DEVICE, timeout);
        }

        @Override
        public void start(Duration timeout) {
            page.tap(Locators.PAIRING_PAIR_DEVICE, timeout);
        }

        @Override
        public boolean awaitingCode() {
            return page.isShowing(Locators.PAIRING_KEYPAD_OK);
        }

        @Override
        public void enterCode(String code, Duration timeout) {
            for (char character : code.toCharArray()) {
                page.tap(Locators.PAIRING_KEYPAD_DIGIT.atNamed(Map.of("digit", String.valueOf(character))), timeout);
            }
            page.tap(Locators.PAIRING_KEYPAD_OK, timeout);
        }

        @Override
        public void confirm(Duration timeout) {
            page.expectVisible(Locators.PAIRING_PAIRED, timeout);
            page.tap(Locators.PAIRING_PAIRED_OK, timeout);
        }

        @Override
        public void close() {
            page.close();
        }
    }
}
