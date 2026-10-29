package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.inits.TestActions.act;
import static com.verifone.cat.pats.runner.handlers.inits.TestActions.kase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.legacyrmi.LegacyRmi;
import com.verifone.cat.pats.WidgetKeys;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.handlers.inits.TestClock;
import com.verifone.cat.pats.runner.handlers.sale.SaleScreen;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The report actions over the production register: {@link LegacyRegister} over a real {@link LegacyRmi} talking to a
 * loopback pos-shim stand-in, and a fake POS screen. This mirrors the Python's {@code test_pats_runner.py} report tests,
 * which run {@code _network_report} over {@code LegacyRmi(_RecordingShim())} and a fake driver.
 *
 * <p>Evidence about this repository only: no register answered these keys.
 */
class LegacyRegisterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRESS_REPLY = "{\"client\":\"test\",\"requested\":[],\"pressed\":[],"
            + "\"all_processed\":true}";
    private static final String PENDING_REPLY = "{\"key\":\"ENTER\",\"processed\":true,\"elapsed_ms\":10}";
    private static final String KEYS_REPLY = "{\"named_keys\":[\"ENTER\",\"EXIT\",\"PRINT_REPORT\",\"VOID_TICKET\"],"
            + "\"rejected_names\":[]}";

    @TempDir
    Path folder;

    private FakeShim shim;
    private FakeScreen screen;
    private final List<Duration> pauses = new ArrayList<>();
    private int overlayClears;
    private LegacyRegister register;

    @BeforeEach
    void setUp() {
        shim = new FakeShim()
                .on("GET", "/pos/keys", 200, KEYS_REPLY)
                .on("POST", "/pos/keys", 200, PRESS_REPLY)
                .on("POST", "/pos/keys/enter", 200, PENDING_REPLY)
                .on("POST", "/pos/keys/pending", 200, PENDING_REPLY);
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(5));
        LegacyRmi rmi = new LegacyRmi(client, () -> overlayClears++);
        screen = new FakeScreen();
        register = new LegacyRegister(rmi, screen, WidgetKeys.standard(), Duration.ofSeconds(2),
                Duration.ofSeconds(1), pauses::add);
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    private CaseRun run(String key, RunnableAction action) throws Exception {
        TestClock clock = new TestClock();
        ConfigurationContext context = ConfigFixtures.context(folder, clock, register, StuckCloseRecovery.UNBOUND,
                CloudSender.UNBOUND);
        return ConfigFixtures.caseRun(context, ConfigFixtures.noFixtures(), kase(key, action), Set.of());
    }

    /** The shim's key sends, as the Python's {@code _RecordingShim.sent} lists them. GETs are not sends. */
    private List<String> sent() throws Exception {
        List<String> out = new ArrayList<>();
        for (FakeShim.Seen seen : shim.seen) {
            if ("GET".equals(seen.method())) {
                continue;
            }
            String path = seen.pathAndQuery().split("\\?", 2)[0];
            JsonNode body = JSON.readTree(seen.body());
            switch (path) {
                case "/pos/keys" -> body.get("keys").forEach(k -> out.add(k.asText()));
                case "/pos/keys/enter" -> out.add("ENTER:" + body.get("data").asText());
                case "/pos/keys/pending" -> out.add(body.get("key").asText() + ":" + body.get("data").asText());
                default -> out.add(seen.method() + " " + path);
            }
        }
        return out;
    }

    @Test
    void petrocore37028sTerminalBatchSummaryKeysItsMenuPathThroughTheShim() throws Exception {
        // 37028's own arguments, as the run dec5794107fa read them.
        CaseRun run = run("PETROCORE-37028", act(0, "NETWORK_REPORT", "MAIN_MENU_NO", "2", "NETWORK_MENU_NO", "4",
                "REPORT_NO", "1", "IS_SECURE_REPORT", "N", "SECURE_USER", "1", "DAY_SEQUENCE", "0"));

        run.run();

        // CSR Func (EXIT to the main menu), 2, 4, report 1, DAY_SEQUENCE 0 carried, then PRINT_REPORT.
        assertEquals(List.of("EXIT", "ENTER:2", "ENTER:4", "ENTER:1", "ENTER:0", "PRINT_REPORT"), sent());
        assertEquals(1, screen.sweeps, "the run's sweep, once before the keys");
        assertEquals(5, screen.settles, "each key before PRINT_REPORT waits for the screen to settle");
        assertTrue(screen.screenReads >= 6, "the screen is read before each key and for the report's view");
        assertEquals(6, overlayClears, "the shim path's before-action ran for every key");
    }

    @Test
    void anEmptyNetworkMenuNoGoesAsABareEnterThroughTheShim() throws Exception {
        // The Python's test_an_empty_network_menu_no_is_the_bare_enter_pats_pending_token_sends (PETROCORE-44319).
        CaseRun run = run("PETROCORE-44319", act(0, "NETWORK_REPORT", "MAIN_MENU_NO", "1", "NETWORK_MENU_NO", "",
                "REPORT_NO", "11", "SUB_REPORT_NO", "1", "IS_SECURE_REPORT", "N", "SECURE_USER", "",
                "SECURE_PASSWORD", "", "DAY_SEQUENCE", "", "BATCH_NUMBER", ""));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:1", "ENTER", "ENTER:11", "ENTER:1", "PRINT_REPORT", "EXIT"), sent());
    }

    @Test
    void aFlashReportSendsItsSevenKeysThroughTheShim() throws Exception {
        CaseRun run = run("PETROCORE-30", act(0, "PRINT_FLASH_REPORT", "MAIN MENU NO", "1", "FUNCTIONS MENU NO",
                "20", "FLASH REPORTS MENU NO", "0"));

        run.run();

        assertEquals(List.of("EXIT", "ENTER:1", "ENTER:20", "ENTER:0", "EXIT", "EXIT", "EXIT"), sent());
        assertEquals(List.of(), pauses, "no message up: the overlay check does not pause");
    }

    @Test
    void theReadsAreTheWidgetTextsThePythonCompares() {
        assertEquals(Set.of("Recall", "CSR Func", "Price Check"), register.functionLabels());
        assertEquals("Closing Cashiers", register.closingCashiersLine());
        screen.express = List.of("Reprint");
        assertTrue(register.ready());
        screen.express = List.of("Price Check");
        assertFalse(register.ready(), "Price Check offers functions but is not Ready (pos_ui._ready)");
        assertTrue(register.overlay().isEmpty());
    }

    @Test
    void theOverlayPauseWaitsOnlyWhenAMessageIsUp() {
        register.overlayPause();
        assertEquals(List.of(), pauses);
        screen.overlay = Map.of("TITLE", "Close Shift", "LINE1", "Closing Cashiers", "LINE2", "", "LINE3", "");
        register.overlayPause();
        assertEquals(List.of(Duration.ofSeconds(1)), pauses);
        assertEquals("Closing Cashiers", register.overlay().orElseThrow().get("LINE1"));
    }

    /** A POS screen that is always Ready and showing a built report's view. */
    private static final class FakeScreen implements SaleScreen {
        List<String> express = List.of("CSR Func");
        Map<String, String> overlay;
        int sweeps;
        int settles;
        int screenReads;

        @Override
        public String screenText() {
            screenReads++;
            return "Dismiss Report\tPrint Report";
        }

        @Override
        public boolean settled(String before, Duration cap) {
            assertEquals(Duration.ofSeconds(2), cap);
            settles++;
            return true;
        }

        @Override
        public Map<String, String> overlay() {
            return overlay;
        }

        @Override
        public List<String> expressLabels() {
            return express;
        }

        @Override
        public void clearBlockingOverlays() {
            sweeps++;
        }

        @Override
        public String receiptText() {
            throw new AssertionError("the report actions do not read the receipt");
        }
    }
}
