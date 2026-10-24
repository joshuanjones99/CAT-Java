package com.verifone.cat.devices.legacyrmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;

/**
 * {@link LegacySaleRmi} against the {@link SaleRmi} seam: every seam method is driven once, through the shim's
 * recorded requests. Loopback only; nothing presses a register.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class LegacySaleRmiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRESS_REPLY = "{\"client\":\"test\",\"requested\":[],\"pressed\":[],"
            + "\"all_processed\":true}";
    private static final String PENDING_REPLY = "{\"key\":\"ENTER\",\"processed\":true}";

    private final List<String> marks = new ArrayList<>();
    private FakeShim shim;
    private SaleRmi sale;

    @BeforeEach
    void setUp() {
        shim = new FakeShim()
                .on("GET", "/pos/keys", 200, "{\"named_keys\":[\"APPROVE\",\"CASH\",\"ENTER\",\"EXIT\",\"QTY\","
                        + "\"REFUND\",\"SUSP\",\"TAX_EXEMPT\",\"TOTAL\",\"VOID_LINE\",\"VOID_TICKET\","
                        + "\"ERROR_CORRECT\",\"YES\"]}")
                .on("POST", "/pos/keys", 200, PRESS_REPLY)
                .on("POST", "/pos/keys/enter", 200, PENDING_REPLY)
                .on("POST", "/pos/keys/pending", 200, PENDING_REPLY)
                .on("POST", "/pos/scan", 200, "{\"processed\":true}");
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(5));
        LegacyRmi rmi = new LegacyRmi(client, () -> mark("overlays"), d -> marks.add("pause:" + d.toSeconds()));
        sale = new LegacySaleRmi(rmi);
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    private void mark(String name) {
        marks.add(name + "@" + wire().size());
    }

    private List<String> wire() {
        List<String> out = new ArrayList<>();
        for (FakeShim.Seen seen : shim.seen) {
            if (!"GET".equals(seen.method())) {
                out.add(describe(seen));
            }
        }
        return out;
    }

    private static String describe(FakeShim.Seen seen) {
        String path = seen.pathAndQuery().split("\\?", 2)[0];
        try {
            JsonNode body = JSON.readTree(seen.body());
            return switch (path) {
                case "/pos/keys" -> "press_keys(" + body.get("keys").get(0).asText() + ")";
                case "/pos/keys/pending" ->
                        "press_with_data(" + body.get("key").asText() + "," + body.get("data").asText() + ")";
                case "/pos/keys/enter" -> "press_enter_with_data(" + body.get("data").asText() + ")";
                case "/pos/scan" -> "scan(" + body.get("scan_data").asText() + ")";
                default -> seen.method() + " " + path;
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void pressKeysSendsOneRawKeyWithNoOverlayClear() {
        sale.pressKeys("QTY");
        assertEquals(List.of("press_keys(QTY)"), wire());
        assertTrue(marks.isEmpty(), marks.toString());
    }

    @Test
    void pressKeysRefusesASequenceRatherThanGuessingASeparator() {
        assertThrows(DeviceUnavailable.class, () -> sale.pressKeys("QTY ENTER"));
        assertTrue(wire().isEmpty());
    }

    @Test
    void pressKeyGoesThroughTheGuardAndTheOverlayClear() {
        sale.pressKey("YES");
        assertEquals(List.of("overlays@0"), marks);
        assertEquals(List.of("press_keys(YES)"), wire());
    }

    @Test
    void pressKeyRefusesAKeyTheShimCannotBuild() {
        assertThrows(DeviceUnavailable.class, () -> sale.pressKey("MAYBE"));
        assertTrue(wire().isEmpty());
    }

    @Test
    void pressEnterCarriesItsDataOrGoesBare() {
        sale.pressEnter("5");
        sale.pressEnter(null);
        assertEquals(List.of("press_enter_with_data(5)", "press_keys(ENTER)"), wire());
    }

    @Test
    void pressWithDataIsARawShimCallSoAnUnlistedKeyIsStillSent() {
        shim.on("GET", "/pos/keys", 200, "{\"named_keys\":[\"TOTAL\"]}");
        sale.pressWithData("APPROVE", "1");
        assertEquals(List.of("press_with_data(APPROVE,1)"), wire());
        assertTrue(marks.isEmpty(), marks.toString());
    }

    @Test
    void scanItemPresentsTheScannerTokenThroughTheShim() {
        sale.scanItem(new SaleRmi.ScanItem("123", 2, true, "123", 0));
        assertEquals(List.of("scan(123)"), wire());
    }

    @Test
    void mopCarriesTheAmountOrGoesBare() {
        sale.mop("CASH", "1800");
        sale.mop("0x7d", "1100");
        assertEquals(List.of("press_with_data(CASH,1800)", "press_keys(0x7d)"), wire());
    }

    @Test
    void otherMopPressesTheKeyThenThePosition() {
        sale.otherMop("0x3e", "0000", "24", "N");
        assertEquals(List.of("press_with_data(0x3e,0000)", "press_enter_with_data(24)"), wire());
    }

    @Test
    void totalPressesTotal() {
        sale.total();
        assertEquals(List.of("press_keys(TOTAL)"), wire());
    }

    @Test
    void addItemMapsTheSeamsFieldsToTheCorpusKeying() {
        sale.addItem(new SaleRmi.AddItem("7002", "N", "0", "0", "SOFT KEY", "2", "N", "N", "N", "N", "N", "N",
                "NO FILE", null));
        assertEquals(List.of("press_with_data(QTY,2)", "press_enter_with_data(7002)"), wire());
    }

    @Test
    void addItemTakesThePythonsDefaultForANullField() {
        sale.addItem(new SaleRmi.AddItem("7002", null, null, null, null, "2", null, null, null, null, null, null,
                null, null));
        assertEquals(List.of("press_with_data(QTY,2)", "press_enter_with_data(7002)"), wire());
    }

    @Test
    void addItemRunsTheScannerFileCallbackInPlaceOfThePlu() {
        sale.addItem(new SaleRmi.AddItem("0", null, null, null, null, null, null, null, null, null, null, null,
                "PLU-402.xml", () -> mark("scan")));
        assertEquals(List.of("overlays@0", "scan@0"), marks);
        assertTrue(wire().isEmpty());
    }

    @Test
    void addDeptItemMapsTheSeamsFieldsAndDefaultsTheQuantityToZero() {
        sale.addDeptItem(new SaleRmi.AddDeptItem("0x0c", "1077", null, null, null, null, null, null, null));
        assertEquals(List.of("press_with_data(0x0c,1077)"), wire());
    }

    @Test
    void addDeptItemSendsTheFullSequenceWhenEveryFieldIsGiven() {
        sale.addDeptItem(new SaleRmi.AddDeptItem("0x0c", "1077", "1567", "Y", "1", "N", "N", "0", "N"));
        assertEquals(List.of("press_with_data(QTY,1)", "press_with_data(0x0c,1077)", "press_enter_with_data(1567)"),
                wire());
    }

    @Test
    void prepayClaimsNothingUntilThePumpIsIdle() {
        sale.prepay("1", "N", "1", "500", "0x7d", () -> mark("waited"));
        assertEquals(List.of("waited@0", "overlays@0"), marks);
        assertEquals(List.of("press_with_data(PREPAY,1)", "press_with_data(0x7d,500)"), wire());
    }

    @Test
    void fuelWaitsForClaimablePausesSixSecondsThenSendsTheFuelKey() {
        sale.fuel("1", "STACKED", () -> mark("waited"));
        assertEquals(List.of("waited@0", "pause:6", "overlays@0"), marks);
        assertEquals(List.of("press_with_data(FUEL,1)"), wire());
    }

    @Test
    void fuelCurrentIsRefused() {
        assertThrows(DeviceUnavailable.class, () -> sale.fuel("1", "CURRENT", () -> mark("waited")));
        assertTrue(marks.isEmpty());
        assertTrue(wire().isEmpty());
    }

    @Test
    void refundPressesRefund() {
        sale.refund();
        assertEquals(List.of("press_keys(REFUND)"), wire());
    }

    @Test
    void voidTicketPressesVoidTicket() {
        sale.voidTicket();
        assertEquals(List.of("press_keys(VOID_TICKET)"), wire());
    }

    @Test
    void voidLineVoidsTheLineThenTheQuantityThenThePlu() {
        sale.voidLine("10035", "1");
        assertEquals(List.of("press_keys(VOID_LINE)", "press_with_data(QTY,1)", "press_enter_with_data(10035)"),
                wire());
    }

    @Test
    void errorCorrectPressesErrorCorrect() {
        sale.errorCorrect();
        assertEquals(List.of("press_keys(ERROR_CORRECT)"), wire());
    }

    @Test
    void suspendPressesSusp() {
        sale.suspend();
        assertEquals(List.of("press_keys(SUSP)"), wire());
    }
}
