package com.verifone.cat.pats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.pos.LocatorTypes;

/**
 * PATS's BDD cases ({@code -TestCase.scenario}), read into the same {@link Action}s as its XML cases. Port of
 * {@code petrotest.pats.scenario}.
 *
 * <p>In PATS a BDD step is not a different action: each cucumber annotation calls the Java method the XML path
 * calls, with the step's quoted arguments in place of the XML fields. So each step in {@code STEPS} becomes an
 * {@link Action} carrying the field names the XML case would, marked {@code SOURCE=BDD}. A step this table does not
 * know is an {@link UnknownStepException} naming the line; it is never skipped.
 *
 * <p>Where BDD carries what XML leaves to state, a field says so: {@code FUEL_AMOUNT} and {@code HANDLE_DOWN} keep
 * their per-step {@code prepay} as {@code PREPAY}; {@code REFUND_STAN_TRAN_KEY} is {@code REFUND_WITH_TRANS_MAP};
 * {@code GET_ANY_DECIMAL ... on viperpay} is XML's {@code GET_DECIMAL}; and so on, beside each rule.
 *
 * <p>Comment lines ({@code #}) switch a step off and Gherkin tags ({@code @}) are skipped. A file with more than one
 * {@code Scenario:} is read as one case, as PATS's runner executes the whole file.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ScenarioParser {

    /** {@code validateDynamicPromptRows}' column names, in the order CAT writes them. */
    public static final List<String> PROMPT_COLUMNS = List.of(
            "promptWebElement",
            "promptText",
            "promptElementLocatorType",
            "touchActionLocatorType",
            "touchActionWebElements");

    /**
     * How a step's data table reads into its action: {@code LIST} (one value a row, joined into {@code PLUS}),
     * {@code ROWS} (a header row, then one row each, kept as one JSON {@code PROMPTS} field) or {@code MAP}
     * (each {@code | key | value |} row a field named by its key as written).
     */
    enum TableKind {
        LIST,
        ROWS,
        MAP
    }

    static final Map<String, TableKind> TABLES = Map.of(
            "ADD_PLU_NUMERIC_KEYPAD", TableKind.LIST,
            "VALIDATE_DYNAMIC_PROMPTS", TableKind.ROWS,
            "VALIDATE_REPORT", TableKind.MAP,
            "SEND_HTTP_REQUEST", TableKind.MAP,
            "VALIDATE_PAYLOAD", TableKind.MAP);

    private static final String Q = "\"([^\"]*)\"";
    private static final String NUM = "([0-9]+(?:\\.[0-9]+)?)";
    private static final String BOOL = "(true|false|True|False|TRUE|FALSE)";

    private static final Pattern KEYWORD = Pattern.compile("^\\s*(?:Given|When|Then|And|But)\\s+(.*?)\\s*$");
    private static final Pattern TITLE = Pattern.compile("Scenario:\\s*(.*?)\\s*$");

    private record Step(String name, List<Action.Field> fields) {
    }

    private record Rule(Pattern pattern, Function<Matcher, Step> build) {
    }

    /** The step text after Given/When/Then/And, as PATS annotates it; the first match wins. */
    private static final List<Rule> STEPS = List.of(
            rule("SLEEP for ([0-9]+) milliseconds",
                    m -> step("SLEEP", pairs("SLEEP", m.group(1)))),
            rule("TOUCH_EVENT element " + Q + " of type " + Q + " on register with deviceid " + Q,
                    m -> step("TOUCH_EVENT", pos("WEB ELEMENT", m.group(1), "TYPE[ID/XPATH]", upper(m.group(2)),
                            "DEVICEID", m.group(3)))),
            rule("WIDGET_VALIDATION for web element " + Q + " with validation key " + Q + " of type " + Q
                    + " on register with deviceid " + Q,
                    m -> step("WIDGET_VALIDATION", pos("WEB ELEMENT", m.group(1), "VALIDATION KEY", m.group(2),
                            "TYPE[ID/XPATH]", upper(m.group(3)), "DEVICEID", m.group(4)))),
            rule("VALIDATION_BY_TEXT for web element " + Q + " of type " + Q + " is displaying " + Q
                    + " on register with deviceid " + Q,
                    m -> step("VALIDATION_BY_TEXT", pos("WEB ELEMENT", m.group(1), "TYPE[ID/XPATH]",
                            upper(m.group(2)), "VALIDATION TEXT", m.group(3), "DEVICEID", m.group(4)))),
            rule("IS_ELEMENT_PRESENT on web element " + Q + " and wait for element to appear " + Q + " of type " + Q
                    + " on register with deviceid " + Q,
                    m -> step("IS_ELEMENT_PRESENT", pos("WEB ELEMENT", m.group(1), "YES/NO", m.group(2),
                            "TYPE[ID/XPATH]", upper(m.group(3)), "DEVICEID", m.group(4)))),
            rule("VALIDATE_OVERLAY with title " + Q + " line1=" + Q + " line2=" + Q + " line3=" + Q + " yes_no " + Q
                    + " on register with deviceid " + Q,
                    m -> step("VALIDATE_OVERLAY", pos("TITLE", m.group(1), "LINE1", m.group(2), "LINE2", m.group(3),
                            "LINE3", m.group(4), "YES/NO", m.group(5), "DEVICEID", m.group(6)))),
            rule("(VALIDATE_RECEIPT|VALIDATE_TRANSACTION) with reference file " + Q + " and rule file " + Q
                    + " on deviceid " + Q,
                    m -> step(m.group(1), pairs("SELECT_FILE", m.group(2), "SELECT_RULE_FILE", m.group(3),
                            "DEVICEID", m.group(4)))),
            rule("REMOVE_CARD from terminal " + Q,
                    m -> step("REMOVE_CARD", pairs("DEVICEID", m.group(1), "DEVICE", "VIPERPAY"))),
            rule("WAIT_FOR_PROMPT for display text " + Q + " on terminal " + Q + " with exactMatch " + Q,
                    m -> step("WAIT_FOR_PROMPT", pairs("DISPLAY_KEY", m.group(1), "DEVICEID", m.group(2),
                            "DISPLAY_KEY_EXACT_MATCH[YES/NO]", m.group(3), "DEVICE", "VIPERPAY"))),
            rule("INSERT_OR_SLIDE_CARD with card label " + Q + " and card image file " + Q + " on terminal " + Q,
                    m -> step("INSERT_OR_SLIDE_CARD", pairs("CARD_NAME", m.group(1),
                            "CARD_SIMULATION_IMAGE_FILE", m.group(2).strip(), "DEVICEID", m.group(3),
                            "DEVICE", "VIPERPAY"))),
            rule("GET_DECIMAL input data " + Q + " on terminal " + Q,
                    m -> step("GET_DECIMAL", pairs("ENTER_DECIMAL", m.group(1), "DEVICEID", m.group(2),
                            "DEVICE", "VIPERPAY"))),
            rule("GET_ANY_KEY key press " + Q + " on terminal " + Q,
                    m -> step("GET_ANY_KEY", pairs("ENTER_KEY", m.group(1), "DEVICEID", m.group(2),
                            "DEVICE", "VIPERPAY"))),
            rule("FUEL_AMOUNT for amount " + NUM + " at pump ([0-9]+) using hose ([0-9]+) prepay " + BOOL,
                    m -> step("FUEL_AMOUNT", pairs("AMOUNT", m.group(1), "PUMP NO", m.group(2), "HOSE NO",
                            m.group(3), "PREPAY", lower(m.group(4))))),
            rule("HANDLE_DOWN on pump ([0-9]+) prepay " + BOOL,
                    m -> step("HANDLE_DOWN", pairs("PUMP NO", m.group(1), "PREPAY", lower(m.group(2))))),
            rule("ADD_PLU_NUMERIC_KEYPAD with the following PLUs on register with deviceid " + Q,
                    m -> step("ADD_PLU_NUMERIC_KEYPAD", pos("DEVICEID", m.group(1)))),
            rule("PREPAY_FUEL_AMOUNT on pump " + Q + " of amount " + Q + " on register with deviceid " + Q,
                    m -> step("PREPAY_FUEL_AMOUNT", pos("PUMP NO", m.group(1), "AMOUNT", m.group(2),
                            "DEVICEID", m.group(3)))),
            rule("PREPAY_FUEL_VOLUME on pump " + Q + " for gallons " + Q + " of grade " + Q
                    + " on register with deviceid " + Q,
                    m -> step("PREPAY_FUEL_VOLUME", pos("PUMP NO", m.group(1), "GALLONS", m.group(2),
                            "GRADE", m.group(3), "DEVICEID", m.group(4)))),
            rule("STORE_TRANS_MAP with transKey " + Q + " on register with deviceid " + Q,
                    m -> step("STORE_TRANS_MAP", pos("REFUND_KEY", m.group(1), "DEVICEID", m.group(2)))),
            rule("REFUND_STAN_TRAN_KEY with transKey " + Q + " on register with deviceid " + Q,
                    m -> step("REFUND_WITH_TRANS_MAP", pos("REFUND_KEY", m.group(1), "DEVICEID", m.group(2)))),
            rule("PRESS_ENTER key " + Q + " on register with deviceid " + Q,
                    m -> step("PRESS_ENTER", pos("DATA", m.group(1), "DEVICEID", m.group(2)))),
            rule("SCAN_ITEM file " + Q + " on register with deviceid " + Q,
                    m -> step("SCAN_ITEM", pos("SELECT_FILE", m.group(1), "DEVICEID", m.group(2)))),
            // The steps for the Naxml, Smart Suggestion, TaxVisitor, PCI-MIN-AGE, Control Commands and Tipping
            // plans (Automn.jar's @When annotations). PaymentTerminalClient's "on viperpay" steps are the methods the
            // XML VIPERPAY handler calls; the register a step names is not passed on.
            rule("INSERT_OR_SLIDE_CARD with card label " + Q + " on viperpay " + Q,
                    m -> step("INSERT_OR_SLIDE_CARD", viperpay("CARD_NAME", m.group(1), "DEVICEID", m.group(2)))),
            // DCR.waitForPrompt: the BDD wording of XML's WAIT_DCR_PROMPT.
            rule("WAIT_DCR_PROMPT for display text " + Q + " on dcr position " + Q + " with exactMatch " + Q,
                    m -> step("WAIT_DCR_PROMPT", pairs("DISPLAY_KEY", m.group(1), "DEVICE", m.group(2)))),
            // DCR.insertOrSlideCard: XML's INSERT_OR_SLIDE_CARD on a DCR. The card type is left out, as XML's is.
            rule("INSERT_OR_SLIDE_CARD of card type " + Q + " with card name " + Q + " at dcr position " + Q,
                    m -> step("INSERT_OR_SLIDE_CARD", pairs("CARD_NAME", m.group(2), "DEVICE", m.group(3)))),
            // DCR.FunctionKEY: l1..r4 is XML's own L1..R4 action.
            rule("FUNCTION_KEY select function key \"([lLrR][1-4])\" at dcr position " + Q,
                    m -> step(upper(m.group(1)), pairs("DEVICE", m.group(2)))),
            // DCR.NumericKEY: XML's KEY_PRESS.
            rule("KEY_PRESS key " + Q + " on dcr with deviceid " + Q,
                    m -> step("KEY_PRESS", pairs("KEY_PRESS", m.group(1), "DEVICE", m.group(2)))),
            // POSDevice.validateDynamicPrompts (BDD only): the host decides which prompts come, and in what order.
            rule("VALIDATE_DYNAMIC_PROMPTS on register " + Q,
                    m -> step("VALIDATE_DYNAMIC_PROMPTS", pos("DEVICEID", m.group(1)))),
            rule("GET_ANY_KEY key press " + Q + " on viperpay " + Q,
                    m -> step("GET_ANY_KEY", viperpay("ENTER_KEY", m.group(1), "DEVICEID", m.group(2)))),
            // getAnyDecimal is what XML's GET_DECIMAL calls on a ViperPay.
            rule("GET_ANY_DECIMAL input data " + Q + " on viperpay " + Q,
                    m -> step("GET_DECIMAL", viperpay("ENTER_DECIMAL", m.group(1), "DEVICEID", m.group(2)))),
            rule("WAIT_FOR_PROMPT for display text " + Q + " on viperpay " + Q + " with exactMatch " + Q,
                    m -> step("WAIT_FOR_PROMPT", viperpay("DISPLAY_KEY", m.group(1), "DEVICEID", m.group(2),
                            "DISPLAY_KEY_EXACT_MATCH[YES/NO]", m.group(3)))),
            // SiteControllerService and CommonActionManager: the XML handlers' own calls.
            rule("IMPORT_CONFIGURATION folder " + Q,
                    m -> step("IMPORT_CONFIGURATION", pairs("SELECT_FOLDER", m.group(1), "DEVICE", "SITECONTROLLER"))),
            rule("CONTROL_FUNCTION init type " + Q,
                    m -> step("CONTROL_FUNCTION", pairs("INIT_TYPE", m.group(1), "DEVICE", "SITECONTROLLER"))),
            rule("RESET_PACKETS for device " + Q,
                    m -> step("RESET_PACKETS", pairs("DEVICE", m.group(1)))),
            rule("UPDATE_CONFIGURATION for device " + Q + " with config file " + Q,
                    m -> step("UPDATE_CONFIGURATION", pairs("DEVICE", m.group(1), "SELECT_FILE", m.group(2)))),
            rule("REPORT_PRINT on register with deviceid " + Q,
                    m -> step("REPORT_PRINT", pos("DEVICEID", m.group(1)))),
            // The data table is ReportValidator's map, the XML fields lower-cased.
            rule("VALIDATE_REPORT with reference file " + Q + " and rule file " + Q + " on deviceid " + Q,
                    m -> step("VALIDATE_REPORT", pos("SELECT_FILE", m.group(1), "SELECT_RULE_FILE", m.group(2),
                            "DEVICEID", m.group(3)))),
            // printFlashReport(mMenuNo, funMenuNo, ...) takes the arguments in order, so functions_menu_no is keyed
            // first: it is XML's MAIN MENU NO.
            rule("PRINT_FLASH_REPORT with functions_menu_no " + Q + " main_menu_no " + Q
                    + " flash_report_menu_no " + Q + " on register " + Q,
                    m -> step("PRINT_FLASH_REPORT", pos("MAIN MENU NO", m.group(1), "FUNCTIONS MENU NO", m.group(2),
                            "FLASH REPORTS MENU NO", m.group(3), "DEVICEID", m.group(4)))),
            rule("CASHIER_LOGIN on register with deviceid " + Q,
                    m -> step("CASHIER_LOGIN", pos("DEVICEID", m.group(1)))),
            rule("APP_SELECTOR select element " + Q + " of type " + Q + " on register with deviceid " + Q,
                    m -> step("APP_SELECTOR", pos("WEB ELEMENT", m.group(1), "TYPE[ID/XPATH]", upper(m.group(2)),
                            "DEVICEID", m.group(3)))),
            // CloudActionHandler.sendHttpRequestWithPayloadFile(type, url, payload).
            rule("CLOUD_HTTP_REQUEST of type " + Q + " is sent to url " + Q + " with payload " + Q,
                    m -> step("CLOUD_HTTP_REQUEST", pairs("HTTP_METHOD", m.group(1), "CONFIGURATION_URL", m.group(2),
                            "SELECT_PAYLOAD_FILE", m.group(3), "DEVICE", "C-SITE"))),
            // SendHttpRequestActionHandler.sendHttpRequest(command, payload, table). BDD only.
            rule("SEND_HTTP_REQUEST using command " + Q + " with payload " + Q,
                    m -> step("SEND_HTTP_REQUEST", pairs("COMMAND", m.group(1), "PAYLOAD", m.group(2)))),
            // CommonActionManager.validatePayload(reference, rule, table). BDD only.
            rule("VALIDATE_PAYLOAD with reference file " + Q + " and rule file " + Q,
                    m -> step("VALIDATE_PAYLOAD", pairs("SELECT_REFERENCE_FILE", m.group(1),
                            "SELECT_RULE_FILE", m.group(2)))));

    private ScenarioParser() {
    }

    /**
     * {@code (scenario title, actions)} from a {@code .scenario} file's text.
     *
     * @throws UnknownStepException for a line no step matches, a table under a step that takes none, or a table
     *                              whose shape the step cannot read
     */
    public static ParsedCase parseScenario(String text) {
        String title = "";
        List<Action> actions = new ArrayList<>();
        List<List<String>> rows = null; // the open step's data table, if it takes one
        List<String> lines = lines(text);
        for (int number = 1; number <= lines.size(); number++) {
            String line = lines.get(number - 1);
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) {
                continue;
            }
            if (line.startsWith("|")) {
                if (rows == null) {
                    throw new UnknownStepException("line " + number + ": a table row with no step that takes one: "
                            + LocatorTypes.pyRepr(line));
                }
                rows.add(cells(line));
                continue;
            }
            closeTable(actions, rows);
            rows = null;
            Matcher titleMatch = TITLE.matcher(line);
            if (titleMatch.lookingAt()) {
                title = title.isEmpty() ? titleMatch.group(1) : title;
                continue;
            }
            Matcher keyword = KEYWORD.matcher(line);
            if (!keyword.matches()) {
                throw new UnknownStepException("line " + number + ": not a Given/When/Then step: "
                        + LocatorTypes.pyRepr(line));
            }
            String step = keyword.group(1);
            Rule matched = null;
            Matcher found = null;
            for (Rule rule : STEPS) {
                Matcher m = rule.pattern().matcher(step);
                if (m.lookingAt()) {
                    matched = rule;
                    found = m;
                    break;
                }
            }
            if (matched == null) {
                throw new UnknownStepException("line " + number + ": no PATS step matches "
                        + LocatorTypes.pyRepr(step));
            }
            Step built = matched.build().apply(found);
            actions.add(new Action(built.name(), withSource(built.fields()), actions.size()));
            if (TABLES.containsKey(built.name())) {
                rows = new ArrayList<>();
            }
        }
        closeTable(actions, rows);
        return new ParsedCase(title, actions);
    }

    /**
     * VALIDATE_DYNAMIC_PROMPTS' table as its one {@code PROMPTS} field: JSON, each row's columns in
     * {@link #PROMPT_COLUMNS} order and trimmed, the touch list as written but for spaces after its commas. The same
     * text whichever of a {@code .scenario} or a {@code .feature} it was read from.
     *
     * @throws UnknownStepException when a row lacks one of the columns
     */
    public static String dynamicPromptsField(List<Map<String, String>> rows) {
        List<String> missing = new ArrayList<>();
        for (String column : PROMPT_COLUMNS) {
            if (rows.stream().anyMatch(row -> !row.containsKey(column))) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new UnknownStepException("VALIDATE_DYNAMIC_PROMPTS: a row with no " + String.join(", ", missing));
        }
        List<Map<String, String>> shaped = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> cells = new LinkedHashMap<>();
            for (String column : PROMPT_COLUMNS) {
                cells.put(column, row.get(column).strip());
            }
            cells.put("touchActionWebElements", joinTouchList(cells.get("touchActionWebElements")));
            shaped.add(cells);
        }
        return json(shaped);
    }

    private static String joinTouchList(String list) {
        List<String> parts = new ArrayList<>();
        for (String part : list.split(",", -1)) {
            String stripped = part.strip();
            if (!stripped.isEmpty()) {
                parts.add(stripped);
            }
        }
        return String.join(",", parts);
    }

    /** A JSON array of string-valued objects, compact, escaped as Python's {@code json.dumps} (ASCII only). */
    private static String json(List<Map<String, String>> rows) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            out.append(i == 0 ? "" : ",").append('{');
            int j = 0;
            for (Map.Entry<String, String> cell : rows.get(i).entrySet()) {
                out.append(j++ == 0 ? "" : ",");
                quote(out, cell.getKey());
                out.append(':');
                quote(out, cell.getValue());
            }
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static Rule rule(String regex, Function<Matcher, Step> build) {
        return new Rule(Pattern.compile(regex + "\\s*$"), build);
    }

    private static Step step(String name, List<Action.Field> fields) {
        return new Step(name, fields);
    }

    private static List<Action.Field> withSource(List<Action.Field> fields) {
        List<Action.Field> out = new ArrayList<>(fields);
        out.add(new Action.Field("SOURCE", "BDD"));
        return out;
    }

    /** {@code name, value, name, value, ...} as fields, in the order written. */
    private static List<Action.Field> pairs(String... kv) {
        List<Action.Field> out = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.add(new Action.Field(kv[i], kv[i + 1]));
        }
        return out;
    }

    /** A POS step: the given fields, then {@code DEVICE=POS}. */
    private static List<Action.Field> pos(String... kv) {
        return withDevice(kv, "POS");
    }

    /** A ViperPay step: the given fields, then {@code DEVICE=VIPERPAY}. */
    private static List<Action.Field> viperpay(String... kv) {
        return withDevice(kv, "VIPERPAY");
    }

    private static List<Action.Field> withDevice(String[] kv, String device) {
        List<Action.Field> out = pairs(kv);
        out.add(new Action.Field("DEVICE", device));
        return out;
    }

    private static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * Physical lines, stripped, with a {@code Scenario:} glued onto a step's end split back off (PETROCORE-124883 ends
     * a step and starts its title on one line).
     */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
            String line = raw.strip();
            int cut = line.indexOf("Scenario:");
            if (cut > 0) {
                out.add(line.substring(0, cut));
                out.add(line.substring(cut));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    /** A Gherkin table row's cells, each trimmed; an empty cell stays ({@code |reporttype||}). */
    private static List<String> cells(String line) {
        String inner = line.strip();
        inner = inner.endsWith("|") && inner.length() > 1
                ? inner.substring(1, inner.length() - 1)
                : inner.substring(1);
        List<String> out = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            out.add(cell.strip());
        }
        return out;
    }

    /** Closes the open table on the step before it: that step's action gains the table's fields. */
    private static void closeTable(List<Action> actions, List<List<String>> rows) {
        if (rows == null) {
            return;
        }
        Action last = actions.get(actions.size() - 1);
        if (rows.isEmpty() && TABLES.get(last.name()) == TableKind.MAP) {
            return;
        }
        List<Action.Field> fields = new ArrayList<>(last.fields());
        fields.addAll(tableFields(last.name(), rows));
        actions.set(actions.size() - 1, new Action(last.name(), fields, last.index()));
    }

    /** The fields a step's data table gives its action. */
    private static List<Action.Field> tableFields(String name, List<List<String>> rows) {
        TableKind kind = TABLES.get(name);
        if (kind == TableKind.LIST) {
            List<String> plus = new ArrayList<>();
            for (List<String> row : rows) {
                for (String cell : row) {
                    if (!cell.isEmpty()) {
                        plus.add(cell);
                    }
                }
            }
            return pairs("PLUS", String.join(",", plus));
        }
        if (kind == TableKind.ROWS) {
            if (rows.isEmpty() || rows.stream().anyMatch(row -> row.size() != rows.get(0).size())) {
                throw new UnknownStepException(name + ": a table whose rows do not match its header: " + rows);
            }
            List<String> header = rows.get(0);
            List<Map<String, String>> shaped = new ArrayList<>();
            for (List<String> row : rows.subList(1, rows.size())) {
                Map<String, String> cells = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    cells.put(header.get(i), row.get(i));
                }
                shaped.add(cells);
            }
            return pairs("PROMPTS", dynamicPromptsField(shaped));
        }
        List<Action.Field> fields = new ArrayList<>();
        for (List<String> row : rows) {
            if (row.size() != 2) {
                throw new UnknownStepException(name + ": a table row that is not | key | value |: " + row);
            }
            fields.add(new Action.Field(row.get(0), row.get(1)));
        }
        return fields;
    }
}
