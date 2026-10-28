package com.verifone.cat.commander.emv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.fep.PacketLog;
import com.verifone.cat.preflight.Bounded.TimeoutExpired;

/**
 * {@code emv-keys}, driven through {@link EmvKeysCommand} and {@link EmvKeys} as the Python's
 * {@code test_emv_keys_tables.py} drives {@code cli.main}: the register and the Commander's SSH are fakes, the
 * Buypass host's answers are real lines in a real packet file. Evidence about this repository only.
 */
class EmvKeysTest {

    @TempDir
    Path dir;

    Path rigs;
    Path packets;
    EmvFakes.Commander commander;
    EmvFakes.Register register;
    final List<String> pressed = new ArrayList<>();
    int transportsBuilt;
    Duration downloadWait = Duration.ofSeconds(5);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        rigs = EmvFakes.rigs(dir);
        packets = Files.createDirectories(dir.resolve("BUYPASS")).resolve("packetFile.txt");
        Files.writeString(packets, "");
        register = new EmvFakes.Register(packets, pressed);
    }

    private void wire(boolean marker) {
        commander = new EmvFakes.Commander(marker);
    }

    private EmvKeysCommand command() {
        return new EmvKeysCommand((rig, env, say) -> new EmvKeys(rig,
                new CommanderAccess(rig, target -> {
                    transportsBuilt++;
                    return commander;
                }, env),
                (r, reg) -> {
                    pressed.add("register");
                    return register;
                },
                say, CommanderTables.Layout.COMMANDER, downloadWait,
                Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC),
                path -> new PacketLog(path == null ? packets : path)));
    }

    private int runWithPin(String pin, String... argv) {
        return command().run(argv, EmvFakes.env(rigs, pin), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int run(String... argv) {
        return runWithPin(null, argv);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /** The register's calls without the menu closes and the session close. */
    private List<String> presses() {
        return pressed.stream().filter(p -> !p.equals("close menus") && !p.equals("closed")).toList();
    }

    @Test
    void autoLoadsTheTablesOnACommanderWithNoRecordAndRecordsIt() {
        wire(false);

        assertEquals(0, run("emv-keys", "--rig", "bench", "--tables", "auto"));

        assertEquals(List.of("register", "tables", "emv keys", "sign off"), presses());
        assertTrue(commander.files.get(CommanderTables.TABLES_MARKER, Duration.ofSeconds(1)).isPresent());
        String marker = new String(commander.files.get(CommanderTables.TABLES_MARKER, Duration.ofSeconds(1))
                .orElseThrow(), StandardCharsets.UTF_8);
        assertEquals("Buypass table load seen by CAT at 2026-10-28T12:00:00Z\n", marker);
        assertEquals(0644, commander.files.modeOf(CommanderTables.TABLES_MARKER).orElseThrow());
        assertTrue(commander.closed);
        assertTrue(stdout().contains("table load: the Buypass host sent TABLE (RespCode 000)"), stdout());
        assertTrue(stdout().contains("signed the cashier off topaz101"), stdout());
    }

    @Test
    void autoLeavesTheRegisterAloneOnceTheCommanderHasHadItsLoad() {
        wire(true);

        assertEquals(0, run("--rig", "bench", "--tables", "auto"));

        assertEquals(List.of(), pressed);
        // [0, 2): the card table load check (ICC rows, then the BIN and rules tables).
        assertEquals(List.of(EmvFakes.MARKER_CHECK, "cashback"), commander.ran.subList(2, commander.ran.size()));
        assertEquals(List.of(), commander.files.writes());
        assertTrue(stdout().contains("table load: already done on this Commander"), stdout());
    }

    @Test
    void aTableLoadTheHostDoesNotApproveIsNotRecorded() {
        wire(false);
        register.tableCode = "096";

        run("--rig", "bench", "--tables", "auto");

        assertTrue(pressed.contains("tables"));
        assertEquals(List.of(), commander.files.writes());
    }

    @Test
    void bareTablesStillLoadsOnACommanderThatHasARecord() {
        wire(true);

        assertEquals(0, run("--rig", "bench", "--tables"));

        assertEquals(List.of("register", "tables", "emv keys", "sign off"), presses());
        assertFalse(commander.ran.contains(EmvFakes.MARKER_CHECK));
        assertTrue(commander.files.get(CommanderTables.TABLES_MARKER, Duration.ofSeconds(1)).isPresent());
    }

    @Test
    void bareTablesBeforeAnotherFlagIsAlways() {
        wire(true);

        assertEquals(0, run("--tables", "--rig", "bench"));

        assertEquals(List.of("register", "tables", "emv keys", "sign off"), presses());
    }

    @Test
    void withoutTablesTheCommanderIsNotAsked() {
        wire(false);

        assertEquals(0, run("--rig", "bench"));

        assertEquals(List.of("register", "emv keys", "sign off"), presses());
        assertEquals(0, transportsBuilt);
        assertEquals(List.of(), commander.ran);
    }

    @Test
    void autoLiftsTheCashbackCapAfterTheTableLoad() {
        wire(false);

        assertEquals(0, run("--rig", "bench", "--tables", "auto"));

        assertEquals("sign off", presses().get(presses().size() - 1));
        assertEquals("cashback", commander.ran.get(commander.ran.size() - 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"already", "absent", "no row"})
    void aTableNeedingNoEditIsNotAFailure(String found) {
        wire(true);
        commander.cashback = found;

        assertEquals(0, run("--rig", "bench", "--tables", "auto"));
        assertTrue(stdout().contains("-> '1,955,999': " + found), stdout());
    }

    @Test
    void anEditThatDidNotHappenFailsTheCommand() {
        wire(true);
        commander.cashback = "";

        assertNotEquals(0, run("--rig", "bench", "--tables", "auto"));
        assertTrue(stderr().contains("cashback limit: could not edit"), stderr());
    }

    @Test
    void autoUndoesACardTableLoadBeforeItReadsTheMarker() {
        wire(true);
        // ICC before; ICC, live, install after.
        commander.counts = List.of("1522\n", "0\n", "2674\n", "2674\n").iterator();

        assertEquals(0, run("--rig", "bench", "--tables", "auto"));

        assertEquals(List.of("count", "restore", "count", "count", "count", EmvFakes.MARKER_CHECK, "cashback"),
                commander.ran);
        assertEquals(List.of(), pressed);
        assertTrue(stdout().contains("card table load: found on this Commander; moved TBL0001.dat, "
                + "restored CardTable.xml; the load's files are in /root/cat-backup-dct-20261028T120000Z"), stdout());
    }

    @Test
    void aCardTableThatStillDiffersAfterTheRestoreFailsWithoutTouchingTheRegister() {
        wire(true);
        commander.counts = List.of("1522\n", "3\n", "2674\n", "2674\n").iterator();

        assertEquals(1, run("--rig", "bench", "--tables", "auto"));

        assertEquals(List.of(), pressed);
        assertTrue(stderr().contains("after the restore, not the install's 2674"), stderr());
    }

    @Test
    void anUnreachableCommanderFailsTheTablesRun() {
        wire(false);
        commander.files.failRunsWith(new com.verifone.cat.devices.DeviceUnreachable("ssh commander", "refused"));

        assertEquals(1, run("--rig", "bench", "--tables", "auto"));
        assertTrue(stderr().contains("emv-keys: cannot undo a card table load"), stderr());
        assertTrue(commander.closed);
    }

    @Test
    void aDownloadTheHostNeverAnswersTimesOutWithTheMenusClosed() {
        wire(false);
        register.hostAnswers = false;
        downloadWait = Duration.ofMillis(50);

        assertEquals(1, run("--rig", "bench"));

        assertEquals(List.of("register", "emv keys", "close menus", "closed"), pressed);
        assertTrue(stderr().contains("TimeoutExpired"), stderr());
        assertTrue(stderr().contains("EMV2KEY"), stderr());
    }

    @Test
    void theDownloadReturnsTheHostsCodeAndClosesTheMenus() throws IOException {
        EmvKeys keys = keys();
        PacketLog host = new PacketLog(packets);

        String code = keys.download(register, host, register::downloadTables, PacketLog.TABLE, "table load");

        assertEquals("000", code);
        assertEquals(List.of("tables", "close menus"), pressed);
    }

    @Test
    void theDownloadTimeoutIsBounded() {
        downloadWait = Duration.ofMillis(50);
        register.hostAnswers = false;
        EmvKeys keys = keys();

        assertThrows(TimeoutExpired.class, () -> keys.download(register, new PacketLog(packets),
                register::downloadTables, PacketLog.TABLE, "table load"));
        assertEquals(List.of("tables", "close menus"), pressed);
    }

    @Test
    void aRegisterAtItsLoginScreenIsSignedOnForNonSalesOnly() {
        wire(false);
        register.atLogin = true;

        assertEquals(0, runWithPin("4321", "--rig", "bench"));

        assertEquals(List.of("register", "sign on", "emv keys", "sign off"), presses());
        assertEquals(List.of("1/4321/NON-SALES FUNCTIONS ONLY"), register.signOns);
        assertTrue(stdout().contains("signed cashier 1 on to topaz101"), stdout());
    }

    @Test
    void aLoginScreenWithNoPasswordFailsBeforeAnyPress() {
        wire(false);
        register.atLogin = true;

        assertEquals(1, run("--rig", "bench"));

        assertEquals(List.of("register", "closed"), pressed);
        assertTrue(stderr().contains("topaz101 is at its login screen and $CAT_TEST_PIN is not set"), stderr());
    }

    @Test
    void usageErrors() {
        wire(false);
        assertEquals(2, run("--tables", "auto"));
        assertEquals(2, run("--rig", "bench", "--tables", "sometimes"));
        assertEquals(2, run("--rig", "bench", "--bogus"));
        assertEquals(2, run("--rig", "nosuchrig"));
        assertEquals(List.of(), pressed);
    }

    private EmvKeys keys() {
        Say.Recorded say = new Say.Recorded();
        return new EmvKeys(EmvFakes.rig(dir.resolve("k")), new CommanderAccess(EmvFakes.rig(dir.resolve("k2")),
                target -> commander, Map.of()), (r, reg) -> register, say, CommanderTables.Layout.COMMANDER,
                downloadWait, Clock.systemUTC(), path -> new PacketLog(packets));
    }
}
