package com.verifone.cat.commander.emv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;

/**
 * {@link CommanderTables}' shell text, run by a real {@code sh} against files in a temporary folder, as the
 * Python's {@code test_emv_keys_tables.py} runs it. Skipped where there is no {@code sh}. Evidence about the
 * shell text only, not about a Commander.
 */
class CommanderTablesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path root;

    EmvFakes.Shell shell;
    final Say.Recorded say = new Say.Recorded();

    @BeforeEach
    void setUp() throws IOException {
        String sh = EmvFakes.Shell.find();
        assumeTrue(sh != null, "no POSIX sh on this machine");
        for (String name : List.of("dat", "live", "install", "root", "scratch")) {
            Files.createDirectories(root.resolve(name));
        }
        shell = new EmvFakes.Shell(sh, root.resolve("scratch"));
    }

    private String posix(String name) {
        return root.resolve(name).toString().replace('\\', '/');
    }

    private CommanderTables tables() {
        CommanderTables.Layout layout = new CommanderTables.Layout(posix("marker"), posix("TBL0003.dat"),
                posix("dat"), posix("root"), posix("live"), posix("install"));
        return new CommanderTables(shell, TIMEOUT, layout, say, Clock.systemUTC());
    }

    // -- the cashback row ------------------------------------------------------------------

    /** The restriction table as the EPS saved it on Reg101 (2026-06-30), LF line ends. */
    private static final String TABLE = "0,***,99999.99\n1,51*,.00\n" + CommanderTables.CAPPED_ROW
            + "\n2,4**,.00\n11,955,.00\n";

    @Test
    void theEditChangesOnlyTheProductTable1CashbackRow() throws IOException {
        Path table = root.resolve("TBL0003.dat");
        Files.write(table, TABLE.getBytes(StandardCharsets.UTF_8));

        assertEquals("raised", tables().raiseCashbackLimit());

        // Product table 11's own cashback row belongs to another table and stays.
        String after = "0,***,99999.99\n1,51*,.00\n" + CommanderTables.CASHBACK_ROW + "\n2,4**,.00\n11,955,.00\n";
        assertEquals(after, Files.readString(table));
        assertEquals("already", tables().raiseCashbackLimit());
    }

    @Test
    void theEditReportsATableItCannotFindOrHasNoRow() throws IOException {
        assertEquals("absent", tables().raiseCashbackLimit());

        Path table = root.resolve("TBL0003.dat");
        Files.writeString(table, "0,***,99999.99\n11,955,.00\n");
        assertEquals("no row", tables().raiseCashbackLimit());
        assertEquals("0,***,99999.99\n11,955,.00\n", Files.readString(table));
    }

    @Test
    void aCommandThatFailsIsADeviceError() {
        // Every command answers exit 1 "Permission denied".
        CommanderTables tables = new CommanderTables(new EmvFakes.Shell(shell.sh, root.resolve("scratch")) {
            @Override
            public CommandResult run(String command, Duration timeout) {
                return new CommandResult(1, "", "Permission denied");
            }
        }, TIMEOUT, say);
        DeviceError error = assertThrows(DeviceError.class, tables::raiseCashbackLimit);
        assertTrue(error.getMessage().contains("Permission denied"), error.getMessage());
    }

    // -- a card table load, undone (runs ec21feda7ba1, df1dc2caae55, 2026-10-20) -------------

    private static String cardTable(String... ctis) {
        StringBuilder rows = new StringBuilder();
        for (String cti : ctis) {
            rows.append("    <CardRow>\n        <CTI>").append(cti).append("</CTI>\n    </CardRow>\n");
        }
        return "<CardTable>\n" + rows + "</CardTable>\n";
    }

    private static final String INSTALL_CARDS = cardTable("V00030182", "ICV00030182", "ICM00040001");
    private static final String LOAD_CARDS = cardTable("C00030182", "ICC00030182", "FBC00030182", "C90000001");

    private void install() throws IOException {
        Files.writeString(root.resolve("install/CardTable.xml"), INSTALL_CARDS);
        for (String name : CommanderTables.CARD_TABLE_LOAD_RESTORED.subList(1, 4)) {
            Files.writeString(root.resolve("install").resolve(name), "install " + name + "\n");
        }
        for (String name : CommanderTables.CARD_TABLE_LOAD_RESTORED) {
            Files.writeString(root.resolve("live").resolve(name),
                    Files.readString(root.resolve("install").resolve(name)));
        }
        Files.writeString(root.resolve("live/POPConfigTable.xml"), "vipercfg's POP 1\n");
    }

    private void load(boolean dat) throws IOException {
        if (dat) {
            for (int n = 1; n <= 6; n++) {
                Files.writeString(root.resolve("dat/TBL000" + n + ".dat"), "table " + n + "\n");
            }
        }
        Files.writeString(root.resolve("live/CardTable.xml"), LOAD_CARDS);
        for (String name : CommanderTables.CARD_TABLE_LOAD_RESTORED.subList(1, 4)) {
            Files.writeString(root.resolve("live").resolve(name), "load " + name + "\n");
        }
        for (String name : CommanderTables.CARD_TABLE_LOAD_ONLY) {
            Files.writeString(root.resolve("live").resolve(name), "load " + name + "\n");
        }
    }

    private static List<Path> list(Path folder) throws IOException {
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.toList();
        }
    }

    private static long count(Path folder, String prefix, String suffix) throws IOException {
        return list(folder).stream().map(p -> p.getFileName().toString())
                .filter(n -> n.startsWith(prefix) && n.endsWith(suffix)).count();
    }

    @Test
    void anInstallCommanderIsLeftAlone() throws IOException {
        // A lone TBL0003 (HANDOFF.md 1.131's Commander) and ICV/ICM chip rows are no load.
        install();
        Files.writeString(root.resolve("dat/TBL0003.dat"), "1,955,999\n");

        assertTrue(tables().undoCardTableLoad());

        assertTrue(Files.exists(root.resolve("dat/TBL0003.dat")));
        assertTrue(list(root.resolve("root")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aCardTableLoadIsReplacedByTheInstallTables(boolean dat) throws IOException {
        install();
        load(dat);

        assertTrue(tables().undoCardTableLoad(), say.errText());

        Path live = root.resolve("live");
        for (String name : CommanderTables.CARD_TABLE_LOAD_RESTORED) {
            assertEquals(Files.readString(root.resolve("install").resolve(name)), Files.readString(live.resolve(name)));
        }
        for (String name : CommanderTables.CARD_TABLE_LOAD_ONLY) {
            assertFalse(Files.exists(live.resolve(name)), name);
        }
        assertEquals("vipercfg's POP 1\n", Files.readString(live.resolve("POPConfigTable.xml")));
        assertEquals(0, count(root.resolve("dat"), "TBL", ".dat"));
        assertEquals(0, count(live, "", ".cat-restore"));

        List<Path> backups = list(root.resolve("root"));
        assertEquals(1, backups.size());
        Path backup = backups.get(0);
        assertTrue(backup.getFileName().toString().startsWith("cat-backup-dct-"));
        assertEquals(LOAD_CARDS, Files.readString(backup.resolve("tables/CardTable.xml")));
        Set<String> kept = new HashSet<>();
        for (Path p : list(backup.resolve("tables"))) {
            kept.add(p.getFileName().toString());
        }
        Set<String> want = new HashSet<>(CommanderTables.CARD_TABLE_LOAD_RESTORED);
        want.addAll(CommanderTables.CARD_TABLE_LOAD_ONLY);
        assertEquals(want, kept);
        assertEquals(dat ? 6 : 0, count(backup, "TBL", ".dat"));
        assertFalse(tables().cardTableLoaded());
        assertTrue(say.outText().contains("card table load: undone;"), say.outText());
    }

    @Test
    void anInstallTableMissingFailsTheUndoBeforeAnythingMoves() throws IOException {
        install();
        load(true);
        Files.delete(root.resolve("install/NACSProdCodesTable.xml"));

        DeviceError error = assertThrows(DeviceError.class, () -> tables().undoCardTableLoad());
        assertTrue(error.getMessage().contains("no install copy of NACSProdCodesTable.xml"), error.getMessage());

        assertEquals(LOAD_CARDS, Files.readString(root.resolve("live/CardTable.xml")));
        assertEquals(6, count(root.resolve("dat"), "TBL", ".dat"));
        assertTrue(list(root.resolve("root")).isEmpty());
    }

    @Test
    void aCardTableThatDoesNotMatchTheInstallAfterTheRestoreFails() throws IOException {
        install();
        load(true);
        Files.writeString(root.resolve("install/CardTable.xml"), LOAD_CARDS); // a wrong install copy

        assertFalse(tables().undoCardTableLoad());
        assertTrue(say.errText().contains("after the restore, not the install's 4"), say.errText());
    }

    @Test
    void theMarkerIsCheckedWithTestE() throws IOException {
        assertFalse(tables().tablesLoaded());
        Files.writeString(root.resolve("marker"), "x");
        assertTrue(tables().tablesLoaded());
    }
}
