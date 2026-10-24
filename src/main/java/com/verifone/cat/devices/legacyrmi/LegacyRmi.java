package com.verifone.cat.devices.legacyrmi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.posshim.ShimReplies.KeyPressResult;
import com.verifone.cat.posshim.ShimReplies.PendingKeyResult;
import com.verifone.cat.posshim.ShimReplies.ScanResult;

/**
 * Legacy RMI key-drive actions through the register's pos-shim: the Java counterpart of the Python CAT's
 * {@code petrotest.devices.legacy_rmi.LegacyRmi}, branch for branch.
 *
 * <p>These press the POS keyboard the way PATS's {@code POSDevice} does, bypassing the POS screen. Each action
 * runs {@code beforeAction} first (PATS's {@code preProcessPOSAction}: clear the overlays that would take the
 * first key). Every named key is checked against the shim's own supported set before it is sent, so a typo is
 * refused locally. Anything the Python raises {@link DeviceUnavailable} for, this raises too (hard rule 5).
 *
 * <p>Key presses on a live POS: the rig owner's go-ahead applies. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class LegacyRmi {

    /**
     * {@code POSDevice.fuel}'s pause between a claimable pump and FUEL: two of PATS's
     * {@code fuelsim.exception.retry.interval}, 3000 ms in the deployed {@code automn_app_config.prop}.
     */
    public static final Duration FUEL_CLAIM_PAUSE = Duration.ofSeconds(6);

    private static final Pattern HEXA = Pattern.compile("0[xX][0-9a-fA-F]{2}");
    private static final String REFERENCE = "§5.2/§6.2";

    /**
     * One key press as the shim reported it. {@code data} is what the press carried, or {@code null} for a bare key;
     * {@code processed} is the shim's own callback flag, so a key the POS ignored reads {@code false}.
     */
    public record KeyResult(String key, String data, boolean processed) {
    }

    private final PosShimClient shim;
    private final Runnable beforeAction;
    private final Consumer<Duration> pause;
    private Set<String> supported;

    public LegacyRmi(PosShimClient shim) {
        this(shim, null, LegacyRmi::park);
    }

    public LegacyRmi(PosShimClient shim, Runnable beforeAction) {
        this(shim, beforeAction, LegacyRmi::park);
    }

    /**
     * @param shim         an active client, never null
     * @param beforeAction run before each action; {@code null} runs nothing
     * @param pause        the bounded pause {@link #fuel} takes; a test replaces it
     */
    public LegacyRmi(PosShimClient shim, Runnable beforeAction, Consumer<Duration> pause) {
        this.shim = shim;
        this.beforeAction = beforeAction == null ? () -> { } : beforeAction;
        this.pause = pause;
    }

    @Override
    public String toString() {
        return "<LegacyRmi via " + shim.baseUri() + ">";
    }

    /** ENTER: bare when {@code data} is null or "0" (PATS's {@code PRESS_ENTER}), else ENTER carrying the data. */
    public KeyResult pressEnter(String data) {
        if (data != null && !"0".equals(data)) {
            beforeAction.run();
            return enter(data);
        }
        return press("press_enter", "ENTER");
    }

    /**
     * ENTER carrying {@code data}, "0" included: PATS's direct {@code pendingToken("ENTER", data)}. A null or empty
     * value is a bare ENTER, because in PATS it is one (the shim refuses an empty payload).
     */
    public KeyResult pendingEnter(String data) {
        if (data == null || data.isEmpty()) {
            return press("pending_enter", "ENTER");
        }
        beforeAction.run();
        return enter(data);
    }

    /** Tender with a MOP key: PATS's {@code MOP}, {@code POSDevice.cashMop}, branch for branch. */
    public KeyResult mop(String mop, String amount) {
        beforeAction.run();
        String key = mop.toLowerCase(Locale.ROOT);
        boolean bare = key.equals("credit") || key.equals("0x7d")
                || ((key.equals("cash") || key.equals("0x7b")) && Integer.parseInt(amount) == 0);
        if (bare) {
            if (!key.startsWith("0x")) {
                requireKey("mop", mop.toUpperCase(Locale.ROOT));
            }
            return sendKey(mop);
        }
        return pending(mop, amount);
    }

    /**
     * Tender from the Other-MOP list: PATS's {@code OTHER_MOP}, {@code POSDevice.cashOtherMop}, with
     * {@code SPECIALDISCOUNT=N} only. The list entry is picked by its number, so the position rides ENTER.
     */
    public List<KeyResult> otherMop(String mop, String amount, String position, String specialDiscount) {
        if (!isHexa(mop) || !"N".equals(upper(specialDiscount))) {
            throw new DeviceUnavailable("LegacyRmi.otherMop(" + mop + ", special_discount=" + specialDiscount + ")",
                    REFERENCE + ": Only cashOtherMop's hex-key, SPECIALDISCOUNT=N branch is ported.");
        }
        beforeAction.run();
        List<KeyResult> sent = new ArrayList<>();
        sent.add(pending(mop, amount));
        sent.add(enter(position));
        return sent;
    }

    /** Ring an item: PATS's {@code POSDevice.addItem}, branch for branch. See {@link ItemSale}. */
    public List<KeyResult> addItem(ItemSale item) {
        beforeAction.run();
        List<KeyResult> sent = new ArrayList<>();
        if (isY(item.taxExempt())) {
            requireKey("add_item", "TAX_EXEMPT");
            sent.add(sendKey("TAX_EXEMPT"));
        }
        if (!item.quantity().equals("1") && !item.quantity().equals("0")) {
            sent.add(pending("QTY", item.quantity()));
        }

        if (!item.usingMenu().equals("N")) {
            if (!isHexa(item.selectSoftkey())) {
                throw new DeviceUnavailable("LegacyRmi.addItem() with using_menu=" + item.usingMenu()
                        + " and select_softkey=" + item.selectSoftkey(),
                        REFERENCE + ": PATS rings no item at all for a menu selection whose softkey is not a 0xNN"
                                + " code, so translating it would silently drop the item.");
            }
            sent.add(sendKey(item.selectSoftkey()));
            sent.add(enter(item.choice()));
        } else if (item.selectFile().endsWith(".xml") && item.scan() != null) {
            item.scan().run();
        } else if (item.selectFile().endsWith(".xml")) {
            throw new DeviceUnavailable("LegacyRmi.addItem() with select_file=" + item.selectFile(),
                    REFERENCE + ": A scanner-data file is PATS's addScannerItem, not addPlu. Present the barcode"
                            + " through rawScanItem (PosShimClient.scanItem) instead.");
        } else if (isHexa(item.plu())) {
            sent.add(sendKey(item.plu()));
        } else {
            sent.add(enter(item.plu()));
        }

        if (!item.idc().equals("N")) {
            sent.add(enter(item.idc()));
        }
        if (!item.modifier().equals("0")) {
            // modifyTopaz: a 0xNN PLU takes MODIFY; a decimal PLU picks its modifier with a second ENTER.
            sent.add(isHexa(item.plu()) ? pending("MODIFY", item.modifier()) : enter(item.modifier()));
        }
        if (!item.priceOver().equals("N")) {
            sent.add(pending("PRICE_OVER", item.priceOver()));
        }
        if (isHexa(item.discountKey())) {
            sent.add(pending(item.discountKey(), item.discount()));
        } else if (!item.discount().isEmpty() && !item.discount().equals("0")) {
            sent.add(pending("DISC", item.discount()));
        }
        if (!item.pluNotFoundPrice().equals("N")) {
            sent.add(enter(item.pluNotFoundPrice()));
        }
        return sent;
    }

    /** Ring a department item: PATS's {@code POSDevice.addDeptItem}, branch for branch. See {@link DeptSale}. */
    public List<KeyResult> addDeptItem(DeptSale dept) {
        beforeAction.run();
        List<KeyResult> sent = new ArrayList<>();
        if (isY(dept.taxExempt())) {
            requireKey("add_dept_item", "TAX_EXEMPT");
            sent.add(sendKey("TAX_EXEMPT"));
        }
        if (!dept.quantity().equals("0")) {
            sent.add(pending("QTY", dept.quantity()));
        }
        if (!dept.pack().equals("N")) {
            sent.add(pending("QTY", dept.pack()));
        }
        sent.add(pending(dept.deptKey(), dept.amount()));
        if (dept.otherDepartment().equals("Y") && !dept.departmentNo().isEmpty()) {
            sent.add(enter(dept.departmentNo()));
        }
        if (!dept.discount().isEmpty() && !dept.discount().equals("0")) {
            sent.add(pending("DISC", dept.discount()));
        }
        if (!dept.idc().equals("N")) {
            sent.add(enter(dept.idc()));
        }
        return sent;
    }

    /**
     * Claim a pump's sale: PATS's {@code FUEL}, {@code POSDevice.fuel}. {@code untilClaimable} stands in for the
     * poll until the pump reads IDLEOFF; then {@link #FUEL_CLAIM_PAUSE}, then {@code FUEL} carrying the pump.
     * {@code CURRENT} raises: it would send {@code pendingToken(FUEL, "")}, which the shim cannot carry.
     */
    public KeyResult fuel(String pump, String option, Runnable untilClaimable) {
        if (!"STACKED".equals(upper(option))) {
            throw new DeviceUnavailable("LegacyRmi.fuel(option=" + option + ")",
                    REFERENCE + ": CURRENT sends pendingToken(FUEL, \"\"); the shim's pending route needs data.");
        }
        untilClaimable.run();
        pause.accept(FUEL_CLAIM_PAUSE);
        beforeAction.run();
        return pending("FUEL", pump);
    }

    /** Preset a pump: PATS's {@code PREPAY}, {@code POSDevice.prePay}, branch for branch. */
    public List<KeyResult> prepay(String pump, String grade, String fuelProduct, String amount, String mop,
            Runnable untilIdle) {
        untilIdle.run();
        beforeAction.run();
        List<KeyResult> sent = new ArrayList<>();
        sent.add(pending("PREPAY", pump));
        if (!grade.equals("N")) {
            sent.add(sendKey("QTY"));
            sent.add(enter(amount));
            if (!fuelProduct.equals("0")) {
                sent.add(enter(fuelProduct));
            }
        } else if (!amount.isEmpty() && !amount.equals("0")) {
            sent.add(pending(mop, amount));
        }
        return sent;
    }

    /** PATS's {@code OTHER_FUEL_KEY}, {@code POSDevice.otherFuelKey}: the key bare, then the option on ENTER. */
    public List<KeyResult> otherFuelKey(String key, String option) {
        beforeAction.run();
        List<KeyResult> sent = new ArrayList<>();
        sent.add(sendKey(key));
        if (!option.isBlank()) {
            sent.add(enter(option));
        }
        return sent;
    }

    /**
     * Drives Functions, Reports, Flash Reports: {@code POSDevice.printFlashReport}. EXIT, the three menu numbers
     * each as {@code pendingToken(ENTER, value)}, then EXIT three times to back out.
     */
    public List<KeyResult> printFlashReport(String mainMenuNo, String functionsMenuNo, String flashReportsMenuNo) {
        List<KeyResult> sent = new ArrayList<>();
        sent.add(exitKey());
        sent.add(pendingEnter(mainMenuNo));
        sent.add(pendingEnter(functionsMenuNo));
        sent.add(pendingEnter(flashReportsMenuNo));
        sent.add(exitKey());
        sent.add(exitKey());
        sent.add(exitKey());
        return sent;
    }

    /**
     * A bare key: PATS's {@code unpendingToken(key)}, as {@code CONFIRMATION} and {@code POS_KEYBOARD_EVENT} send it.
     * A {@code 0xNN} code goes as written; anything else is a key name, checked against the shim first.
     */
    public KeyResult pressKey(String key) {
        if (!isHexa(key)) {
            requireKey("press_key", key.strip().toUpperCase(Locale.ROOT));
        }
        beforeAction.run();
        return sendKey(key);
    }

    /**
     * Void a line: PATS's {@code VOID_LINE}, {@code POSDevice.voidline}. The bare key, then the quantity unless it is
     * {@code "0"}, then the PLU on ENTER unless it is empty.
     */
    public List<KeyResult> voidLineItem(String plu, String quantity) {
        List<KeyResult> sent = new ArrayList<>();
        sent.add(press("void_line_item", "VOID_LINE"));
        if (!quantity.equals("0")) {
            sent.add(pending("QTY", quantity));
        }
        if (!plu.isEmpty()) {
            sent.add(enter(plu));
        }
        return sent;
    }

    /**
     * A key press with no guard and no overlay clear: the Python's {@code run.rmi.shim.press_keys}, which the sale
     * family calls directly. Its callers clear the overlays themselves.
     */
    public KeyResult pressRawKey(String key) {
        return sendKey(key);
    }

    /**
     * A key carrying data with no guard and no overlay clear: the Python's {@code run.rmi.shim.press_with_data}.
     */
    public KeyResult pressRawWithData(String key, String data) {
        return pending(key, data);
    }

    /**
     * A barcode scan presented directly: the Python's {@code run.rmi.shim.scan_item}. No overlay clear, no guard.
     */
    public ScanResult rawScanItem(String scanData, int scanDataType, boolean decodeData, String label, int status) {
        return shim.scanItem(scanData, scanDataType, decodeData, label, status);
    }

    /** PATS's {@code TOTAL} key. */
    public KeyResult total() {
        return press("total", "TOTAL");
    }

    /** The APPROVE key. The lab shim rejects it by name (its {@code rejected_names}). */
    public KeyResult approve() {
        return press("approve", "APPROVE");
    }

    public KeyResult refund() {
        return press("refund", "REFUND");
    }

    public KeyResult logout() {
        return press("logout", "LOG_OUT");
    }

    /** Opens a cashier session on a self-checkout lane: PATS's {@code CASHIER_LOGIN}. */
    public KeyResult scoCashierEntry() {
        return press("sco_cashier_entry", "KEY_SCO_CASHIER_ENTRY");
    }

    public KeyResult printReport() {
        return press("print_report", "PRINT_REPORT");
    }

    /** The QTY (quantity) key. */
    public KeyResult quantity() {
        return press("quantity", "QTY");
    }

    public KeyResult noSale() {
        return press("no_sale", "NO_SALE");
    }

    public KeyResult voidLine() {
        return press("void_line", "VOID_LINE");
    }

    public KeyResult voidTicket() {
        return press("void_ticket", "VOID_TICKET");
    }

    /** The SUSP (suspend) key. */
    public KeyResult suspend() {
        return press("suspend", "SUSP");
    }

    public KeyResult errorCorrect() {
        return press("error_correct", "ERROR_CORRECT");
    }

    /** The EXIT key. */
    public KeyResult exitKey() {
        return press("exit_key", "EXIT");
    }

    /** One named key, as its own action: the guard, then the overlays, then the press. */
    private KeyResult press(String method, String key) {
        requireKey(method, key);
        beforeAction.run();
        return sendKey(key);
    }

    /** One key with no guard: the shim's {@code press_keys} for a single key. */
    private KeyResult sendKey(String key) {
        KeyPressResult reply = shim.pressKeys(List.of(key));
        return new KeyResult(key, null, reply.allProcessed());
    }

    /** A key carrying data, as the shim's {@code press_with_data}. */
    private KeyResult pending(String key, String data) {
        PendingKeyResult reply = shim.pressWithData(key, data);
        return new KeyResult(key, data, reply.processed());
    }

    /** ENTER carrying data, as the shim's {@code press_enter_with_data}. */
    private KeyResult enter(String data) {
        PendingKeyResult reply = shim.pressEnterWithData(data);
        return new KeyResult("ENTER", data, reply.processed());
    }

    /**
     * Refuses a key the shim cannot build, before anything is sent. The shim's supported set is read once, on the
     * first guarded call, and kept.
     */
    private void requireKey(String method, String key) {
        if (supported == null) {
            List<String> named = shim.supportedKeys().namedKeys();
            supported = named == null ? Set.of() : new HashSet<>(named);
        }
        if (!supported.contains(key)) {
            List<String> sorted = new ArrayList<>(supported);
            Collections.sort(sorted);
            throw new DeviceUnavailable("LegacyRmi." + method + "() with key '" + key + "'",
                    REFERENCE + ": The POS shim's LexTokenMaker does not support this key. Supported named keys: "
                            + String.join(", ", sorted));
        }
    }

    /** PATS's {@code isHexaTrue}: {@code 0x} in either case, then exactly two hex digits. */
    static boolean isHexa(String value) {
        return value != null && HEXA.matcher(value).matches();
    }

    private static boolean isY(String value) {
        return "Y".equals(upper(value));
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    /** A bounded pause: parks until the deadline, never past it, and keeps an interrupt for the caller. */
    static void park(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long left = deadline - System.nanoTime();
        while (left > 0) {
            LockSupport.parkNanos(left);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
            left = deadline - System.nanoTime();
        }
    }

    /**
     * {@code addItem}'s fields, the corpus's own names mapped as {@code PosActionHandler.java:52-69} maps them. Use
     * {@link #of} for the inert defaults, then the builder for the fields a case carries.
     */
    public record ItemSale(String plu, String taxExempt, String modifier, String discount, String discountKey,
            String quantity, String idc, String priceOver, String pluNotFoundPrice, String usingMenu,
            String selectSoftkey, String choice, String selectFile, Runnable scan) {

        /** An item with every field at its inert default. */
        public static ItemSale of(String plu) {
            return new ItemSale(plu, "N", "0", "0", "SOFT KEY", "1", "N", "N", "N", "N", "N", "N", "NO FILE", null);
        }
    }

    /** {@code addDeptItem}'s fields, mapped as {@code PosActionHandler.java:143-147} maps them. */
    public record DeptSale(String deptKey, String amount, String departmentNo, String otherDepartment,
            String quantity, String pack, String taxExempt, String discount, String idc) {

        /** A department item with every optional field at its inert default. */
        public static DeptSale of(String deptKey, String amount) {
            return new DeptSale(deptKey, amount, "", "N", "0", "N", "N", "0", "N");
        }
    }
}
