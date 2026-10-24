package com.verifone.cat.pats.runner;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which fixtures each action needs, so a case's test can declare them up front (the DCR's session
 * listener starts only for a case that names {@code dcr}). Ported from the Python
 * {@code petrotest.pats.runner.registry.FIXTURES_BY_ACTION}, {@code fixtures_for} and
 * {@code prints_at_dcr}.
 *
 * <p>This table is data for the handler families. An action missing from it needs no fixture.
 */
public final class FixtureRequirements {

    private static final Map<String, Set<FixtureName>> BY_ACTION = new HashMap<>();

    static {
        EnumSet<FixtureName> pos = EnumSet.of(FixtureName.POS);
        EnumSet<FixtureName> posRmi = EnumSet.of(FixtureName.POS, FixtureName.LEGACY_RMI);
        EnumSet<FixtureName> posRmiPumps = EnumSet.of(FixtureName.POS, FixtureName.LEGACY_RMI, FixtureName.FUEL_PUMPS);
        EnumSet<FixtureName> posPumps = EnumSet.of(FixtureName.POS, FixtureName.FUEL_PUMPS);
        EnumSet<FixtureName> pumps = EnumSet.of(FixtureName.FUEL_PUMPS);
        EnumSet<FixtureName> dcr = EnumSet.of(FixtureName.DCR);
        EnumSet<FixtureName> pinpad = EnumSet.of(FixtureName.PINPAD);
        EnumSet<FixtureName> commander = EnumSet.of(FixtureName.COMMANDER_CONFIG);
        EnumSet<FixtureName> commanderConfigs = EnumSet.of(FixtureName.COMMANDER_CONFIG, FixtureName.CONFIGURATIONS);

        put(pos, "TOUCH_EVENT", "WIDGET_VALIDATION", "VALIDATION_BY_TEXT", "IS_ELEMENT_PRESENT",
                "IS_ELEMENT_CLICKABLE", "APP_SELECTOR", "VALIDATE_RECEIPT", "VALIDATE_TRANSACTION",
                "ENTER_DRAWER_AMNT", "SELECT_TILL");
        put(posRmi, "SCAN_ITEM", "PRESS_ENTER", "MOP", "OTHER_MOP", "TOTAL", "ADD_ITEM", "QTY", "LOGIN",
                "LOGOUT", "REFUND", "REFUND_WITH_TRANS_MAP", "NETWORK_REPORT", "VOID_TICKET", "VOID_LINE",
                "ERROR_CORRECT", "SUSP", "CONFIRMATION", "POS_KEYBOARD_EVENT", "ADD_DEPT_ITEM",
                "PRINT_FLASH_REPORT", "CLOSE_REPORT", "CASHIER_LOGIN");
        put(posRmi, "VALIDATE_OVERLAY");
        put(posRmiPumps, "PREPAY", "FUEL", "APPROVE");
        put(pos, "ADD_PLU_NUMERIC_KEYPAD", "VALIDATE_DYNAMIC_PROMPTS");
        put(posPumps, "PREPAY_FUEL_AMOUNT", "PREPAY_FUEL_VOLUME");
        put(pumps, "FUEL_AMOUNT", "HANDLE_DOWN", "HANDLE_UP");
        put(dcr, "WAIT_DCR_PROMPT", "KEY_PRESS", "L1", "L2", "L3", "L4", "R1", "R2", "R3", "R4");
        put(pinpad, "WAIT_FOR_PROMPT", "GET_ANY_KEY", "GET_DECIMAL", "REMOVE_CARD");
        put(commander, "VALIDATE_REPORT", "CONTROL_FUNCTION", "SEND_HTTP_REQUEST");
        put(commanderConfigs, "IMPORT_CONFIGURATION");
    }

    private FixtureRequirements() {
    }

    private static void put(Set<FixtureName> needed, String... actions) {
        for (String action : actions) {
            BY_ACTION.put(action, Set.copyOf(needed));
        }
    }

    /** The fixtures an action name needs from the table alone (empty when it needs none). */
    public static Set<FixtureName> forAction(String name) {
        return BY_ACTION.getOrDefault(name.toUpperCase(Locale.ROOT), Set.of());
    }

    /**
     * Whether PATS reads this case's receipts from the DCR's printer, not the POS's. That is a DCR
     * transaction (an action on a {@code DCRn} device) that is not inside a POS sale: the case has an
     * {@code INSERT_OR_SLIDE_CARD} and no {@code FUEL} at the POS.
     */
    public static boolean printsAtDcr(RunnableCase kase) {
        Set<String> names = new HashSet<>();
        boolean atDcr = false;
        for (RunnableAction action : kase.actions()) {
            names.add(action.name().toUpperCase(Locale.ROOT));
            atDcr |= device(action).startsWith("DCR");
        }
        return atDcr && names.contains("INSERT_OR_SLIDE_CARD") && !names.contains("FUEL");
    }

    /** The fixtures {@code kase}'s test must declare, in {@link FixtureName} order. */
    public static List<FixtureName> fixturesFor(RunnableCase kase) {
        EnumSet<FixtureName> needed = EnumSet.noneOf(FixtureName.class);
        for (RunnableAction action : kase.actions()) {
            String name = action.name().toUpperCase(Locale.ROOT);
            if ("INSERT_OR_SLIDE_CARD".equals(name)) {
                needed.add(device(action).startsWith("DCR") ? FixtureName.DCR : FixtureName.PINPAD);
            } else if ("VALIDATE_TRANSACTION".equals(name) && action.get("DEVICEID").toUpperCase(Locale.ROOT).startsWith("DCR")) {
                needed.add(FixtureName.COMMANDER_CONFIG);
            } else {
                needed.addAll(forAction(name));
            }
        }
        if (printsAtDcr(kase)) {
            needed.add(FixtureName.DCR);
        }
        return new ArrayList<>(needed);
    }

    /** The action's device as PATS reads it: upper-cased, POS by default. */
    static String device(RunnableAction action) {
        return action.get("DEVICE", "POS").toUpperCase(Locale.ROOT);
    }
}
