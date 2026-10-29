package com.verifone.cat.pats.runner.handlers.sale;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.devices.ActionFields;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.driver.PosDriver;

import java.io.StringReader;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sale: items, quantity, tenders, TOTAL, prepay and postpay fuel, refunds with the TRAN#/STAN map a refund is
 * keyed by, voids, error correct and suspend, and the bare keys a case presses by name or code. Ported from the Python
 * {@code petrotest.pats.runner.sale}, which is the specification.
 *
 * <p>Twenty action names, registered by {@link #register}. Each handler reaches the POS screen through
 * {@link SaleScreen}, the register's keys through {@link SaleRmi}, and the pumps and scanner data through their
 * seams; an unwired one throws {@link com.verifone.cat.devices.DeviceUnavailable}.
 *
 * <p>The Python's {@code run.rmi.shim} calls are {@link SaleRmi#pressKeys} and {@link SaleRmi#pressKey}; the Python's
 * overlay readers and {@code settle} are {@link SaleScreen}. PATS's {@code _clear_nag} is
 * {@link SaleScreen#clearBlockingOverlays}, which clears a nag as the sweep does. {@link SaleScreen#overlay} reads the
 * overlay families the Python's {@code _overlay_texts} reads: the message box, else the input dialog.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class SaleFamily {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner.handlers.sale");

    /** The action names this family registers, as PATS names them (upper-case). */
    public static final List<String> ACTIONS = List.of(
            "SCAN_ITEM", "PRESS_ENTER", "MOP", "OTHER_MOP", "TOTAL", "ADD_ITEM", "QTY", "PREPAY", "FUEL", "APPROVE",
            "REFUND", "STORE_TRANS_MAP", "REFUND_WITH_TRANS_MAP", "VOID_TICKET", "VOID_LINE", "ERROR_CORRECT", "SUSP",
            "CONFIRMATION", "POS_KEYBOARD_EVENT", "ADD_DEPT_ITEM");

    /** The text a scan waits out before it is sent (PATS's pinpad cancel, widget {@code CANCEL_PINPAD_SESSION}). */
    static final String CANCEL_PINPAD_SESSION = "Cancelling PinPad Session";

    /** The express labels that mean the POS is ready (widgets {@code EXPRESS_REPRINT}, {@code EXPRESS_RECALL}, {@code EXPRESS_CSRFUNC}). */
    static final Set<String> READY_LABELS = Set.of("Reprint", "Recall", "CSR Func");

    /** The express labels that mean a suspend is possible (widgets {@code EXPRESS_PRICE_CHECK}, {@code EXPRESS_RECALL}). */
    static final Set<String> SUSPEND_LABELS = Set.of("Price Check", "Recall");

    /** The pump states that mean a postpay pump is waiting for its approval. */
    static final Set<String> AWAITING_APPROVAL = Set.of("IDLEON", "PTHOLD", "DCRIDLEON");

    /** The overlay titles PATS treats as a nag, from the POS driver. */
    static final List<String> NAG_TITLES = PosDriver.NAG_OVERLAY_TITLES;

    private static final Pattern TRAN = Pattern.compile("TRAN#\\s*(\\d+)");
    private static final Pattern STAN = Pattern.compile("Stan:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private SaleFamily() {
    }

    /**
     * Registers the twenty sale handlers.
     *
     * @throws IllegalStateException when one of the names already has a handler
     */
    public static void register(ActionRegistry registry, SaleContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        registry.register("SCAN_ITEM", (run, action) -> scanItem(run, action, context));
        registry.register("PRESS_ENTER", (run, action) -> pressEnter(run, action, context));
        registry.register("MOP", (run, action) -> mop(run, action, context));
        registry.register("OTHER_MOP", (run, action) -> otherMop(run, action, context));
        registry.register("TOTAL", (run, action) -> total(run, context));
        registry.register("ADD_ITEM", (run, action) -> addItem(run, action, context));
        registry.register("QTY", (run, action) -> quantity(run, action, context));
        registry.register("PREPAY", (run, action) -> prepay(run, action, context));
        registry.register("FUEL", (run, action) -> fuel(run, action, context));
        registry.register("APPROVE", (run, action) -> approve(run, action, context));
        registry.register("REFUND", (run, action) -> refund(run, context));
        registry.register("STORE_TRANS_MAP", (run, action) -> storeTransMap(run, action));
        registry.register("REFUND_WITH_TRANS_MAP", (run, action) -> refundWithTransMap(run, action, context));
        registry.register("VOID_TICKET", (run, action) -> voidTicket(run, context));
        registry.register("VOID_LINE", (run, action) -> voidLine(run, action, context));
        registry.register("ERROR_CORRECT", (run, action) -> errorCorrect(run, context));
        registry.register("SUSP", (run, action) -> suspend(run, context));
        registry.register("CONFIRMATION", (run, action) -> confirmation(run, action, context));
        registry.register("POS_KEYBOARD_EVENT", (run, action) -> posKeyboardEvent(run, action, context));
        registry.register("ADD_DEPT_ITEM", (run, action) -> addDeptItem(run, action, context));
    }

    // -- scans --------------------------------------------------------------------------

    /** {@code POSDevice.scanItem}: the {@code scannerdata/} file, when it is an XML file; any other name does nothing. */
    static void scanItem(CaseRun run, RunnableAction action, SaleContext context) {
        String name = action.get("SELECT_FILE");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            return;
        }
        scanFile(run, context, name);
    }

    /**
     * {@code POSDevice.addScannerItem}: {@code scannerdata/<name>} presented as a scanner token, then a settle. A scan
     * that changed nothing under a nag is scanned again once the nag is cleared.
     */
    static void scanFile(CaseRun run, SaleContext context, String name) {
        Element data = scannerDataElement(context, name);
        sweep(context);
        waitOutPinpadCancel(run, context);
        context.pacer().boundaryPause(run);
        if (scanOnce(run, context, data)) {
            return;
        }
        Map<String, String> shown = context.screen().overlay();
        if (shown != null && NAG_TITLES.contains(shown.getOrDefault("TITLE", "").strip().toUpperCase(Locale.ROOT))) {
            LOG.warn("{}: the scan of {} went under {}; scanning again", run.kase().key(), name, shown.get("TITLE"));
            context.screen().clearBlockingOverlays();
            if (scanOnce(run, context, data)) {
                return;
            }
        }
        LOG.info("{}: nothing on screen answered the scan of {}", run.kase().key(), name);
    }

    /** One scan: the token sent, the touch marked, the screen settled. True when the screen changed. */
    private static boolean scanOnce(CaseRun run, SaleContext context, Element data) {
        String before = context.screen().screenText();
        context.rmi().scanItem(new SaleRmi.ScanItem(
                childText(data, "scanData", ""),
                Integer.parseInt(childText(data, "scanDataType", "0")),
                childText(data, "decodeData", "true").strip().equalsIgnoreCase("true"),
                childTextOrNull(data, "scanDataLabel"),
                Integer.parseInt(childText(data, "status", "0"))));
        context.pacer().pressed(run);
        return context.screen().settled(before, context.timing().touchSettle());
    }

    /** Holds a scan while the register shows the pinpad cancel, up to its wait: a token sent under it is swallowed. */
    private static void waitOutPinpadCancel(CaseRun run, SaleContext context) {
        String cancelling = CANCEL_PINPAD_SESSION.toLowerCase(Locale.ROOT);
        BooleanSupplier clear = () -> {
            Map<String, String> shown = context.screen().overlay();
            return shown == null || shown.values().stream()
                    .noneMatch(text -> text.toLowerCase(Locale.ROOT).contains(cancelling));
        };
        if (clear.getAsBoolean()) {
            return;
        }
        LOG.info("{}: waiting for {} to clear before the scan", run.kase().key(), CANCEL_PINPAD_SESSION);
        try {
            PollUntil.until(clear, context.timing().cancelSessionWait(), context.timing().pollInterval(),
                    "the pinpad cancel");
        } catch (DriverTimeout notCleared) {
            LOG.info("{}: the pinpad cancel did not clear; scanning anyway", run.kase().key());
        }
    }

    /** The first {@code <Data>} element of the scanner-data file, read through a secure XML parser. */
    private static Element scannerDataElement(SaleContext context, String name) {
        String xml = context.scannerData().xml(name);
        NodeList nodes = parse(xml).getElementsByTagName("Data");
        if (nodes.getLength() == 0) {
            throw new PatsStepError("scannerdata/" + name + " has no <Data>");
        }
        return (Element) nodes.item(0);
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new PatsStepError("scannerdata is not readable XML: " + e.getMessage(), e);
        }
    }

    /** The text of the first direct child named {@code name}, or {@code fallback} when there is none. */
    private static String childText(Element parent, String name, String fallback) {
        String text = childTextOrNull(parent, name);
        return text == null ? fallback : text;
    }

    private static String childTextOrNull(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && name.equals(child.getTagName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    // -- keys and tenders ------------------------------------------------------------------

    /** {@code POSDevice.pressEnter}: bare when DATA is absent, else ENTER carrying it. */
    static void pressEnter(CaseRun run, RunnableAction action, SaleContext context) {
        sweep(context);
        context.pacer().boundaryPause(run);
        context.rmi().pressEnter(action.raw("DATA"));
        context.pacer().pressed(run);
    }

    /** {@code POSDevice.cashMop}. */
    static void mop(CaseRun run, RunnableAction action, SaleContext context) {
        sweep(context);
        context.rmi().mop(ActionFields.require(action, "MOP"), ActionFields.require(action, "DATA"));
        awaitTender(run, action, context);
    }

    /** {@code POSDevice.cashOtherMop}. */
    static void otherMop(CaseRun run, RunnableAction action, SaleContext context) {
        sweep(context);
        context.rmi().otherMop(
                ActionFields.require(action, "OTHER_MOP"),
                action.get("DATA"),
                ActionFields.require(action, "POSITION"),
                action.get("SPECIALDISCOUNT", "N"));
        awaitTender(run, action, context);
    }

    /**
     * After a tender, up to the touch settle for the POS to read Ready, then on either way. The register answers the
     * tender's key before the sale commits, and a key sent while it commits is taken by the payment state; a partial
     * tender or a prompt spends the wait and goes on, as PATS's next key would.
     */
    private static void awaitTender(CaseRun run, RunnableAction action, SaleContext context) {
        if (!run.declares(FixtureName.POS)) {
            return;
        }
        try {
            PollUntil.until(() -> isReady(context.screen().expressLabels()), context.timing().touchSettle(),
                    context.timing().pollInterval(), "Ready");
        } catch (DriverTimeout notReady) {
            LOG.info("{}: {}; not Ready in 2 s, going on", run.kase().key(), action.name());
        }
    }

    /** {@code POSDevice.total}. */
    static void total(CaseRun run, SaleContext context) {
        sweep(context);
        context.rmi().total();
    }

    /**
     * {@code POSDevice.addItem}, through the register's item keys. A scanner-data {@code SELECT_FILE} is scanned as
     * SCAN_ITEM scans it, when the register asks for the item's scanner file.
     */
    static void addItem(CaseRun run, RunnableAction action, SaleContext context) {
        String selectFile = action.get("SELECT_FILE", "NO FILE");
        sweep(context);
        context.rmi().addItem(new SaleRmi.AddItem(
                ActionFields.require(action, "PLU"),
                action.get("TAX EXC", "N"),
                action.get("MODIFIER", "0"),
                action.get("DISCOUNT", "0"),
                action.get("DISCOUNT KEY", "SOFT KEY"),
                action.get("QUANTITY", "1"),
                action.get("IDC", "N"),
                action.get("PRICEOVER", "N"),
                action.get("PLUNOTFUND PRICE", "N"),
                action.get("USING MENU", "N"),
                action.get("SELECT SOFTKEY", "N"),
                action.get("CHOICE", "N"),
                selectFile,
                () -> scanFile(run, context, selectFile)));
    }

    /** {@code POSDevice.quantityKey}: the key, then ENTER carrying the quantity. */
    static void quantity(CaseRun run, RunnableAction action, SaleContext context) {
        sweep(context);
        context.rmi().pressKeys(ActionFields.require(action, "QUANTITY_KEY"));
        context.rmi().pressEnter(ActionFields.require(action, "QUANTITY"));
    }

    /**
     * {@code POSDevice.prePay}: marks the case as a prepay, so the pump's lift after it is a prepay lift, and waits for
     * the pump to be idle once the sale has taken it.
     */
    static void prepay(CaseRun run, RunnableAction action, SaleContext context) {
        run.setScratch(ScratchKey.PREPAY, true);
        String pump = ActionFields.require(action, "PUMP NO");
        FuelPumps pumps = context.fuelPumps();
        sweep(context);
        context.rmi().prepay(
                pump,
                action.get("GRADE", "N"),
                action.get("FUEL PRODUCT", "0"),
                action.get("AMOUNT"),
                action.get("MOP", "CASH"),
                () -> pumps.waitUntilIdle(Integer.parseInt(pump), ActionFields.budget(run, context.timing().pinpadWait())));
    }

    /** {@code POSDevice.fuel}: a sale on a pump, waiting for the pump to be claimable. */
    static void fuel(CaseRun run, RunnableAction action, SaleContext context) {
        String pump = ActionFields.require(action, "PUMP");
        FuelPumps pumps = context.fuelPumps();
        sweep(context);
        context.rmi().fuel(
                pump,
                action.get("CURRENT/STACKED", "STACKED"),
                () -> pumps.waitUntilIdle(Integer.parseInt(pump), ActionFields.budget(run, context.timing().pinpadWait())));
    }

    /**
     * {@code POSDevice.postpayApprove}: waits for the pump to be waiting for approval (IDLEON, PTHOLD or DCRIDLEON),
     * then presses APPROVE carrying the pump.
     */
    static void approve(CaseRun run, RunnableAction action, SaleContext context) {
        String pump = ActionFields.require(action, "PUMP NO");
        int number = Integer.parseInt(pump);
        FuelPumps pumps = context.fuelPumps();
        PollUntil.until(
                () -> AWAITING_APPROVAL.contains(pumps.state(number).orElse("").toUpperCase(Locale.ROOT)),
                ActionFields.budget(run, context.timing().pinpadWait()), context.timing().pollInterval(),
                "pump " + pump + " to be waiting for approval");
        sweep(context);
        context.rmi().pressWithData("APPROVE", pump);
    }

    /** {@code POSDevice.reFund}: once the POS is ready, REFUND. */
    static void refund(CaseRun run, SaleContext context) {
        PollUntil.until(() -> isReady(context.screen().expressLabels()),
                ActionFields.budget(run, context.timing().stateWait()), context.timing().pollInterval(),
                "the POS ready state");
        sweep(context);
        context.rmi().refund();
    }

    // -- the TRAN#/STAN map -----------------------------------------------------------------

    /** {@code POSDevice.storeReFundKey}: the next receipt's TRAN# and STAN are kept under the key. */
    static void storeTransMap(CaseRun run, RunnableAction action) {
        run.setScratch(ScratchKey.TRANS_MAP_KEY, ActionFields.require(action, "REFUND_KEY"));
    }

    /**
     * {@code TopazPrinterSim.recordReceiptsObject}, for the receipt just captured: its TRAN# and STAN go under the key
     * a STORE_TRANS_MAP named, if one is waiting. VALIDATE_RECEIPT calls it with the receipt it captured, as the
     * Python's {@code _validate_receipt} does.
     */
    public static void bindTransMap(CaseRun run, String text) {
        Optional<String> key = run.scratch(ScratchKey.TRANS_MAP_KEY);
        if (key.isEmpty()) {
            return;
        }
        Matcher tran = TRAN.matcher(text);
        Matcher stan = STAN.matcher(text);
        String tranValue = tran.find() ? tran.group(1) : null;
        String stanValue = stan.find() ? stan.group(1) : null;
        transMap(run).put(key.get(), new AbstractMap.SimpleImmutableEntry<>(tranValue, stanValue));
        run.clearScratch(ScratchKey.TRANS_MAP_KEY);
    }

    private static Map<String, AbstractMap.SimpleImmutableEntry<String, String>> transMap(CaseRun run) {
        return run.scratchOrCreate(ScratchKey.TRANS_MAP, () -> new HashMap<>());
    }

    /**
     * {@code POSDevice.reFundKey}: ENTER with the STAN or TRAN# the key stored. The STAN prompt is waited for while a
     * STAN is stored; the TLog the case last validated gives one when the receipt has none; with neither, VOID_TICKET, as PATS.
     */
    static void refundWithTransMap(CaseRun run, RunnableAction action, SaleContext context) {
        String key = ActionFields.require(action, "REFUND_KEY");
        if (run.scratch(ScratchKey.TRANS_MAP_KEY).isPresent()) {
            bindTransMap(run, context.screen().receiptText());
        }
        AbstractMap.SimpleImmutableEntry<String, String> entry = transMap(run).get(key);
        String tran = entry == null ? null : entry.getKey();
        String stan = entry == null ? null : entry.getValue();
        if (!present(stan)) {
            stan = context.lastTlog().stan(run).orElse(null);
        }
        String[] line1 = {""};
        BooleanSupplier prompted = () -> {
            Map<String, String> shown = context.screen().overlay();
            line1[0] = shown == null ? "" : shown.getOrDefault("LINE1", "");
            return asksForStan(line1[0]);
        };
        if (present(stan)) {
            try {
                PollUntil.until(prompted, ActionFields.budget(run, context.timing().overlayRead()),
                        context.timing().pollInterval(), "the STAN prompt");
            } catch (DriverTimeout notPrompted) {
                LOG.info("{}: the STAN prompt did not show; reading the overlay as it is", run.kase().key());
            }
        } else {
            prompted.getAsBoolean();
        }
        LOG.info("{}: {} holds TRAN# {}, STAN {}; overlay line 1 reads {}", run.kase().key(), key, tran, stan,
                line1[0]);
        if (present(stan) && asksForStan(line1[0])) {
            context.rmi().pressEnter(stan);
        } else if (present(tran)) {
            context.rmi().pressEnter(tran);
        } else {
            LOG.error("{}: no TRAN# or STAN stored under {}; VOID_TICKET, as PATS", run.kase().key(), key);
            context.rmi().voidTicket();
        }
    }

    private static boolean asksForStan(String line) {
        return line.strip().toUpperCase(Locale.ROOT).equals("ENTER STAN")
                || line.toLowerCase(Locale.ROOT).contains("invoice number");
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    // -- voids, error correct, suspend, bare keys --------------------------------------------

    /** {@code POSDevice.voidTicket}: the overlay check, then a bare void. */
    static void voidTicket(CaseRun run, SaleContext context) {
        sweep(context);
        overlayPause(run, context);
        context.rmi().voidTicket();
    }

    /** {@code POSDevice.voidline}: the overlay check, then the void-line keys with the PLU and quantity. */
    static void voidLine(CaseRun run, RunnableAction action, SaleContext context) {
        String plu = ActionFields.require(action, "PLU");
        String quantity = ActionFields.require(action, "QUANTITY");
        sweep(context);
        overlayPause(run, context);
        context.rmi().voidLine(plu, quantity);
    }

    /** {@code POSDevice.errorcor}: the overlay check, then a bare ERROR CORRECT. */
    static void errorCorrect(CaseRun run, SaleContext context) {
        sweep(context);
        overlayPause(run, context);
        context.rmi().errorCorrect();
    }

    /**
     * {@code POSDevice.susp}: once the express panel offers Price Check or Recall, a bare SUSPEND. PATS throws when the
     * panel never offers one, which is a step failure here too.
     */
    static void suspend(CaseRun run, SaleContext context) {
        sweep(context);
        try {
            PollUntil.until(() -> isSuspendable(context.screen().expressLabels()),
                    ActionFields.budget(run, context.timing().stateWait()), context.timing().pollInterval(),
                    "an express button reading Price Check or Recall");
        } catch (DriverTimeout notOffered) {
            throw new PatsStepError(notOffered.getMessage() + "; the express panel reads "
                    + context.screen().expressLabels(), notOffered);
        }
        context.rmi().suspend();
    }

    /** {@code POSDevice.confirmation}: the YES/NO value as one bare key. No overlay check and no state gate. */
    static void confirmation(CaseRun run, RunnableAction action, SaleContext context) {
        String answer = action.get("YES/NO").strip();
        if (answer.isEmpty()) {
            throw new PatsStepError("CONFIRMATION has no YES/NO value; PATS would press a null key");
        }
        sweep(context);
        context.rmi().pressKey(answer);
    }

    /**
     * {@code POSDevice.pressUnpendingSoftKey}: the KEY_EVENT_ADDR key code, pressed bare. PATS skips the action when the
     * field is absent; the overlay check comes first, as in voidTicket.
     */
    static void posKeyboardEvent(CaseRun run, RunnableAction action, SaleContext context) {
        String key = action.raw("KEY_EVENT_ADDR");
        if (key == null) {
            LOG.info("{}: POS_KEYBOARD_EVENT with no KEY_EVENT_ADDR; PATS skips it", run.kase().key());
            return;
        }
        sweep(context);
        overlayPause(run, context);
        context.rmi().pressKey(key);
    }

    /** {@code POSDevice.addDeptItem}, through the register's department keys. */
    static void addDeptItem(CaseRun run, RunnableAction action, SaleContext context) {
        sweep(context);
        context.rmi().addDeptItem(new SaleRmi.AddDeptItem(
                ActionFields.require(action, "DEPT"),
                ActionFields.require(action, "AMOUNT"),
                action.get("DEPARTMENT NO"),
                action.get("OTHER DEPARTMENT", "N"),
                action.get("QUANTITY", "0"),
                action.get("@", "N"),
                action.get("TAX EXC", "N"),
                action.get("DISCOUNT", "0"),
                action.get("IDC", "N")));
    }

    // -- shared -------------------------------------------------------------------------

    /** PATS's overlay sweep before every non-UI POS action (Python {@code CaseRun.sweep}). */
    static void sweep(SaleContext context) {
        context.screen().clearBlockingOverlays();
    }

    /** The overlay check before a void, an error correct or a keyboard key: a message up is given one second. */
    private static void overlayPause(CaseRun run, SaleContext context) {
        if (context.screen().overlay() != null) {
            LOG.info("{}: a message is up before the key", run.kase().key());
            context.pauses().pause(context.timing().overlaySeenPause());
        }
    }

    /** The POS is ready when an express button reads Reprint, Recall or CSR Func. */
    static boolean isReady(List<String> labels) {
        return labels.stream().anyMatch(READY_LABELS::contains);
    }

    /** The POS can suspend when an express button reads Price Check or Recall, exactly. */
    static boolean isSuspendable(List<String> labels) {
        return labels.stream().anyMatch(SUSPEND_LABELS::contains);
    }
}
