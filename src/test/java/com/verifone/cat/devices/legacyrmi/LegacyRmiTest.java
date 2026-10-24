package com.verifone.cat.devices.legacyrmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.legacyrmi.LegacyRmi.DeptSale;
import com.verifone.cat.devices.legacyrmi.LegacyRmi.ItemSale;
import com.verifone.cat.devices.legacyrmi.LegacyRmi.KeyResult;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;

/**
 * The Python's {@code tests/unit/test_legacy_rmi.py}, ported. The wire is {@link FakeShim}'s recorded requests, not a
 * stub's call list: {@link #wire()} reads them back as {@code press_keys(X)}, {@code press_with_data(K,D)} and so on.
 * {@link #mark} records where the overlay hook and the scan callback fell in that wire, so ordering is checked.
 *
 * <p>Evidence about this repository only: the shim here is loopback, and nothing presses a register.
 * UNVERIFIED AGAINST HARDWARE (Java port).
 */
class LegacyRmiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The Python stub's named keys, which the real lab shim's list is a superset of or disjoint from by design. */
    private static final List<String> SUPPORTED = List.of(
            "APPROVE", "CASH", "CREDIT", "ENTER", "ERROR_CORRECT", "EXIT", "KEY_LANE_CLOSE_VIEW_EXIT_BUTTON",
            "KEY_SCO_CASHIER_ENTRY", "LOG_OUT", "NO", "NO_SALE", "OTHER_FUEL", "OTHER_FUNC", "PRINT_REPORT", "QTY",
            "REFUND", "SAFE_DROP", "STOP", "SUSP", "TAX_EXEMPT", "TICKET_PRINT", "TOTAL", "VOID_LINE", "VOID_TICKET",
            "YES");

    private static final String PRESS_REPLY = "{\"client\":\"test\",\"requested\":[],\"pressed\":[],"
            + "\"all_processed\":true}";
    private static final String PENDING_REPLY = "{\"key\":\"ENTER\",\"processed\":true,\"elapsed_ms\":10}";

    private final List<String> marks = new ArrayList<>();
    private FakeShim shim;
    private LegacyRmi rmi;

    @BeforeEach
    void setUp() {
        shim = new FakeShim()
                .on("GET", "/pos/keys", 200, keysReply(SUPPORTED))
                .on("POST", "/pos/keys", 200, PRESS_REPLY)
                .on("POST", "/pos/keys/enter", 200, PENDING_REPLY)
                .on("POST", "/pos/keys/pending", 200, PENDING_REPLY);
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(5));
        rmi = new LegacyRmi(client, () -> mark("overlays"), d -> marks.add("pause:" + d.toSeconds()));
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    // -- helpers ---------------------------------------------------------------------------------

    private void mark(String name) {
        marks.add(name + "@" + wire().size());
    }

    /** The shim's POST requests so far, as the Python's stub recorded them. GETs (the supported-key list) are not sends. */
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
                case "/pos/keys" -> "press_keys(" + String.join(",", texts(body.get("keys"))) + ")";
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

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static String keysReply(List<String> names) {
        StringBuilder quoted = new StringBuilder();
        for (String name : names) {
            quoted.append(quoted.length() == 0 ? "" : ",").append('"').append(name).append('"');
        }
        return "{\"named_keys\":[" + quoted + "],\"rejected_names\":[]}";
    }

    /** An item with the Python's add_item defaults, overridden by {@code kv} name/value pairs. */
    private static ItemSale item(String plu, String... kv) {
        return item(plu, null, kv);
    }

    private static ItemSale item(String plu, Runnable scan, String... kv) {
        ItemSale d = ItemSale.of(plu);
        Map<String, String> f = new HashMap<>();
        f.put("taxExempt", d.taxExempt());
        f.put("modifier", d.modifier());
        f.put("discount", d.discount());
        f.put("discountKey", d.discountKey());
        f.put("quantity", d.quantity());
        f.put("idc", d.idc());
        f.put("priceOver", d.priceOver());
        f.put("pluNotFoundPrice", d.pluNotFoundPrice());
        f.put("usingMenu", d.usingMenu());
        f.put("selectSoftkey", d.selectSoftkey());
        f.put("choice", d.choice());
        f.put("selectFile", d.selectFile());
        for (int i = 0; i < kv.length; i += 2) {
            f.put(kv[i], kv[i + 1]);
        }
        return new ItemSale(plu, f.get("taxExempt"), f.get("modifier"), f.get("discount"), f.get("discountKey"),
                f.get("quantity"), f.get("idc"), f.get("priceOver"), f.get("pluNotFoundPrice"), f.get("usingMenu"),
                f.get("selectSoftkey"), f.get("choice"), f.get("selectFile"), scan);
    }

    /** A department item with the Python's add_dept_item defaults, overridden by {@code kv}. */
    private static DeptSale dept(String key, String amount, String... kv) {
        DeptSale d = DeptSale.of(key, amount);
        Map<String, String> f = new HashMap<>();
        f.put("departmentNo", d.departmentNo());
        f.put("otherDepartment", d.otherDepartment());
        f.put("quantity", d.quantity());
        f.put("pack", d.pack());
        f.put("taxExempt", d.taxExempt());
        f.put("discount", d.discount());
        f.put("idc", d.idc());
        for (int i = 0; i < kv.length; i += 2) {
            f.put(kv[i], kv[i + 1]);
        }
        return new DeptSale(key, amount, f.get("departmentNo"), f.get("otherDepartment"), f.get("quantity"),
                f.get("pack"), f.get("taxExempt"), f.get("discount"), f.get("idc"));
    }

    // -- ENTER -----------------------------------------------------------------------------------

    @Test
    void pressEnterWithNoDataSendsABareEnter() {
        KeyResult result = rmi.pressEnter(null);
        assertEquals(List.of("press_keys(ENTER)"), wire());
        assertEquals("ENTER", result.key());
    }

    @Test
    void pressEnterWithZeroSendsABareEnter() {
        rmi.pressEnter("0");
        assertEquals(List.of("press_keys(ENTER)"), wire());
    }

    @Test
    void pressEnterWithDataReachesTheDataCarryingRoute() {
        KeyResult result = rmi.pressEnter("123");
        assertEquals(List.of("press_enter_with_data(123)"), wire());
        assertEquals("123", result.data());
        assertTrue(result.processed());
    }

    @Test
    void pendingEnterCarriesZeroAndSendsAnEmptyValueBare() {
        rmi.pendingEnter("0");
        assertEquals(List.of("press_enter_with_data(0)"), wire());
        shim.seen.clear();
        rmi.pendingEnter("");
        assertEquals(List.of("press_keys(ENTER)"), wire());
    }

    // -- MOP -------------------------------------------------------------------------------------

    @Test
    void anOtherMopCodeCarriesTheAmount() {
        rmi.mop("0x3e", "1000");
        assertEquals(List.of("press_with_data(0x3e,1000)"), wire());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREDIT", "0x7d"})
    void creditGoesBare(String mop) {
        rmi.mop(mop, "1100");
        assertEquals(List.of("press_keys(" + mop + ")"), wire());
    }

    @Test
    void cashOfZeroGoesBareAndCashWithAnAmountCarriesIt() {
        rmi.mop("0x7b", "0");
        rmi.mop("CASH", "2000");
        assertEquals(List.of("press_keys(0x7b)", "press_with_data(CASH,2000)"), wire());
    }

    // -- add_item --------------------------------------------------------------------------------

    @Test
    void petroCore61367FieldsSendExactlyTwoTokens() {
        rmi.addItem(item("7002", "quantity", "2"));
        assertEquals(List.of("press_with_data(QTY,2)", "press_enter_with_data(7002)"), wire());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "0"})
    void aQuantityOfOneOrZeroSendsNoQtyToken(String quantity) {
        rmi.addItem(item("7002", "quantity", quantity));
        assertEquals(List.of("press_enter_with_data(7002)"), wire());
    }

    @Test
    void aHexPluIsABareSoftkey() {
        rmi.addItem(item("0x4a"));
        assertEquals(List.of("press_keys(0x4a)"), wire());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0x4", "0x4a2", "4a", "0000000009123"})
    void anythingThatIsNotATwoDigitHexCodeIsKeyed(String plu) {
        rmi.addItem(item(plu));
        assertEquals(List.of("press_enter_with_data(" + plu + ")"), wire());
    }

    @Test
    void taxQuantityIdcAndDiscountFireInPatsOrder() {
        rmi.addItem(item("7002", "taxExempt", "Y", "quantity", "2", "idc", "3", "discount", "50"));
        assertEquals(List.of(
                "press_keys(TAX_EXEMPT)",
                "press_with_data(QTY,2)",
                "press_enter_with_data(7002)",
                "press_enter_with_data(3)",
                "press_with_data(DISC,50)"), wire());
    }

    @Test
    void aHexDiscountKeyCarriesTheDiscountItself() {
        rmi.addItem(item("7002", "discountKey", "0x5a", "discount", "50"));
        assertEquals(List.of("press_enter_with_data(7002)", "press_with_data(0x5a,50)"), wire());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0"})
    void anInertDiscountSendsNoDiscToken(String discount) {
        rmi.addItem(item("7002", "discount", discount));
        assertEquals(List.of("press_enter_with_data(7002)"), wire());
    }

    @Test
    void aMenuSelectionPressesTheSoftkeyThenTheChoice() {
        rmi.addItem(item("7002", "usingMenu", "Y", "selectSoftkey", "0x2b", "choice", "4"));
        assertEquals(List.of("press_keys(0x2b)", "press_enter_with_data(4)"), wire());
    }

    @Test
    void theNotFoundPriceRidesATrailingEnter() {
        rmi.addItem(item("7002", "pluNotFoundPrice", "199"));
        assertEquals(List.of("press_enter_with_data(7002)", "press_enter_with_data(199)"), wire());
    }

    @Test
    void aDecimalPluPicksItsModifierWithASecondEnter() {
        rmi.addItem(item("9112", "modifier", "1"));
        assertEquals(List.of("press_enter_with_data(9112)", "press_enter_with_data(1)"), wire());
    }

    @Test
    void aSoftkeyPluSendsTheModifierAsModify() {
        rmi.addItem(item("0x2b", "modifier", "2"));
        assertEquals(List.of("press_keys(0x2b)", "press_with_data(MODIFY,2)"), wire());
    }

    @Test
    void aPriceOverridePressesPriceOverCarryingTheField() {
        rmi.addItem(item("1000014", "priceOver", "Y"));
        assertEquals(List.of("press_enter_with_data(1000014)", "press_with_data(PRICE_OVER,Y)"), wire());
    }

    @Test
    void aScannerFileWithoutAScanCallbackRaises() {
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class,
                () -> rmi.addItem(item("7002", "selectFile", "scanner.xml")));
        assertTrue(e.getMessage().contains("scanItem"), e.getMessage());
        assertTrue(wire().isEmpty());
    }

    @Test
    void aMenuSelectionWithANonHexSoftkeyRaisesRatherThanDroppingTheItem() {
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class,
                () -> rmi.addItem(item("7002", "usingMenu", "Y", "selectSoftkey", "N")));
        assertTrue(e.getMessage().contains("silently drop"), e.getMessage());
    }

    @Test
    void aScannerFileIsScannedWhereThePluWouldBeKeyed() {
        rmi.addItem(item("0", () -> mark("scan"), "quantity", "2", "idc", "1", "selectFile", "PLU-402.xml"));
        assertEquals(List.of("overlays@0", "scan@1"), marks);
        assertEquals(List.of("press_with_data(QTY,2)", "press_enter_with_data(1)"), wire());
    }

    @Test
    void itReturnsOneResponsePerKeySent() {
        List<KeyResult> sent = rmi.addItem(item("7002", "quantity", "2"));
        assertEquals(List.of("QTY", "ENTER"), sent.stream().map(KeyResult::key).toList());
        assertEquals(List.of("2", "7002"), sent.stream().map(KeyResult::data).toList());
    }

    // -- add_dept_item ---------------------------------------------------------------------------

    @Test
    void petroCore61335FieldsSendExactlyThreeTokens() {
        rmi.addDeptItem(dept("0x0c", "1077", "departmentNo", "1567", "otherDepartment", "Y", "quantity", "1"));
        assertEquals(List.of(
                "press_with_data(QTY,1)",
                "press_with_data(0x0c,1077)",
                "press_enter_with_data(1567)"), wire());
    }

    @Test
    void aQuantityOfZeroIsTheOnlyOneSkipped() {
        rmi.addDeptItem(dept("0x0c", "1077", "quantity", "0"));
        assertEquals(List.of("press_with_data(0x0c,1077)"), wire());
    }

    @Test
    void otherDepartmentNSendsNoEnter() {
        rmi.addDeptItem(dept("0x0c", "1077", "departmentNo", "1567"));
        assertEquals(List.of("press_with_data(0x0c,1077)"), wire());
    }

    @Test
    void otherDepartmentYWithNoNumberSendsNoEnter() {
        rmi.addDeptItem(dept("0x0c", "1077", "otherDepartment", "Y"));
        assertEquals(List.of("press_with_data(0x0c,1077)"), wire());
    }

    @Test
    void taxPackDiscountAndIdcFireInPatsOrder() {
        rmi.addDeptItem(dept("0x0c", "1077", "departmentNo", "1567", "otherDepartment", "Y", "quantity", "2",
                "pack", "6", "taxExempt", "Y", "discount", "50", "idc", "3"));
        assertEquals(List.of(
                "press_keys(TAX_EXEMPT)",
                "press_with_data(QTY,2)",
                "press_with_data(QTY,6)",
                "press_with_data(0x0c,1077)",
                "press_enter_with_data(1567)",
                "press_with_data(DISC,50)",
                "press_enter_with_data(3)"), wire());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0"})
    void anInertDepartmentDiscountSendsNoDiscToken(String discount) {
        rmi.addDeptItem(dept("0x0c", "1077", "discount", discount));
        assertEquals(List.of("press_with_data(0x0c,1077)"), wire());
    }

    @Test
    void taxExemptIsABareKeyAndIsValidatedLocally() {
        rmi.addDeptItem(dept("0x0c", "1077", "taxExempt", "y"));
        assertEquals(List.of("press_keys(TAX_EXEMPT)", "press_with_data(0x0c,1077)"), wire());
    }

    @Test
    void anUnsupportedTaxExemptKeyIsRefusedBeforeAnythingIsSent() {
        shim.on("GET", "/pos/keys", 200, keysReply(List.of("ENTER", "TOTAL")));
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class,
                () -> rmi.addDeptItem(dept("0x0c", "1077", "taxExempt", "Y")));
        assertTrue(e.getMessage().contains("TAX_EXEMPT"), e.getMessage());
        assertTrue(wire().isEmpty());
    }

    @Test
    void itReturnsOneResponsePerKeySentForADeptItem() {
        List<KeyResult> sent = rmi.addDeptItem(dept("0x0c", "1077", "departmentNo", "1567",
                "otherDepartment", "Y", "quantity", "1"));
        assertEquals(List.of("QTY", "0x0c", "ENTER"), sent.stream().map(KeyResult::key).toList());
        assertEquals(List.of("1", "1077", "1567"), sent.stream().map(KeyResult::data).toList());
    }

    // -- single keys -----------------------------------------------------------------------------

    @Test
    void theSingleKeyMethodsEachPressTheirOwnKey() {
        Map<String, Supplier<KeyResult>> presses = new java.util.LinkedHashMap<>();
        presses.put("TOTAL", rmi::total);
        presses.put("APPROVE", rmi::approve);
        presses.put("REFUND", rmi::refund);
        presses.put("LOG_OUT", rmi::logout);
        presses.put("PRINT_REPORT", rmi::printReport);
        presses.put("QTY", rmi::quantity);
        presses.put("NO_SALE", rmi::noSale);
        presses.put("VOID_LINE", rmi::voidLine);
        presses.put("VOID_TICKET", rmi::voidTicket);
        presses.put("SUSP", rmi::suspend);
        presses.put("ERROR_CORRECT", rmi::errorCorrect);
        presses.put("EXIT", rmi::exitKey);
        presses.put("KEY_SCO_CASHIER_ENTRY", rmi::scoCashierEntry);
        for (Map.Entry<String, Supplier<KeyResult>> press : presses.entrySet()) {
            shim.seen.clear();
            KeyResult result = press.getValue().get();
            assertEquals(press.getKey(), result.key());
            assertEquals(List.of("press_keys(" + press.getKey() + ")"), wire());
        }
    }

    @Test
    void printFlashReportSendsExitThenThreeDataEntersThenThreeExits() {
        rmi.printFlashReport("1", "20", "4");
        assertEquals(List.of(
                "press_keys(EXIT)",
                "press_enter_with_data(1)",
                "press_enter_with_data(20)",
                "press_enter_with_data(4)",
                "press_keys(EXIT)",
                "press_keys(EXIT)",
                "press_keys(EXIT)"), wire());
    }

    @Test
    void printFlashReportReturnsAllSevenPressResultsInOrder() {
        List<KeyResult> results = rmi.printFlashReport("1", "20", "1");
        assertEquals(7, results.size());
        assertEquals("1", results.get(1).data());
        assertEquals("20", results.get(2).data());
        assertEquals("1", results.get(3).data());
    }

    @Test
    void aZeroMenuIsCarriedAsPendingTokenCarriesIt() {
        rmi.printFlashReport("1", "20", "0");
        assertEquals("press_enter_with_data(0)", wire().get(3));
    }

    @Test
    void aKeyCodeIsSentAsWrittenWithoutTheNameCheck() {
        rmi.pressKey("0x4a");
        assertEquals(List.of("press_keys(0x4a)"), wire());
    }

    @Test
    void aNameIsCheckedAgainstTheShimCaseInsensitively() {
        rmi.pressKey("yes");
        assertEquals(List.of("press_keys(yes)"), wire());
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class, () -> rmi.pressKey("MAYBE"));
        assertTrue(e.getMessage().contains("MAYBE"), e.getMessage());
    }

    @Test
    void voidLineItemIsTheBareKeyThenTheQuantityThenThePlu() {
        rmi.voidLineItem("10035", "1");
        assertEquals(List.of(
                "press_keys(VOID_LINE)",
                "press_with_data(QTY,1)",
                "press_enter_with_data(10035)"), wire());
    }

    @Test
    void aZeroQuantityAndAnEmptyPluSendNothingMore() {
        rmi.voidLineItem("", "0");
        assertEquals(List.of("press_keys(VOID_LINE)"), wire());
    }

    // -- key guard ----------------------------------------------------------------------------------

    @Test
    void anUnsupportedKeyRaisesUnavailableNamingTheSupportedOnes() {
        shim.on("GET", "/pos/keys", 200, keysReply(List.of("TOTAL")));
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class, () -> rmi.approve());
        String message = e.getMessage();
        assertTrue(message.contains("LegacyRmi.approve()"), message);
        assertTrue(message.contains("APPROVE"), message);
        assertTrue(message.contains("§5.2/§6.2"), message);
        assertTrue(message.contains("TOTAL"), message);
        assertTrue(wire().isEmpty());
    }

    @Test
    void theGuardValidatesBeforeAnythingIsSent() {
        shim.on("GET", "/pos/keys", 200, keysReply(List.of()));
        assertThrows(DeviceUnavailable.class, () -> rmi.total());
        assertTrue(wire().isEmpty());
    }

    @Test
    void theErrorMessageListsWhatIsSupported() {
        shim.on("GET", "/pos/keys", 200, keysReply(List.of("ENTER", "EXIT")));
        String message = assertThrows(DeviceUnavailable.class, () -> rmi.total()).getMessage();
        assertTrue(message.contains("ENTER") && message.contains("EXIT"), message);
    }

    @Test
    void theLabShimsOwnReplyRejectsApproveByName() {
        shim.on("GET", "/pos/keys", 200, FakeShim.fixture("keys.json"));
        assertThrows(DeviceUnavailable.class, () -> rmi.approve());
        assertEquals("TOTAL", rmi.total().key());
        assertEquals(List.of("press_keys(TOTAL)"), wire());
    }

    @Test
    void theSupportedKeysAreReadOnceAndKept() {
        rmi.total();
        rmi.approve();
        long gets = shim.seen.stream()
                .filter(seen -> "GET".equals(seen.method()) && seen.pathAndQuery().startsWith("/pos/keys"))
                .count();
        assertEquals(1, gets);
    }

    // -- integration -------------------------------------------------------------------------------

    @Test
    void multipleCallsInSequenceRecordEveryPress() {
        rmi.total();
        rmi.approve();
        rmi.refund();
        assertEquals(List.of("press_keys(TOTAL)", "press_keys(APPROVE)", "press_keys(REFUND)"), wire());
    }

    @Test
    void toStringNamesTheClass() {
        assertTrue(rmi.toString().contains("LegacyRmi"));
    }

    @Test
    void everyKeyInTheDefaultSetSucceeds() {
        rmi.pressEnter(null);
        rmi.total();
        rmi.approve();
        rmi.refund();
        rmi.logout();
        rmi.printReport();
        rmi.quantity();
        rmi.noSale();
        rmi.voidLine();
        rmi.voidTicket();
        rmi.suspend();
        rmi.errorCorrect();
        assertFalse(wire().isEmpty());
    }

    // -- before_action ----------------------------------------------------------------------------

    @Test
    void addItemClearsOverlaysBeforeItsFirstToken() {
        rmi.addItem(item("7002", "quantity", "2"));
        assertEquals(List.of("overlays@0"), marks);
        assertEquals(List.of("press_with_data(QTY,2)", "press_enter_with_data(7002)"), wire());
    }

    @Test
    void addDeptItemClearsOverlaysFirstAndOnlyOnce() {
        rmi.addDeptItem(dept("0x0c", "1077", "departmentNo", "1567"));
        assertEquals(List.of("overlays@0"), marks);
    }

    @Test
    void eachActionClearsOverlaysOnceThenSends() {
        List<Runnable> actions = List.of(
                () -> rmi.total(),
                () -> rmi.mop("CASH", "1800"),
                () -> rmi.mop("0x7d", "0000"),
                () -> rmi.pressEnter("03"),
                () -> rmi.pressEnter(null));
        List<List<String>> expected = List.of(
                List.of("press_keys(TOTAL)"),
                List.of("press_with_data(CASH,1800)"),
                List.of("press_keys(0x7d)"),
                List.of("press_enter_with_data(03)"),
                List.of("press_keys(ENTER)"));
        for (int i = 0; i < actions.size(); i++) {
            marks.clear();
            shim.seen.clear();
            actions.get(i).run();
            assertEquals(List.of("overlays@0"), marks, "action " + i);
            assertEquals(expected.get(i), wire(), "action " + i);
        }
    }

    @Test
    void anUnsupportedKeyRaisesBeforeTouchingTheScreen() {
        shim.on("GET", "/pos/keys", 200, keysReply(List.of()));
        assertThrows(DeviceUnavailable.class, () -> rmi.total());
        assertTrue(marks.isEmpty(), marks.toString());
        assertTrue(wire().isEmpty());
    }

    // -- other_mop -------------------------------------------------------------------------------

    @Test
    void theAmountRidesTheKeyAndThePositionRidesEnter() {
        rmi.otherMop("0x3e", "0000", "24", "N");
        assertEquals(List.of("press_with_data(0x3e,0000)", "press_enter_with_data(24)"), wire());
    }

    @ParameterizedTest
    @CsvSource({"MOBILE,N", "0x3e,Y"})
    void unportedOtherMopBranchesRaiseBeforeSending(String mop, String specialDiscount) {
        assertThrows(DeviceUnavailable.class, () -> rmi.otherMop(mop, "0000", "24", specialDiscount));
        assertTrue(wire().isEmpty());
    }

    // -- fuel ------------------------------------------------------------------------------------

    @Test
    void stackedWaitsPausesAsPatsDoesThenSendsThePumpOnFuel() {
        rmi.fuel("1", "STACKED", () -> mark("waited"));
        assertEquals(List.of("waited@0", "pause:6", "overlays@0"), marks);
        assertEquals(List.of("press_with_data(FUEL,1)"), wire());
    }

    @Test
    void currentRaisesBeforeWaitingOrSending() {
        assertThrows(DeviceUnavailable.class, () -> rmi.fuel("1", "CURRENT", () -> mark("waited")));
        assertTrue(marks.isEmpty());
        assertTrue(wire().isEmpty());
    }

    // -- prepay ----------------------------------------------------------------------------------

    @Test
    void anAmountPresetCarriesTheAmountOnTheMopKey() {
        rmi.prepay("1", "N", "1", "500", "0x7d", () -> mark("waited"));
        assertEquals(List.of("waited@0", "overlays@0"), marks);
        assertEquals(List.of("press_with_data(PREPAY,1)", "press_with_data(0x7d,500)"), wire());
    }

    @Test
    void aGradePresetIsAQuantityAndAProductOfZeroSendsNothingMore() {
        rmi.prepay("4", "Y", "0", "500", "0x7d", () -> { });
        assertEquals(List.of("press_with_data(PREPAY,4)", "press_keys(QTY)", "press_enter_with_data(500)"), wire());
    }

    @Test
    void aGradePresetWithAProductEntersIt() {
        rmi.prepay("4", "Y", "2", "500", "0x7d", () -> { });
        assertEquals("press_enter_with_data(2)", wire().get(wire().size() - 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", ""})
    void noAmountSendsOnlyThePrepayKey(String amount) {
        rmi.prepay("1", "N", "1", amount, "0x7d", () -> { });
        assertEquals(List.of("press_with_data(PREPAY,1)"), wire());
    }

    // -- other fuel key ------------------------------------------------------------------------------

    @Test
    void theKeyGoesBareThenTheOptionRidesEnter() {
        rmi.otherFuelKey("0x08", "4");
        assertEquals(List.of("press_keys(0x08)", "press_enter_with_data(4)"), wire());
    }

    @Test
    void aBlankOptionSendsOnlyTheKey() {
        rmi.otherFuelKey("0x08", " ");
        assertEquals(List.of("press_keys(0x08)"), wire());
    }
}
