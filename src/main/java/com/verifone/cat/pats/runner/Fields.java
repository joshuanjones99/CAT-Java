package com.verifone.cat.pats.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which fields of each PATS action the runner reads, and an action reduced to them.
 *
 * <p>A PATS XML action carries whatever its author's editor left on it; the runner reads only some
 * fields, by name. {@link #FIELDS_READ} lists those per action, and {@link #canonical} reduces an
 * action to them, so two actions with the same canonical form run identically whatever else they
 * carry. The table is the handlers' own reads: a handler that starts reading a new field must add it
 * here, or the feature-to-XML equivalence check (in the Python, {@code tests/unit/test_pats_feature.py})
 * will not see it.
 *
 * <p>Three fields are read for every action: {@code DEVICE} (POS by default), {@code DEVICEID} (only
 * whether it names a DCR), and the name. Ported from the Python {@code petrotest.pats.runner.fields}.
 */
public final class Fields {

    private static final List<String> ELEMENT = List.of("WEB ELEMENT", "TYPE[ID/XPATH]");

    /** Per action name (upper-case), the fields its handler reads. */
    public static final Map<String, List<String>> FIELDS_READ = Map.ofEntries(
            Map.entry("SLEEP", List.of("SLEEP")),
            Map.entry("TOUCH_EVENT", ELEMENT),
            Map.entry("WIDGET_VALIDATION", concat(ELEMENT, "VALIDATION KEY")),
            Map.entry("VALIDATION_BY_TEXT", concat(ELEMENT, "VALIDATION TEXT")),
            Map.entry("IS_ELEMENT_PRESENT", concat(ELEMENT, "YES/NO")),
            Map.entry("IS_ELEMENT_CLICKABLE", concat(ELEMENT, "YES/NO", "WAIT TIME[SEC]")),
            Map.entry("APP_SELECTOR", ELEMENT),
            Map.entry("CASHIER_LOGIN", List.of()),
            Map.entry("VALIDATE_OVERLAY", List.of("TITLE", "LINE1", "LINE2", "LINE3", "YES/NO")),
            Map.entry("SCAN_ITEM", List.of("SELECT_FILE")),
            Map.entry("PRESS_ENTER", List.of("DATA")),
            Map.entry("MOP", List.of("MOP", "DATA")),
            Map.entry("OTHER_MOP", List.of("OTHER_MOP", "DATA", "POSITION", "SPECIALDISCOUNT")),
            Map.entry("TOTAL", List.of()),
            Map.entry("ADD_ITEM", List.of("PLU", "QUANTITY", "MODIFIER", "IDC", "PRICEOVER",
                    "PLUNOTFUND PRICE", "DISCOUNT", "DISCOUNT KEY", "SELECT SOFTKEY", "USING MENU",
                    "CHOICE", "TAX EXC", "SELECT_FILE")),
            Map.entry("QTY", List.of("QUANTITY_KEY", "QUANTITY")),
            Map.entry("PREPAY", List.of("PUMP NO", "AMOUNT", "MOP", "FUEL PRODUCT", "GRADE")),
            Map.entry("FUEL", List.of("PUMP", "CURRENT/STACKED")),
            Map.entry("APPROVE", List.of("PUMP NO")),
            Map.entry("LOGIN", List.of("USER_ID", "PASSWORD")),
            Map.entry("LOGOUT", List.of()),
            Map.entry("ENTER_DRAWER_AMNT", List.of("DRAWER_AMOUNT")),
            Map.entry("SELECT_TILL", List.of("SELECT_TILL_OPTION")),
            Map.entry("REFUND", List.of()),
            Map.entry("STORE_TRANS_MAP", List.of("REFUND_KEY")),
            Map.entry("REFUND_WITH_TRANS_MAP", List.of("REFUND_KEY")),
            Map.entry("REPORT_PRINT", List.of()),
            Map.entry("NETWORK_REPORT", List.of("MAIN_MENU_NO", "NETWORK_MENU_NO", "REPORT_NO",
                    "SUB_REPORT_NO", "IS_SECURE_REPORT", "SECURE_USER", "SECURE_PASSWORD",
                    "DAY_SEQUENCE", "BATCH_NUMBER")),
            Map.entry("VALIDATE_RECEIPT", List.of("SELECT_FILE")),
            Map.entry("VALIDATE_TRANSACTION", List.of("SELECT_FILE", "SELECT_RULE_FILE")),
            Map.entry("VALIDATE_REPORT", List.of("CMD", "REPORTNAME", "REPORTTYPE", "FILENAME",
                    "PERIOD", "CASHIERNUM", "TOPAZNUM", "SELECT_FILE", "SELECT_RULE_FILE")),
            Map.entry("RESET_PACKETS", List.of()),
            Map.entry("VALIDATE_PACKETS", List.of("SELECT_REFERENCE_FILE", "SELECT_RULE_FILE")),
            Map.entry("UPDATE_CONFIGURATION", List.of("SELECT_FILE")),
            Map.entry("IMPORT_CONFIGURATION", List.of("SELECT_FOLDER")),
            Map.entry("CONTROL_FUNCTION", List.of("INIT_TYPE")),
            Map.entry("INSERT_OR_SLIDE_CARD", List.of("CARD_NAME", "CARD_SIMULATION_IMAGE_FILE")),
            Map.entry("REMOVE_CARD", List.of()),
            Map.entry("VOID_TICKET", List.of()),
            Map.entry("VOID_LINE", List.of("PLU", "QUANTITY")),
            Map.entry("ERROR_CORRECT", List.of()),
            Map.entry("SUSP", List.of()),
            Map.entry("CONFIRMATION", List.of("YES/NO")),
            Map.entry("POS_KEYBOARD_EVENT", List.of("KEY_EVENT_ADDR")),
            Map.entry("ADD_DEPT_ITEM", List.of("DEPT", "AMOUNT", "DEPARTMENT NO", "OTHER DEPARTMENT",
                    "QUANTITY", "@", "TAX EXC", "DISCOUNT", "IDC")),
            Map.entry("PRINT_FLASH_REPORT", List.of("MAIN MENU NO", "FUNCTIONS MENU NO", "FLASH REPORTS MENU NO")),
            Map.entry("CLOSE_REPORT", List.of("MAIN MENU NO", "CLOSE REPORT NO", "REPORT TYPE", "CASHIERNUM")),
            Map.entry("CLOUD_HTTP_REQUEST", List.of("HTTP_METHOD", "CONFIGURATION_URL", "SELECT_PAYLOAD_FILE")),
            Map.entry("R1", List.of()),
            Map.entry("R2", List.of()),
            Map.entry("R3", List.of()),
            Map.entry("R4", List.of()),
            Map.entry("ADD_PLU_NUMERIC_KEYPAD", List.of("PLUS")),
            Map.entry("VALIDATE_DYNAMIC_PROMPTS", List.of("PROMPTS")),
            Map.entry("PREPAY_FUEL_AMOUNT", List.of("PUMP NO", "AMOUNT")),
            Map.entry("PREPAY_FUEL_VOLUME", List.of("PUMP NO", "GALLONS", "GRADE")),
            Map.entry("WAIT_FOR_PROMPT", List.of("DISPLAY_KEY", "DISPLAY_KEY_EXACT_MATCH[YES/NO]")),
            Map.entry("GET_ANY_KEY", List.of("ENTER_KEY")),
            Map.entry("GET_DECIMAL", List.of("ENTER_DECIMAL")),
            Map.entry("WAIT_DCR_PROMPT", List.of("DISPLAY_KEY")),
            Map.entry("KEY_PRESS", List.of("KEY_PRESS")),
            Map.entry("L1", List.of()),
            Map.entry("L2", List.of()),
            Map.entry("L3", List.of()),
            Map.entry("L4", List.of()),
            Map.entry("FUEL_AMOUNT", List.of("PUMP NO", "AMOUNT", "HOSE NO", "PREPAY")),
            Map.entry("HANDLE_DOWN", List.of("PUMP NO", "PREPAY")),
            Map.entry("HANDLE_UP", List.of("PUMP NO", "HOSE NO")),
            Map.entry("HTTP_SIM_EVENT", List.of("SELECT_CONTROL_FILE", "SELECT_PAYLOAD_FILE")),
            Map.entry("SEND_HTTP_REQUEST", List.of("COMMAND", "PAYLOAD", "METHOD", "USERNAME", "PASSWORD",
                    "CAPTUREFIELDS", "EXPECTSTATUS", "POLLUNTILFIELD", "POLLUNTILVALUE", "POLLMAXSECONDS",
                    "POLLINTERVALSECONDS", "RETRYENABLED", "ROLE", "HEADERS", "PARAMS", "UPLOADFILE",
                    "FORMFIELDS", "DATEFORMAT")),
            Map.entry("VALIDATE_PAYLOAD", List.of("SELECT_REFERENCE_FILE", "SELECT_RULE_FILE", "VALIDATESCHEMA")));

    /**
     * Fields a handler reads with {@code _require} or {@code raw}, where absent (PATS throws, or falls
     * back to state) is not the same as empty. Every other read is {@code get}, for which they are the same.
     */
    public static final Set<String> REQUIRED = Set.of(
            "AMOUNT", "CARD_NAME", "CLOSE REPORT NO", "CMD", "COMMAND", "DATA", "DEPT", "DISPLAY_KEY",
            "DRAWER_AMOUNT", "ENTER_DECIMAL", "ENTER_KEY", "FLASH REPORTS MENU NO", "FUNCTIONS MENU NO",
            "GALLONS", "GRADE", "INIT_TYPE", "KEY_EVENT_ADDR", "KEY_PRESS", "MAIN MENU NO", "MAIN_MENU_NO",
            "MOP", "NETWORK_MENU_NO", "OTHER_MOP", "PASSWORD", "PAYLOAD", "PLU", "PLUS", "POLLUNTILVALUE",
            "POSITION", "PREPAY", "PUMP", "PUMP NO", "QUANTITY", "QUANTITY_KEY", "REFUND_KEY", "REPORTNAME",
            "REPORT_NO", "SELECT_CONTROL_FILE", "SELECT_FILE", "SELECT_FOLDER", "SELECT_PAYLOAD_FILE",
            "SELECT_REFERENCE_FILE", "SELECT_RULE_FILE", "SELECT_TILL_OPTION", "SLEEP", "USER_ID",
            "VALIDATION KEY", "VALIDATION TEXT", "WAIT TIME[SEC]", "WEB ELEMENT");

    /** One field of a canonical action: its name, and its value, where {@code null} is absent. */
    public record CanonicalField(String name, String value) {
    }

    /** An action reduced to the fields the runner reads, in a fixed order. */
    public record Canonical(String name, List<CanonicalField> fields) {
    }

    private Fields() {
    }

    /**
     * {@code action} as the runner reads it: its name, and every field it reads, in {@link #FIELDS_READ}
     * order after {@code DEVICE} and {@code DEVICEID}. {@code DEVICE} is upper-cased with the POS default;
     * {@code DEVICEID} is kept only when it names a DCR; {@code TYPE[ID/XPATH]} is upper-cased with its ID
     * default. A field in {@link #REQUIRED} keeps absent ({@code null}) apart from empty; any other reads
     * the same either way.
     */
    public static Canonical canonical(RunnableAction action) {
        String name = action.name().toUpperCase(Locale.ROOT);
        String deviceId = action.get("DEVICEID").strip().toUpperCase(Locale.ROOT);
        List<CanonicalField> fields = new ArrayList<>();
        fields.add(new CanonicalField("DEVICE", action.get("DEVICE", "POS").toUpperCase(Locale.ROOT)));
        fields.add(new CanonicalField("DEVICEID", deviceId.startsWith("DCR") ? deviceId : ""));
        for (String field : FIELDS_READ.getOrDefault(name, List.of())) {
            if ("TYPE[ID/XPATH]".equals(field)) {
                fields.add(new CanonicalField(field,
                        action.get(field, "ID").strip().toUpperCase(Locale.ROOT)));
            } else if (REQUIRED.contains(field)) {
                fields.add(new CanonicalField(field, action.raw(field)));
            } else {
                fields.add(new CanonicalField(field, action.get(field)));
            }
        }
        // The exact name, as written: the next-action lookup for SLEEP matches it literally.
        return new Canonical(action.name(), List.copyOf(fields));
    }

    private static List<String> concat(List<String> head, String... tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(List.of(tail));
        return List.copyOf(all);
    }
}
