package com.verifone.cat.commander.emv;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.fep.PacketLog;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.RegisterEndpoint;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.preflight.Bounded.TimeoutExpired;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * {@code emv-keys --rig X [--tables [always|auto]] [--host-packets FILE]}: get the Commander's EPS talking to a
 * pinpad simulator, and after a new install give it its Buypass tables. A port of the Python CAT's
 * {@code petrotest.pos.emv_keys} ({@code emv_keys}, {@code emv_keys_cli}, {@code _download}); its Commander
 * half is {@link CommanderTables}.
 *
 * <p>The rig owner's procedure, 2026-09-14: on the primary register, CSR Func -> 2. Network Menu -> 3. EPS
 * Network Functions -> 15. Perform EMV CA Public Key File Download, then log the cashier out. Seconds later the
 * pinpad simulator saw the EPS's first {@code SET_IDLE}. With tables, PATS's {@code BuypassTblLoader} order:
 * 3. Perform Table Load first. Each press is confirmed from the Buypass host's packet log ({@link PacketLog}),
 * not from the register's status strip, which stopped showing "TBL DOWNLOAD SUCCESSFUL" (rig owner, 2026-09-24).
 *
 * <p>No till is opened (rig owner, 2026-09-17): a login screen is answered with
 * {@link Pos#NON_SALES_ONLY}, since PETROCORE-37043 is what opens the till.
 *
 * <p>The register is reached through {@link Register}, a seam over {@link Pos}: production attaches over CDP
 * ({@link #connectRegister}); tests pass a recorder.
 *
 * <p>Deviations from the Python, each deliberate:
 * <ul>
 *   <li>The cashier's password is {@link Rig.Cashier#resolvePassword}, so an inline {@code password} is
 *       honoured where the Python reads only {@code password_env}; a rig with no {@code cashier:} block fails
 *       with a line where the Python would raise.</li>
 *   <li>Lines go to a {@link Say}, the Python's stdout and stderr.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Fake-tested only: a recording register, an in-memory Commander
 * and a packet file in a temporary folder. The Python ran this against Reg101 (2026-09-14, 2026-10-20).
 */
public final class EmvKeys {

    /** Ceiling for the Buypass host to answer one download. A bound, not a pause. */
    public static final Duration DOWNLOAD_WAIT = Duration.ofSeconds(240);

    /** The host's approval. All 15 {@code TABLE} answers in {@code packetFile.txt} to 2026-09-24 carry it. */
    public static final String APPROVED = "000";

    /** {@code --tables always}: the table load whatever the Commander has recorded. */
    public static final String TABLES_ALWAYS = "always";
    /** {@code --tables auto}: the table load only when the Commander has no {@link CommanderTables#TABLES_MARKER}. */
    public static final String TABLES_AUTO = "auto";

    /** The register as {@code emv_keys} drives it: the few {@link Pos} presses it needs. */
    public interface Register extends AutoCloseable {

        boolean atLogin();

        void signOnFromLogin(String cashierId, String password, String till, Duration timeout);

        /** Presses 3. Perform Table Load and returns, menus left open. */
        void downloadTables();

        /** Presses 15. Perform EMV CA Public Key File Download and returns, menus left open. */
        void downloadEmvCaPublicKeys();

        void closeToMainMenu(Duration timeout);

        void signOff();

        @Override
        void close();

        /** {@code pos} over {@code driver}, which {@link #close} closes. */
        static Register of(Pos pos, AutoCloseable driver) {
            return new Register() {
                @Override
                public boolean atLogin() {
                    return pos.atLogin();
                }

                @Override
                public void signOnFromLogin(String cashierId, String password, String till, Duration timeout) {
                    pos.signOnFromLogin(cashierId, password, till, timeout);
                }

                @Override
                public void downloadTables() {
                    pos.downloadTables();
                }

                @Override
                public void downloadEmvCaPublicKeys() {
                    pos.downloadEmvCaPublicKeys(Pos.MENU_WAIT);
                }

                @Override
                public void closeToMainMenu(Duration timeout) {
                    pos.functionMenu().closeToMainMenu(timeout);
                }

                @Override
                public void signOff() {
                    pos.signOff();
                }

                @Override
                public void close() {
                    try {
                        driver.close();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        }
    }

    /** Opens the primary register. */
    @FunctionalInterface
    public interface Registers {
        Register open(Rig rig, Rig.Register register);
    }

    private final Rig rig;
    private final CommanderAccess access;
    private final Registers registers;
    private final Say say;
    private final CommanderTables.Layout layout;
    private final Duration downloadWait;
    private final Clock clock;
    private final Function<Path, PacketLog> hostLogs;

    /** The production flow: real SSH and the register's Chrome over CDP. */
    public static EmvKeys forRig(Rig rig, Say say) {
        return new EmvKeys(rig, new CommanderAccess(rig), EmvKeys::connectRegister, say);
    }

    public EmvKeys(Rig rig, CommanderAccess access, Registers registers, Say say) {
        this(rig, access, registers, say, CommanderTables.Layout.COMMANDER, DOWNLOAD_WAIT, Clock.systemUTC(),
                path -> path == null ? new PacketLog() : new PacketLog(path));
    }

    /** Every seam: tests shorten the download bound and give the host's packet file. */
    public EmvKeys(Rig rig, CommanderAccess access, Registers registers, Say say, CommanderTables.Layout layout,
            Duration downloadWait, Clock clock, Function<Path, PacketLog> hostLogs) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.access = Objects.requireNonNull(access, "access");
        this.registers = Objects.requireNonNull(registers, "registers");
        this.say = Objects.requireNonNull(say, "say");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.downloadWait = Objects.requireNonNull(downloadWait, "downloadWait");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hostLogs = Objects.requireNonNull(hostLogs, "hostLogs");
    }

    /** Attaches to {@code register}'s Chrome over CDP, as the Python's {@code connect_pos(..., trace=False)}. */
    public static Register connectRegister(Rig rig, Rig.Register register) {
        PosDriver driver = PosDriver.connect(RegisterEndpoint.of(register), Timeouts.of(rig.timeouts()),
                rig.build().expectedVersion(), null, null, false);
        try {
            return Register.of(new Pos(driver), driver);
        } catch (RuntimeException e) {
            driver.close();
            throw e;
        }
    }

    /**
     * {@code emv-keys --rig X [--tables [always|auto]] [--host-packets FILE]}; true when it succeeded.
     *
     * <p>No {@code tables}: the EMV CA key download alone. {@code always}: table load first, as after a new
     * install. {@code auto}: the table load only when the Commander has no marker; with one, nothing at all
     * happens on the register. Either mode first undoes a card table load, and then, after any table load, lifts
     * the cashback cap. Every table load the host approves is recorded, whichever mode asked for it.
     * {@code hostPackets} is the payment host's packet file, BUYPASS Host's when null.
     */
    public boolean cli(String tables, Path hostPackets) {
        if (tables == null) {
            return emvKeys(false, null, hostPackets);
        }
        if (!TABLES_ALWAYS.equals(tables) && !TABLES_AUTO.equals(tables)) {
            throw new IllegalArgumentException("--tables is always or auto, not '" + tables + "'");
        }
        Duration timeout = access.sshTimeout();
        Transport transport;
        try {
            transport = access.connectedTransport();
        } catch (DeviceError | TimeoutExpired e) {
            say.err("emv-keys: cannot reach the Commander to check its tables: " + e.getMessage());
            return false;
        }
        try {
            CommanderTables commander = new CommanderTables(transport, timeout, layout, say, clock);
            try {
                if (!commander.undoCardTableLoad()) {
                    return false;
                }
            } catch (DeviceError | TimeoutExpired e) {
                say.err("emv-keys: cannot undo a card table load: " + e.getMessage());
                return false;
            }
            if (TABLES_AUTO.equals(tables)) {
                boolean loaded;
                try {
                    loaded = commander.tablesLoaded();
                } catch (DeviceError | TimeoutExpired e) {
                    say.err("emv-keys: cannot check " + layout.tablesMarker() + ": " + e.getMessage());
                    return false;
                }
                if (loaded) {
                    say.out("table load: already done on this Commander (" + layout.tablesMarker() + "); skipped");
                    return commander.cashback();
                }
                say.out("table load: none recorded on this Commander (" + layout.tablesMarker() + "); loading");
            }
            Runnable record = () -> {
                try {
                    commander.markTablesLoaded();
                } catch (DeviceError | TimeoutExpired e) {
                    // The tables are loaded; the next run only pays for one more load.
                    say.out("table load: could not record it in " + layout.tablesMarker() + ": " + e.getMessage());
                }
            };
            boolean loadedOk = emvKeys(true, record, hostPackets);
            return commander.cashback() && loadedOk;
        } finally {
            transport.close();
        }
    }

    /**
     * Signs on if the register is at its login screen, downloads (the tables first when {@code tables}), signs
     * off. {@code onTablesLoaded} runs once the host has approved the table load. A download the host does not
     * answer within the bound throws {@link TimeoutExpired}, the menus closed first.
     */
    public boolean emvKeys(boolean tables, Runnable onTablesLoaded, Path hostPackets) {
        Rig.Register register = rig.primaryRegister();
        try (Register pos = registers.open(rig, register)) {
            if (pos.atLogin()) {
                Rig.Cashier cashier = rig.cashier();
                if (cashier == null) {
                    say.err(register.id() + " is at its login screen and the rig has no cashier");
                    return false;
                }
                String password = cashier.resolvePassword(access.environ());
                if (password.isEmpty()) {
                    say.err(register.id() + " is at its login screen and $" + cashier.passwordEnv() + " is not set");
                    return false;
                }
                // Right after payment-links restarts PMC the login screen is still redrawing;
                // signOnFromLogin waits for it to hold still and retries once.
                pos.signOnFromLogin(cashier.id(), password, Pos.NON_SALES_ONLY, Pos.MENU_WAIT);
                say.out("signed cashier " + cashier.id() + " on to " + register.id());
            }
            PacketLog host = hostLogs.apply(hostPackets);
            if (tables) {
                // After a new install, PATS's BuypassTblLoader loads the tables first.
                String code = download(pos, host, pos::downloadTables, PacketLog.TABLE, "table load");
                if (APPROVED.equals(code) && onTablesLoaded != null) {
                    onTablesLoaded.run();
                }
            }
            download(pos, host, pos::downloadEmvCaPublicKeys, PacketLog.EMV_KEYS, "EMV CA key download");
            pos.signOff();
            say.out("signed the cashier off " + register.id());
        }
        return true;
    }

    /**
     * Presses one EPS download and waits for the Buypass host to send {@code fileType}; its response code. The
     * menus are closed afterwards whether or not the host answered.
     */
    String download(Register pos, PacketLog host, Runnable press, String fileType, String what) {
        long mark = host.mark();
        try {
            press.run();
            String code = host.waitFor(fileType, mark, downloadWait);
            say.out(what + ": the Buypass host sent " + fileType + " (RespCode " + code + ")");
            return code;
        } finally {
            pos.closeToMainMenu(Pos.MENU_WAIT);
        }
    }
}
