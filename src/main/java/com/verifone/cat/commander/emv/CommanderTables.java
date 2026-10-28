package com.verifone.cat.commander.emv;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.preflight.Bounded.TimeoutExpired;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The Commander side of {@code emv-keys}, over SSH: the table-load marker, the credit cashback cap and the
 * undo of a card table load. A port of the transport half of the Python CAT's {@code petrotest.pos.emv_keys}
 * ({@code tables_loaded}, {@code mark_tables_loaded}, {@code raise_cashback_limit}, {@code card_table_loaded},
 * {@code icc_card_rows}, {@code card_rows}, {@code _undo_script}, {@code undo_card_table_load},
 * {@code _cashback}).
 *
 * <p><b>The table load, once per Commander install (2026-09-24, run {@code fdee047203f7}).</b> A Commander
 * reinstalled other than by CAT and run with upgrade mode {@code skip} never got its Buypass tables: 35 of the
 * Charity plan's 39 cases failed. {@link #TABLES_MARKER} is CAT's record that the host has approved a
 * {@code TABLE} download since the install; a reinstall wipes it with the rest of the filesystem.
 *
 * <p><b>Credit cashback (rig owner, 2026-10-11).</b> The Buypass restriction table caps NACS product 955 at
 * $0.00 for product table 1 ({@link #CAPPED_ROW}); {@link #raiseCashbackLimit} makes it {@link #CASHBACK_ROW}
 * in place. The FEP rereads the file when its timestamp moves, so nothing restarts.
 *
 * <p><b>A card table load is undone (2026-10-20, run {@code ec21feda7ba1}).</b> RCI Functions'
 * PETROCORE-130496 loads {@code DYNCRDTBL}; the EPS compiles it into its XML tables and every 12/2024 chip card
 * then gets CARD EXPIRED. {@link #undoCardTableLoad} puts the install's copies back, removes the load's own,
 * keeps everything it touched under {@link #DCT_BACKUP_ROOT}, and checks the result on disk.
 *
 * <p>Every remote path comes from a {@link Layout}, {@link Layout#COMMANDER} in production; tests point one at a
 * temporary folder, as the Python tests monkeypatch the module's constants. Failures are {@link DeviceError}
 * (the Python's {@code TransportError}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's own marker paths were seen only by
 * {@code InMemoryTransport}; the cashback edit and the card table undo ran live in the Python
 * (2026-10-11, 2026-10-20), never from this class.
 */
public final class CommanderTables {

    /** CAT's record, on the Commander, that the Buypass host has sent this install its tables. */
    public static final String TABLES_MARKER = "/home/pmc/data/.petrotest-tables-loaded";

    /** The Buypass FEP's restriction table ({@code TBL0003}, "RESTRCTION"), one {@code table,product,limit} row a line. */
    public static final String CASHBACK_TABLE = "/home/pmc/data/buypass/firstdata/tables/TBL0003.dat";
    /** Product table 1's cashback row as the host sends it: a $0.00 limit. */
    public static final String CAPPED_ROW = "1,955,.00";
    /** The same row as CAT leaves it: a $999 limit. */
    public static final String CASHBACK_ROW = "1,955,999";

    /** Where a card table load ({@code DYNCRDTBL}) leaves {@code TBL0001.dat} to {@code TBL0006.dat}. */
    public static final String DYNAMIC_CARD_TABLES = "/home/pmc/data/buypass/firstdata/tables";
    /** The BIN and rules tables, which mark a load: a Commander can hold {@code TBL0003} alone. */
    public static final List<String> DYNAMIC_CARD_TABLE_MARKS = List.of("TBL0001.dat", "TBL0002.dat");
    /** Where {@link #undoCardTableLoad} keeps what it replaces, one folder per undo. */
    public static final String DCT_BACKUP_ROOT = "/root";
    /** The EPS's live tables, which it reloads and writes back itself. */
    public static final String EPS_TABLES = "/home/pmc/data/tables";
    /** The install's copies of them. */
    public static final String INSTALL_TABLES = "/home/pmc/reldata";
    /** The card table's file name. The install's has no {@code ICC} row. */
    public static final String CARD_TABLE = "CardTable.xml";
    /** The card table. */
    public static final String EPS_CARD_TABLE = EPS_TABLES + "/" + CARD_TABLE;
    /** What the load compiles its {@code TBL*.dat} into; put back from {@link #INSTALL_TABLES}. */
    public static final List<String> CARD_TABLE_LOAD_RESTORED = List.of(
            "CardTable.xml",
            "NACSProdCodesTable.xml",
            "buypass_LimitTable.xml",
            "buypass_PromptTable.xml");
    /** The load's tables the install has none of. {@code ProductTable.xml} is left, as the Python leaves it. */
    public static final List<String> CARD_TABLE_LOAD_ONLY = List.of(
            "buypass_LogicControlTable.xml",
            "buypass_LogicIndicatorTable.xml",
            "buypass_PromptIndicatorTable.xml",
            "buypass_TextTable.xml");

    private static final DateTimeFormatter MARKER_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** Every remote path this class touches. The card table is {@code epsTables/CardTable.xml}. */
    public record Layout(String tablesMarker, String cashbackTable, String dynamicCardTables, String dctBackupRoot,
            String epsTables, String installTables) {

        /** The Commander's own paths. */
        public static final Layout COMMANDER = new Layout(TABLES_MARKER, CASHBACK_TABLE, DYNAMIC_CARD_TABLES,
                DCT_BACKUP_ROOT, EPS_TABLES, INSTALL_TABLES);

        public Layout {
            Objects.requireNonNull(tablesMarker, "tablesMarker");
            Objects.requireNonNull(cashbackTable, "cashbackTable");
            Objects.requireNonNull(dynamicCardTables, "dynamicCardTables");
            Objects.requireNonNull(dctBackupRoot, "dctBackupRoot");
            Objects.requireNonNull(epsTables, "epsTables");
            Objects.requireNonNull(installTables, "installTables");
        }

        public String epsCardTable() {
            return epsTables + "/" + CARD_TABLE;
        }

        public String installCardTable() {
            return installTables + "/" + CARD_TABLE;
        }
    }

    private final Transport transport;
    private final Duration timeout;
    private final Layout layout;
    private final Say say;
    private final Clock clock;

    public CommanderTables(Transport transport, Duration timeout, Say say) {
        this(transport, timeout, Layout.COMMANDER, say, Clock.systemUTC());
    }

    public CommanderTables(Transport transport, Duration timeout, Layout layout, Say say, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.say = Objects.requireNonNull(say, "say");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Layout layout() {
        return layout;
    }

    /** Whether the marker exists: {@code test -e} over plain SSH, so no SFTP. */
    public boolean tablesLoaded() {
        return transport.run("test -e " + layout.tablesMarker(), timeout).ok();
    }

    /** Writes the marker, once the Buypass host has approved a {@code TABLE} download. */
    public void markTablesLoaded() {
        String stamp = MARKER_STAMP.format(clock.instant());
        transport.put(layout.tablesMarker(),
                ("Buypass table load seen by CAT at " + stamp + "\n").getBytes(StandardCharsets.UTF_8), 0644, timeout);
    }

    /**
     * Makes the restriction table's {@link #CAPPED_ROW} read {@link #CASHBACK_ROW}; what was found. One of
     * {@code raised}, {@code already}, {@code absent} (no table) or {@code no row} (a table without the capped
     * row, left alone).
     */
    public String raiseCashbackLimit() {
        String table = layout.cashbackTable();
        String capped = CAPPED_ROW.replace(".", "\\.");
        String row = CASHBACK_ROW;
        CommandResult result = transport.run(
                "test -e " + table + " || { echo absent; exit 0; }; "
                        + "grep -qx '" + row + "' " + table + " && { echo already; exit 0; }; "
                        + "grep -qx '" + capped + "' " + table + " || { echo 'no row'; exit 0; }; "
                        + "sed -i 's/^" + capped + "$/" + row + "/' " + table + " && grep -qx '" + row + "' "
                        + table + " && echo raised",
                timeout);
        String found = result.stdout().strip();
        if (!result.ok() || found.isEmpty()) {
            String why = result.stderr().strip();
            throw new DeviceError("could not edit " + table + ": " + (why.isEmpty() ? "no answer" : why));
        }
        return found;
    }

    /**
     * Whether the EPS runs on a card table load's tables ({@code ICC} rows in its card table), or the load's BIN
     * or rules table is here.
     */
    public boolean cardTableLoaded() {
        if (iccCardRows() > 0) {
            return true;
        }
        StringBuilder paths = new StringBuilder();
        for (String name : DYNAMIC_CARD_TABLE_MARKS) {
            if (paths.length() > 0) {
                paths.append(' ');
            }
            paths.append(layout.dynamicCardTables()).append('/').append(name);
        }
        CommandResult result = transport.run(
                "for f in " + paths + "; do [ -e \"$f\" ] && { echo yes; break; }; done; true", timeout);
        if (!result.ok()) {
            throw new DeviceError("could not look in " + layout.dynamicCardTables() + ": " + result.stderr().strip());
        }
        return result.stdout().strip().equals("yes");
    }

    /** How many {@code ICC} rows the EPS's card table holds: none in the install's. */
    public int iccCardRows() {
        CommandResult result = transport.run("grep -c '<CTI>ICC' " + layout.epsCardTable() + "; true", timeout);
        return count(result, "could not count " + layout.epsCardTable() + "'s ICC rows");
    }

    /** How many {@code <CardRow>} lines {@code table} holds (one per row, as the EPS writes it). */
    public int cardRows(String table) {
        CommandResult result = transport.run("grep -c '<CardRow>' " + table + "; true", timeout);
        return count(result, "could not count " + table + "'s rows");
    }

    private static int count(CommandResult result, String what) {
        String text = result.stdout().strip();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new DeviceError(what + ": '" + result.stdout() + "'", e);
        }
    }

    /**
     * Back the load's files up, put the install's tables back, move the load's own aside. {@code cp -p} keeps the
     * install copy's owner and mode; each restored table lands under a temporary name and is renamed over the live
     * one, so the EPS never reads half a file. Says {@code moved}/{@code restored}/{@code removed <name>} per file.
     */
    String undoScript(String backup) {
        String live = layout.epsTables();
        String install = layout.installTables();
        String dat = layout.dynamicCardTables();
        String restored = String.join(" ", CARD_TABLE_LOAD_RESTORED);
        String removed = String.join(" ", CARD_TABLE_LOAD_ONLY);
        return "set -e; for f in " + restored + "; do if [ ! -e " + install + "/\"$f\" ]; then "
                + "echo \"no install copy of $f in " + install + "; nothing changed\" >&2; exit 1; fi; done; "
                + "mkdir -p " + backup + "/tables; "
                + "for f in " + dat + "/TBL*.dat; do if [ -e \"$f\" ]; then "
                + "mv \"$f\" " + backup + "/; echo \"moved ${f##*/}\"; fi; done; "
                + "for f in " + restored + " " + removed + "; do "
                + "if [ -e " + live + "/\"$f\" ]; then cp -p " + live + "/\"$f\" " + backup + "/tables/; fi; done; "
                + "for f in " + restored + "; do "
                + "cp -p " + install + "/\"$f\" " + live + "/\"$f\".cat-restore; "
                + "mv " + live + "/\"$f\".cat-restore " + live + "/\"$f\"; echo \"restored $f\"; done; "
                + "for f in " + removed + "; do "
                + "if [ -e " + live + "/\"$f\" ]; then rm " + live + "/\"$f\"; echo \"removed $f\"; fi; done";
    }

    /**
     * Puts the install's card tables back in place of a card table load's; whether it took. True at once when
     * there is no load. Everything it replaces or removes is kept in {@code DCT_BACKUP_ROOT/cat-backup-dct-<UTC>}.
     * No PMC restart, as the Python. Checked on disk afterwards: no {@code ICC} row, and the install's row count.
     */
    public boolean undoCardTableLoad() {
        if (!cardTableLoaded()) {
            return true;
        }
        String backup = layout.dctBackupRoot() + "/cat-backup-dct-" + BACKUP_STAMP.format(clock.instant());
        CommandResult done = transport.run(undoScript(backup), timeout);
        if (!done.ok()) {
            String why = done.stderr().strip();
            throw new DeviceError("could not undo the card table load (backup in " + backup + "): "
                    + (why.isEmpty() ? done.stdout().strip() : why));
        }
        say.out("card table load: found on this Commander; " + String.join(", ", done.stdout().lines().toList())
                + "; the load's files are in " + backup);
        int icc = iccCardRows();
        int rows = cardRows(layout.epsCardTable());
        int want = cardRows(layout.installCardTable());
        if (icc != 0 || rows != want) {
            say.err("card table load: " + layout.epsCardTable() + " has " + rows + " rows (" + icc
                    + " ICC) after the restore, not the install's " + want + "; the load's files are in " + backup);
            return false;
        }
        say.out("card table load: undone; " + layout.epsCardTable() + " is the install's (" + rows + " rows)");
        return true;
    }

    /** {@link #raiseCashbackLimit}, reported: false (and an error line) when the edit could not be made. */
    public boolean cashback() {
        String found;
        try {
            found = raiseCashbackLimit();
        } catch (DeviceError | TimeoutExpired e) {
            say.err("cashback limit: " + e.getMessage());
            return false;
        }
        say.out("cashback limit: " + layout.cashbackTable() + " '" + CAPPED_ROW + "' -> '" + CASHBACK_ROW + "': "
                + found);
        return true;
    }
}
