package com.verifone.cat.pats.runner.handlers.steps;

import com.verifone.cat.authoring.StepDef;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.devices.legacyrmi.LegacyRmi;
import com.verifone.cat.pats.feature.LibrarySteps;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.HandlerFixtures;
import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.pages.BaseElement;
import com.verifone.cat.pos.pages.ItemPanel;
import com.verifone.cat.pos.pages.Keypad;
import com.verifone.cat.pos.pages.KeypadNamespace;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.rig.Durations;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.validate.ReceiptAssertions;
import com.verifone.cat.validate.SnapshotSession;
import com.verifone.cat.validate.ValidationFailedException;
import com.verifone.cat.validate.tlog.TlogAssertions;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The step library's domain and UI phrases, run: the Java bodies of the Python's {@code petrotest.authoring.steps}
 * modules ({@code pos_screen}, {@code register_keys}, {@code pinpad}, {@code forecourt}, {@code store}, {@code checks},
 * {@code screen_controls}). Registered as the one handler of {@link LibrarySteps#ACTION}: each step's line is resolved
 * against the library ({@link LibrarySteps#resolve}) and the body for that phrase's template runs with the line's
 * arguments, converted as the Python's {@code registry.TYPES} converts them.
 *
 * <p>A library step is not a PATS action, and runs as pytest-bdd runs it: the first step that throws stops the
 * scenario. A check that fails (the receipt or the TLog against its baseline) is therefore a hard failure too, as the
 * Python's {@code AssertionError} is; its verdict is recorded for the Validations view first, as the Python's
 * {@code receipt_checked} and {@code validation_checked} record it.
 *
 * <p>Deviations from the Python, each for want of a fixture this port does not have:
 * <ul>
 *   <li>The register keys run on a {@link LegacyRmi} over the run's pos-shim, built here, with the POS driver's overlay
 *       sweep before each key (the Python's {@code legacy_rmi} fixture with {@code pos} held); when the run has no RMI
 *       ({@link SaleRmi#UNAVAILABLE}) each fails with {@link DeviceUnavailable}, where the Python errors at setup.</li>
 *   <li>The cashier's sign-on reads the rig's cashier from {@link HandlerFixtures#cashier()}; without one it fails with
 *       {@link DeviceUnavailable}, where the Python's {@code rig} fixture errors at setup.</li>
 *   <li>The TLog check reads the Commander's TLog of the transaction the last receipt named, through the run's
 *       {@code commander_config} fixture, as the Python's {@code pos} fixture wires {@code tlog_source}.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): fake-POS runs and fakes only.
 */
public final class LibraryStepFamily {

    /** A case's one snapshot session, so its receipt and TLog checks share one claim set, as the test's fixture does. */
    static final ScratchKey<SnapshotSession> SNAPSHOT = ScratchKey.of("library-snapshot", SnapshotSession.class);

    /** The {@code Pos} a case's checks read: the receipt it captured names the transaction its TLog check fetches. */
    static final ScratchKey<Pos> CHECK_POS = ScratchKey.of("library-check-pos", Pos.class);

    /** PETROCORE-37043's sign-on screen, verbatim from its migrated test ({@code pos_screen}). */
    static final String ENTER_ID_TEXT = "Enter ID";
    static final String ENTER_PASSWORD_TEXT = "Enter Password For ID: ";
    static final String ENTER_OPEN_DRAWER_AMOUNT_TEXT = "Enter Open Drawer Amount";
    static final int OPEN_TILL_ITEM = 13631488;
    static final String OPEN_TILL_TEXT = "1. OPEN TILL for Drawer 1";

    /** {@code register_keys}: OTHER FUEL and its preset option, and the OTHER MOP key. */
    static final String OTHER_FUEL_KEY = "0x08";
    static final String PRESET_OPTION = "4";
    static final String OTHER_MOP_KEY = "0x3e";

    /** {@code store}: the Commander's fuel and DCR initialisations. */
    static final String FUEL_INIT = "cfuelinit";
    static final String DCR_INIT = "cdcrinit";

    /** {@code registry._MOP_KEYS}: a MopKey's spoken name to the key it presses. */
    private static final Map<String, String> MOP_KEYS = Map.of("CASH", "CASH", "CREDIT", "0x7d", "OTHER MOP", "0x3e");

    private static final Pattern CONTROL = Pattern.compile("^([A-Z][A-Z0-9_]*)(?:\\(([^)]*)\\))?$");
    private static final Pattern CALCULATOR_KEY = Pattern.compile("00|\\d");

    /** One phrase's body. */
    @FunctionalInterface
    interface Body {
        void run(Call call) throws Exception;
    }

    /** One step being run: the case, the run's fixtures, and the line's arguments. */
    record Call(CaseRun run, HandlerFixtures fixtures, Map<String, String> args) {

        String text(String name) {
            return Objects.requireNonNull(args.get(name), name);
        }

        int number(String name) {
            return Integer.parseInt(text(name));
        }

        /** A Money argument as written, without its {@code $}: {@code 11.00}. */
        String money(String name) {
            String text = text(name);
            return text.startsWith("$") ? text.substring(1) : text;
        }

        /** A Money argument as a cashier keys it: {@code 1100}. */
        String keyed(String name) {
            return money(name).replace(".", "");
        }

        Pos pos() {
            return fixtures.pos();
        }
    }

    private final HandlerFixtures fixtures;
    private final Map<String, Body> bodies;

    private LibraryStepFamily(HandlerFixtures fixtures) {
        this.fixtures = fixtures;
        this.bodies = bodies();
    }

    /** Registers the {@link LibrarySteps#ACTION} handler over the run's fixtures. */
    public static void register(ActionRegistry registry, HandlerFixtures fixtures) {
        LibraryStepFamily family = new LibraryStepFamily(Objects.requireNonNull(fixtures, "fixtures"));
        registry.register(LibrarySteps.ACTION, family::handle);
    }

    /** The templates of the phrases this family runs, for the parity test against the library. */
    public static java.util.Set<String> templates() {
        return new LibraryStepFamily(null).bodies.keySet();
    }

    void handle(CaseRun run, RunnableAction action) throws Exception {
        String line = action.get(LibrarySteps.LINE_FIELD);
        StepDef phrase = LibrarySteps.resolve(line);
        Body body = bodies.get(phrase.text());
        if (body == null) {
            throw new UnsupportedOperationException("the library phrase " + phrase.text()
                    + " has no Java body yet (pats.runner.handlers.steps)");
        }
        Map<String, String> args = phrase.arguments(line).orElseThrow();
        body.run(new Call(run, fixtures, args));
    }

    // -- the bodies, by template, module by module ----------------------------------------------------------------

    private Map<String, Body> bodies() {
        Map<String, Body> b = new LinkedHashMap<>();
        posScreen(b);
        registerKeys(b);
        pinpad(b);
        forecourt(b);
        store(b);
        checks(b);
        screenControls(b);
        return b;
    }

    /** {@code pos_screen}: the page objects. */
    private static void posScreen(Map<String, Body> b) {
        b.put("the register is idle", c -> c.pos().expectIdle(null));
        b.put("a sale is in progress", c -> c.pos().expectTransactionActive(null));
        b.put("the cashier signs on", c -> {
            Rig.Cashier cashier = cashier(c);
            c.pos().signOn(cashier.id(), cashier.resolvePassword(c.fixtures().environment()));
        });
        b.put("the cashier signs on and opens the till with a {amount:Money} float", c -> {
            Rig.Cashier cashier = cashier(c);
            Pos pos = c.pos();
            pos.signOnScreen().productName().expectVisible(null);
            pos.signOnScreen().productName().tap();
            pos.signOnScreen().prompt().expectText(ENTER_ID_TEXT, false, null);
            pos.keypad().enter(cashier.id(), null);
            pos.signOnScreen().prompt().expectText(ENTER_PASSWORD_TEXT, false, null);
            pos.keypad().enter(cashier.resolvePassword(c.fixtures().environment()), null);
            BaseElement openTill = pos.reviewOverlay().item(OPEN_TILL_ITEM);
            openTill.expectText(OPEN_TILL_TEXT, false, null);
            openTill.tap();
            pos.signOnScreen().openDrawerTitle().expectText(ENTER_OPEN_DRAWER_AMOUNT_TEXT, false, null);
            for (String key : calculatorKeys(c.keyed("amount"))) {
                pos.calculatorKeypad().digit(key).tap();
            }
            pos.calculatorKeypad().ok(null);
        });
        b.put("the cashier signs off", c -> c.pos().signOff());
        b.put("the cashier rings up PLU {plu:Number}", c -> c.pos().keyPlu(c.text("plu"), null));
        b.put("the cashier totals the sale", c -> c.pos().itemPanel().total(null));
        b.put("the customer pays {amount:Money} in cash", c -> c.pos().itemPanel().payCash(c.keyed("amount"), null));
        b.put("the customer pays by credit", c -> c.pos().itemPanel().payCredit());
        b.put("the customer pays {amount:Money} by \"{mop}\"",
                c -> c.pos().payOtherMop(c.text("mop"), c.keyed("amount"), null));
        b.put("the customer pays by \"{mop}\"", c -> c.pos().payOtherMop(c.text("mop"), null, null));
        b.put("the POS shows error \"{title}\": \"{message}\"",
                c -> c.pos().overlay().expect(c.text("title"), c.text("message"), null, null, false, null));
        b.put("the error message closes", c -> c.pos().overlay().expectClosed(null));
        b.put("the POS asks \"{prompt}\"", c -> c.pos().inputOverlay().expectPrompt(c.text("prompt"), null));
        b.put("the cashier voids the ticket", c -> c.pos().voidTicket(null));
    }

    /** {@code register_keys}: PATS's RMI key-drive, over the pos-shim. */
    private static void registerKeys(Map<String, Body> b) {
        b.put("the cashier keys PLU {plu:Number}", c -> rmi(c).addItem(LegacyRmi.ItemSale.of(c.text("plu"))));
        b.put("the cashier keys {quantity:Number} of PLU {plu:Number}", c -> {
            LegacyRmi.ItemSale item = LegacyRmi.ItemSale.of(c.text("plu"));
            rmi(c).addItem(new LegacyRmi.ItemSale(item.plu(), item.taxExempt(), item.modifier(), item.discount(),
                    item.discountKey(), c.text("quantity"), item.idc(), item.priceOver(), item.pluNotFoundPrice(),
                    item.usingMenu(), item.selectSoftkey(), item.choice(), item.selectFile(), item.scan()));
        });
        b.put("the cashier keys PLU {plu:Number} with modifier {modifier:Number}", c -> {
            LegacyRmi.ItemSale item = LegacyRmi.ItemSale.of(c.text("plu"));
            rmi(c).addItem(new LegacyRmi.ItemSale(item.plu(), item.taxExempt(), c.text("modifier"), item.discount(),
                    item.discountKey(), item.quantity(), item.idc(), item.priceOver(), item.pluNotFoundPrice(),
                    item.usingMenu(), item.selectSoftkey(), item.choice(), item.selectFile(), item.scan()));
        });
        b.put("the cashier keys {quantity:Number} at {amount:Money} on department key {key:Hex} "
                + "for department {department:Number}", c -> {
            LegacyRmi.DeptSale d = LegacyRmi.DeptSale.of(c.text("key").toLowerCase(Locale.ROOT), c.keyed("amount"));
            rmi(c).addDeptItem(new LegacyRmi.DeptSale(d.deptKey(), d.amount(), c.text("department"), "Y",
                    c.text("quantity"), d.pack(), d.taxExempt(), d.discount(), d.idc()));
        });
        b.put("the cashier keys TOTAL", c -> rmi(c).total());
        b.put("the cashier keys {amount:Money} on the {mop:MopKey} key", c -> rmi(c).mop(mopKey(c), c.keyed("amount")));
        b.put("the cashier keys the {mop:MopKey} key", c -> rmi(c).mop(mopKey(c), "0"));
        b.put("the cashier keys the OTHER MOP key and picks entry {position:Number}",
                c -> rmi(c).otherMop(OTHER_MOP_KEY, "0000", c.text("position"), ""));
        b.put("the cashier presses ENTER", c -> rmi(c).pressEnter(""));
        b.put("the cashier enters \"{data}\"", c -> rmi(c).pressEnter(c.text("data")));
        b.put("the cashier claims the fuel sale on pump {pump:Number}", c -> {
            LegacyRmi rmi = rmi(c);
            int pump = c.number("pump");
            rmi.fuel(c.text("pump"), "STACKED", () -> pumps(c).waitUntilIdle(pump, null));
        });
        b.put("the cashier keys a {amount:Money} prepay on pump {pump:Number} with the {mop:MopKey} key", c -> {
            LegacyRmi rmi = rmi(c);
            int pump = c.number("pump");
            rmi.prepay(c.text("pump"), "N", "1", c.keyed("amount"), mopKey(c),
                    () -> pumps(c).waitUntilIdle(pump, null));
        });
        b.put("the cashier keys a prepay quantity of {quantity:Money} on pump {pump:Number}", c -> {
            LegacyRmi rmi = rmi(c);
            int pump = c.number("pump");
            rmi.prepay(c.text("pump"), "Y", "0", c.keyed("quantity"), "0x7d",
                    () -> pumps(c).waitUntilIdle(pump, null));
        });
        b.put("the cashier presets {amount:Money} on pump {pump:Number}", c -> {
            LegacyRmi rmi = rmi(c);
            rmi.otherFuelKey(OTHER_FUEL_KEY, PRESET_OPTION);
            rmi.pressEnter(c.text("pump"));
            rmi.pressEnter(c.keyed("amount"));
        });
        b.put("the cashier prints flash report {report:Number} from functions menu {functions:Number} "
                + "of main menu {main:Number}",
                c -> rmi(c).printFlashReport(c.text("main"), c.text("functions"), c.text("report")));
    }

    /** {@code pinpad}: the run's {@code PinpadDevice} (the double under {@code --fake-pinpad}). */
    private static void pinpad(Map<String, Body> b) {
        b.put("the pinpad shows \"{prompt}\"", c -> c.fixtures().pinpad().atPrompt(c.text("prompt")));
        b.put("the customer presses \"{key}\" on the pinpad", c -> c.fixtures().pinpad().key(c.text("key")));
        b.put("the customer inserts card \"{card}\"", c -> c.fixtures().pinpad().insertCard(c.text("card")));
        b.put("the pinpad's \"{prompt}\" prompt clears by itself within {limit:Duration}",
                c -> c.fixtures().pinpad().expectPromptCleared(c.text("prompt"), Durations.parse(c.text("limit"))));
    }

    /** {@code forecourt}: Petro's pumps and DCR 1. */
    private static void forecourt(Map<String, Body> b) {
        b.put("pump {pump:Number} dispenses {amount:Money}",
                c -> pumps(c).fillAmount(c.number("pump"), c.money("amount"), 1, false, false));
        b.put("pump {pump:Number} dispenses {amount:Money} of its prepay",
                c -> pumps(c).fillAmount(c.number("pump"), c.money("amount"), 1, true, false));
        b.put("the customer hangs up pump {pump:Number}", c -> pumps(c).handleDown(c.number("pump"), false));
        b.put("the customer hangs up prepaid pump {pump:Number}", c -> pumps(c).handleDown(c.number("pump"), true));
        b.put("the DCR shows \"{prompt}\"", c -> dcr(c).waitForPrompt(c.text("prompt"), null));
        b.put("the customer presses {key:DcrKey} on the DCR",
                c -> dcr(c).press(c.text("key").toUpperCase(Locale.ROOT)));
    }

    /** {@code store}: the Commander over CGILink. */
    private static void store(Map<String, Body> b) {
        b.put("the store imports the \"{folder}\" configuration", c -> commander(c).importConfigurationFolder(
                c.fixtures().petroapps().configurations().resolve(c.text("folder"))));
        b.put("the Commander initialises fuel", c -> commander(c).controlFunction(FUEL_INIT));
        b.put("the Commander initialises the DCRs", c -> commander(c).controlFunction(DCR_INIT));
        b.put("the Commander runs control function \"{function}\"",
                c -> commander(c).controlFunction(c.text("function")));
    }

    /** {@code checks}: the receipt and the TLog against the case's reviewed baselines. */
    private static void checks(Map<String, Body> b) {
        b.put("the receipt matches its baseline", c -> {
            Receipt receipt = checkPos(c).receipt();
            c.run().record().recordReceipt(receipt.text(), "");
            SnapshotSession snapshot = snapshot(c.run());
            String name = snapshot.path(ReceiptAssertions.RECEIPT_SUFFIX, null).getFileName().toString();
            try {
                ReceiptAssertions.Verdict verdict = ReceiptAssertions.assertReceipt(receipt.text(), snapshot);
                c.run().record().recordValidation("receipt", name, true, "", verdict.expected(), verdict.compared(),
                        c.run().kase().key());
                c.run().record().markNewestReceipt(true);
            } catch (ValidationFailedException e) {
                c.run().record().recordValidation("receipt", name, false, e.getMessage(), "", receipt.text(),
                        c.run().kase().key());
                c.run().record().markNewestReceipt(false);
                throw e;
            }
        });
        b.put("the transaction log matches its baseline", c -> {
            Object tlog = checkPos(c).tlog();
            SnapshotSession snapshot = snapshot(c.run());
            String name = snapshot.path(TlogAssertions.TLOG_SUFFIX, null).getFileName().toString();
            try {
                ReceiptAssertions.Verdict verdict = TlogAssertions.assertTlog(tlog, snapshot);
                c.run().record().recordValidation("tlog", name, true, "", verdict.expected(), verdict.compared(),
                        c.run().kase().key());
            } catch (ValidationFailedException e) {
                c.run().record().recordValidation("tlog", name, false, e.getMessage(), "", "",
                        c.run().kase().key());
                throw e;
            }
        });
    }

    /** {@code screen_controls}: UI-level controls by name, the recorder's fallback. */
    private static void screenControls(Map<String, Body> b) {
        b.put("the cashier taps item {item:Number}", c -> c.pos().itemPanel().select(c.number("item")));
        b.put("the cashier rings up {quantity:Number} of item {item:Number}",
                c -> c.pos().itemPanel().selectWithQuantity(c.number("item"), c.number("quantity"), null));
        b.put("the cashier keys \"{digits}\" on the {pad:Pad} keypad",
                c -> keypad(c, c.text("pad")).enter(c.text("digits"), null));
        b.put("the cashier keys \"{digits}\" on the {pad:Pad} keypad without OK",
                c -> keypad(c, c.text("pad")).enter(c.text("digits"), false, null));
        b.put("the cashier presses {key:PadKey} on the {pad:Pad} keypad", c -> {
            Keypad pad = keypad(c, c.text("pad"));
            switch (c.text("key").toUpperCase(Locale.ROOT)) {
                case "OK" -> pad.ok(null);
                case "CANCEL" -> pad.cancel(null);
                default -> pad.clear(null);
            }
        });
        b.put("the cashier taps express button {slot:Number}",
                c -> c.pos().expressPanel().button(c.number("slot")).tap());
        b.put("the cashier taps row {row:Number} of the list", c -> c.pos().reviewOverlay().item(c.number("row")).tap());
        b.put("the cashier taps control \"{control}\"", LibraryStepFamily::tapControl);
    }

    // -- helpers ----------------------------------------------------------------------------------------------------

    /** {@code pos_screen.calculator_keys}: the calculator keys for {@code digits}, a pair of zeros its {@code 00} key. */
    public static List<String> calculatorKeys(String digits) {
        List<String> keys = new ArrayList<>();
        Matcher m = CALCULATOR_KEY.matcher(digits);
        while (m.find()) {
            keys.add(m.group());
        }
        return keys;
    }

    private static Rig.Cashier cashier(Call c) {
        Rig.Cashier cashier = c.fixtures().cashier();
        if (cashier == null) {
            throw new DeviceUnavailable("rig", "the rig's cashier is not given to this run's steps");
        }
        return cashier;
    }

    /** The MopKey as the Python converts it: CASH, CREDIT and OTHER MOP to their keys, a 0xNN code as written. */
    private static String mopKey(Call c) {
        String text = c.text("mop");
        return MOP_KEYS.getOrDefault(text.toUpperCase(Locale.ROOT), text);
    }

    /** The register's RMI, over the run's pos-shim, sweeping the POS's overlays before each key. */
    private static LegacyRmi rmi(Call c) {
        if (c.fixtures().saleRmi() == SaleRmi.UNAVAILABLE) {
            throw new DeviceUnavailable("legacy_rmi",
                    "the register's RMI (the pos-shim's key path) is not connected to this run");
        }
        Pos pos = c.pos();
        return new LegacyRmi(c.fixtures().posShim(), () -> pos.driver().clearBlockingOverlays(null));
    }

    private static FuelPumps pumps(Call c) {
        return c.fixtures().fuelPumps() == null ? FuelPumps.UNAVAILABLE : c.fixtures().fuelPumps();
    }

    private static DcrDevice dcr(Call c) {
        return c.fixtures().dcr() == null ? DcrDevice.UNAVAILABLE : c.fixtures().dcr();
    }

    private static PdkShim commander(Call c) {
        return c.run().fixture(FixtureName.COMMANDER_CONFIG, PdkShim.class);
    }

    /** The case's snapshot session, made on its first check from the case's {@code .feature} file. */
    private static SnapshotSession snapshot(CaseRun run) {
        return run.scratchOrCreate(SNAPSHOT, () -> {
            Path file = Path.of(run.kase().source());
            return SnapshotSession.forNodeId(file, file.getFileName() + "::test", run.update(), new java.util.HashSet<>());
        });
    }

    /**
     * The {@code Pos} the checks read, one per case: the run's receipt source, and the Commander's TLog of the
     * transaction the receipt named (the Python's {@code pos} fixture's {@code tlog_source}).
     */
    private static Pos checkPos(Call c) {
        HandlerFixtures f = c.fixtures();
        CaseRun run = c.run();
        return run.scratchOrCreate(CHECK_POS, () -> new Pos(f.pos().driver(), f.receipts(),
                number -> commander(c).fetchTlog(number),
                () -> commander(c).listTransactionNumbers()));
    }

    /** {@code screen_controls.keypad}: numeric is the VFI pad, calculator the till pad, on-screen the PLU pad. */
    private static Keypad keypad(Call c, String which) {
        return switch (which.toLowerCase(Locale.ROOT)) {
            case "calculator" -> c.pos().calculatorKeypad();
            case "on-screen" -> new Keypad(c.pos().driver(), KeypadNamespace.VIRTUAL_KEYBOARD);
            default -> c.pos().keypad();
        };
    }

    /** {@code taps_control}: any catalogued control by its {@code Locators} name, with slot values in brackets. */
    private static void tapControl(Call c) {
        String control = c.text("control").strip();
        Matcher m = CONTROL.matcher(control);
        Locator locator = m.matches() ? catalogued(m.group(1)) : null;
        if (locator == null) {
            throw new IllegalArgumentException("'" + control + "' is not a control in pos.Locators");
        }
        List<String> parts = new ArrayList<>();
        for (String part : (m.group(2) == null ? "" : m.group(2)).split(",")) {
            if (!part.strip().isEmpty()) {
                parts.add(part.strip());
            }
        }
        Map<String, String> named = new HashMap<>();
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                named.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
            }
        }
        if (!named.isEmpty()) {
            c.pos().driver().tap(locator.atNamed(named));
        } else {
            c.pos().driver().tap(locator, parts.toArray(new Object[0]));
        }
    }

    private static Locator catalogued(String name) {
        try {
            Field field = Locators.class.getField(name);
            if (Modifier.isStatic(field.getModifiers()) && Locator.class.isAssignableFrom(field.getType())) {
                return (Locator) field.get(null);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
        return null;
    }
}
