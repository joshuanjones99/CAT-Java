package com.verifone.cat.pats.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which fields of each PATS action the runner reads, and an action as the runner sees it.
 *
 * <p>Port of the parts of {@code petrotest.pats.runner.fields} this package needs: {@code FIELDS_READ}, {@code REQUIRED}
 * and {@code canonical}. The tables are generated from the Python's by a script. The PATS runner's own table is the one
 * to keep: the orchestrator unifies the two at merge, and checks that every field a handler reads is listed.
 * UNVERIFIED AGAINST HARDWARE.
 */
final class Fields {

    private static final String ELEMENT_FIELD = "TYPE[ID/XPATH]";

    /** Per action, the fields its handler reads. */
    static final Map<String, List<String>> FIELDS_READ = Map.ofEntries(
            Map.entry("SLEEP", List.of("SLEEP")),
            Map.entry("TOUCH_EVENT", List.of("WEB ELEMENT", "TYPE[ID/XPATH]")),
            Map.entry("WIDGET_VALIDATION", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "VALIDATION KEY")),
            Map.entry("VALIDATION_BY_TEXT", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "VALIDATION TEXT")),
            Map.entry("IS_ELEMENT_PRESENT", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "YES/NO")),
            Map.entry("IS_ELEMENT_CLICKABLE", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "YES/NO", "WAIT TIME[SEC]")),
            Map.entry("APP_SELECTOR", List.of("WEB ELEMENT", "TYPE[ID/XPATH]")),
            Map.entry("CASHIER_LOGIN", List.of()),
            Map.entry("VALIDATE_OVERLAY", List.of("TITLE", "LINE1", "LINE2", "LINE3", "YES/NO")),
            Map.entry("SCAN_ITEM", List.of("SELECT_FILE")),
            Map.entry("PRESS_ENTER", List.of("DATA")),
            Map.entry("MOP", List.of("MOP", "DATA")),
            Map.entry("OTHER_MOP", List.of("OTHER_MOP", "DATA", "POSITION", "SPECIALDISCOUNT")),
            Map.entry("TOTAL", List.of()),
            Map.entry("ADD_ITEM", List.of("PLU", "QUANTITY", "MODIFIER", "IDC", "PRICEOVER", "PLUNOTFUND PRICE", "DISCOUNT", "DISCOUNT KEY", "SELECT SOFTKEY", "USING MENU", "CHOICE", "TAX EXC", "SELECT_FILE")),
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
            Map.entry("NETWORK_REPORT", List.of("MAIN_MENU_NO", "NETWORK_MENU_NO", "REPORT_NO", "SUB_REPORT_NO", "IS_SECURE_REPORT", "SECURE_USER", "SECURE_PASSWORD", "DAY_SEQUENCE", "BATCH_NUMBER")),
            Map.entry("VALIDATE_RECEIPT", List.of("SELECT_FILE")),
            Map.entry("VALIDATE_TRANSACTION", List.of("SELECT_FILE", "SELECT_RULE_FILE")),
            Map.entry("VALIDATE_REPORT", List.of("CMD", "REPORTNAME", "REPORTTYPE", "FILENAME", "PERIOD", "CASHIERNUM", "TOPAZNUM", "SELECT_FILE", "SELECT_RULE_FILE")),
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
            Map.entry("ADD_DEPT_ITEM", List.of("DEPT", "AMOUNT", "DEPARTMENT NO", "OTHER DEPARTMENT", "QUANTITY", "@", "TAX EXC", "DISCOUNT", "IDC")),
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
            Map.entry("SEND_HTTP_REQUEST", List.of("COMMAND", "PAYLOAD", "METHOD", "USERNAME", "PASSWORD", "CAPTUREFIELDS", "EXPECTSTATUS", "POLLUNTILFIELD", "POLLUNTILVALUE", "POLLMAXSECONDS", "POLLINTERVALSECONDS", "RETRYENABLED", "ROLE", "HEADERS", "PARAMS", "UPLOADFILE", "FORMFIELDS", "DATEFORMAT")),
            Map.entry("VALIDATE_PAYLOAD", List.of("SELECT_REFERENCE_FILE", "SELECT_RULE_FILE", "VALIDATESCHEMA"))
    );

    /** Fields where a field that is absent is not the same as one that is empty. */
    static final Set<String> REQUIRED = Set.of(
            "AMOUNT",
            "CARD_NAME",
            "CLOSE REPORT NO",
            "CMD",
            "COMMAND",
            "DATA",
            "DEPT",
            "DISPLAY_KEY",
            "DRAWER_AMOUNT",
            "ENTER_DECIMAL",
            "ENTER_KEY",
            "FLASH REPORTS MENU NO",
            "FUNCTIONS MENU NO",
            "GALLONS",
            "GRADE",
            "INIT_TYPE",
            "KEY_EVENT_ADDR",
            "KEY_PRESS",
            "MAIN MENU NO",
            "MAIN_MENU_NO",
            "MOP",
            "NETWORK_MENU_NO",
            "OTHER_MOP",
            "PASSWORD",
            "PAYLOAD",
            "PLU",
            "PLUS",
            "POLLUNTILVALUE",
            "POSITION",
            "PREPAY",
            "PUMP",
            "PUMP NO",
            "QUANTITY",
            "QUANTITY_KEY",
            "REFUND_KEY",
            "REPORTNAME",
            "REPORT_NO",
            "SELECT_CONTROL_FILE",
            "SELECT_FILE",
            "SELECT_FOLDER",
            "SELECT_PAYLOAD_FILE",
            "SELECT_REFERENCE_FILE",
            "SELECT_RULE_FILE",
            "SELECT_TILL_OPTION",
            "SLEEP",
            "USER_ID",
            "VALIDATION KEY",
            "VALIDATION TEXT",
            "WAIT TIME[SEC]",
            "WEB ELEMENT"
    );

    private Fields() {}

    /** An action reduced to the fields the runner reads: its name, and each field's value (null when absent, in REQUIRED). */
    record Canonical(String name, List<Action.Field> fields) {}

    /**
     * {@code action} as the runner reads it: its name, then {@code DEVICE} (POS by default), {@code DEVICEID} (kept only
     * when it names a DCR), then every field its handler reads, in {@link #FIELDS_READ} order.
     */
    static Canonical canonical(Action action) {
        String name = action.name().toUpperCase(Locale.ROOT);
        String deviceId = action.get("DEVICEID").strip().toUpperCase(Locale.ROOT);
        List<Action.Field> fields = new ArrayList<>();
        fields.add(new Action.Field("DEVICE", action.get("DEVICE", "POS").toUpperCase(Locale.ROOT)));
        fields.add(new Action.Field("DEVICEID", deviceId.startsWith("DCR") ? deviceId : ""));
        for (String field : FIELDS_READ.getOrDefault(name, List.of())) {
            if (field.equals(ELEMENT_FIELD)) {
                fields.add(new Action.Field(field, action.get(field, "ID").strip().toUpperCase(Locale.ROOT)));
            } else if (REQUIRED.contains(field)) {
                fields.add(new Action.Field(field, action.raw(field)));
            } else {
                fields.add(new Action.Field(field, action.get(field)));
            }
        }
        return new Canonical(action.name(), fields);
    }
}
