package com.verifone.cat.devices.forecourt;

import static com.verifone.cat.devices.forecourt.JavaSerial.Field;
import static com.verifone.cat.devices.forecourt.JavaSerial.JavaClass;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The requests CAT sends Petro Simulator Suite, and the classes they are written as. Ported from the Python
 * {@code petro_forecourt} module's encoders.
 *
 * <p>Each encoder writes the object the way PATS's own client does: a {@code Status[]} login to the pumps, a bare
 * {@code Status} to the DCRs, and so on. The bytes are checked against JDK-written fixtures in the tests.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ForecourtWire {

    static final String FUEL_ENT = "com.verifone.isd.pcapps.simulators.forecourt.fuel.comm.ent.";
    static final String DCR_ENT = "com.verifone.isd.pcapps.simulators.forecourt.dcr.comm.ent.";

    static final JavaClass STATUS = new JavaClass(
            "com.verifone.isd.pcapps.simulators.comm.pres.Status", 4217523255449182833L,
            List.of(Field.prim('Z', "firstTimeLogging")), null);
    static final JavaClass STATUS_ARRAY = new JavaClass(
            "[Lcom.verifone.isd.pcapps.simulators.comm.pres.Status;", -8115033481833181988L);
    static final JavaClass HANDLE_UP = new JavaClass(FUEL_ENT + "FuelHandleUpEntity", 1L, List.of(
            Field.prim('S', "fuelingPosition"),
            Field.prim('S', "hoseNum"),
            Field.prim('Z', "isFirstTimeLogin"),
            Field.prim('Z', "isHandleUp"),
            Field.prim('D', "presetAmount")), null);
    static final JavaClass HANDLE_UP_ARRAY = new JavaClass("[L" + HANDLE_UP.name() + ";", 5054989764807258735L);
    static final JavaClass HANDLE_DOWN = new JavaClass(FUEL_ENT + "FuelHandleDownEntity", 1L, List.of(
            Field.prim('S', "fuelingPosition"),
            Field.prim('D', "presetAmount")), null);
    static final JavaClass HANDLE_DOWN_ARRAY = new JavaClass("[L" + HANDLE_DOWN.name() + ";", -1372194175888070987L);
    static final JavaClass KEY_DATA = new JavaClass("com.verifone.isd.psc.fcc.dcr.comm.DcrKeyDataInfo",
            -5090109009383177134L, List.of(Field.prim('C', "column"), Field.prim('C', "row")), null);
    static final JavaClass KEY_PRESSED = new JavaClass(DCR_ENT + "KeyPressedParameters", 5108236084757643384L,
            List.of(
                    new Field('L', "dcrKeyDataInfo", "Lcom/verifone/isd/psc/fcc/dcr/comm/DcrKeyDataInfo;"),
                    new Field('L', "keyNumber", "Ljava/lang/String;"),
                    new Field('[', "position", "[Ljava/lang/Short;")),
            null);
    static final JavaClass CARD_LABEL = new JavaClass(DCR_ENT + "CardLabel", -5005484007985777001L, List.of(
            Field.prim('I', "limitCardSwipe"),
            new Field('L', "cardLabel", "Ljava/lang/String;"),
            new Field('[', "position", "[Ljava/lang/Short;")), null);
    static final JavaClass CHAR_ARRAY = new JavaClass("[C", -5753798564021173076L);
    static final JavaClass KEYBOARD_DATA = new JavaClass("com.verifone.isd.psc.fcc.dcr.comm.DcrKeyboardDataInfo",
            -4476146832652443965L, List.of(
                    Field.prim('Z', "emvAbort"),
                    new Field('[', "keyboardData", "[C")), null);
    static final JavaClass KEYBOARD = new JavaClass(DCR_ENT + "KeyboardParameters", 4984172870354742289L, List.of(
            new Field('L', "dcrKeyboardDataInfo", "Lcom/verifone/isd/psc/fcc/dcr/comm/DcrKeyboardDataInfo;"),
            new Field('[', "position", "[Ljava/lang/Short;")), null);

    /** PATS's DCR soft keys: {@code l1}..{@code r4} as the (column, row) the suite's key message carries. */
    private static final Map<String, String[]> DCR_KEYS = Map.of(
            "l1", new String[] {"L", "4"},
            "l2", new String[] {"L", "3"},
            "l3", new String[] {"L", "2"},
            "l4", new String[] {"L", "1"},
            "r1", new String[] {"R", "4"},
            "r2", new String[] {"R", "3"},
            "r3", new String[] {"R", "2"},
            "r4", new String[] {"R", "1"});

    /** The DCR's reply to a display push, as a bare {@code Status}. */
    static final byte[] DCR_ACK = JavaSerial.dumps(new JavaSerial.Obj(STATUS));

    private ForecourtWire() {
    }

    /** PATS's login: a {@code Status[]} to the pumps, a bare {@code Status} to the DCRs. */
    public static byte[] encodeLogin(boolean array) {
        JavaSerial.Obj status = new JavaSerial.Obj(STATUS, Map.of("firstTimeLogging", true));
        if (!array) {
            return JavaSerial.dumps(status);
        }
        return JavaSerial.dumps(new JavaSerial.Arr(STATUS_ARRAY, List.of(status)));
    }

    /** {@code PumpManager.fillAmountFuel}'s request. */
    public static byte[] encodeHandleUp(int pump, BigDecimal amount, int hose) {
        JavaSerial.Obj entity = new JavaSerial.Obj(HANDLE_UP, Map.of(
                "fuelingPosition", pump,
                "hoseNum", hose,
                "presetAmount", amount.doubleValue(),
                "isHandleUp", true));
        return JavaSerial.dumps(new JavaSerial.Arr(HANDLE_UP_ARRAY, List.of(entity)));
    }

    /** {@code PumpManager.handleDown}'s request, carrying the last fill's amount (none when null). */
    public static byte[] encodeHandleDown(int pump, BigDecimal amount) {
        JavaSerial.Obj entity = new JavaSerial.Obj(HANDLE_DOWN, Map.of(
                "fuelingPosition", pump,
                "presetAmount", amount == null ? 0.0 : amount.doubleValue()));
        return JavaSerial.dumps(new JavaSerial.Arr(HANDLE_DOWN_ARRAY, List.of(entity)));
    }

    /** {@code DCRManager.funKeyPress}'s request for PATS's {@code L1}..{@code R4}. */
    public static byte[] encodeDcrKey(int dcr, String key) {
        String[] position = DCR_KEYS.get(key.toLowerCase(java.util.Locale.ROOT));
        if (position == null) {
            throw new IllegalArgumentException("not a DCR soft key: " + key);
        }
        JavaSerial.Obj info = new JavaSerial.Obj(KEY_DATA, Map.of("column", position[0], "row", position[1]));
        return JavaSerial.dumps(new JavaSerial.Obj(KEY_PRESSED, Map.of("dcrKeyDataInfo", info, "position", positions(dcr))));
    }

    /** {@code DCRManager.cardAction}'s request for PATS's DCR {@code INSERT_OR_SLIDE_CARD}. */
    public static byte[] encodeDcrCard(int dcr, String label) {
        return JavaSerial.dumps(new JavaSerial.Obj(CARD_LABEL, Map.of("cardLabel", label, "position", positions(dcr))));
    }

    /** {@code DCRManager.numericKeyPress}'s request: PATS's DCR {@code KEY_PRESS}, all of it in one message. */
    public static byte[] encodeDcrKeyboard(int dcr, String chars) {
        List<Integer> codes = chars.chars().boxed().toList();
        JavaSerial.Obj data = new JavaSerial.Obj(KEYBOARD_DATA, Map.of("keyboardData", new JavaSerial.Arr(CHAR_ARRAY, codes)));
        return JavaSerial.dumps(new JavaSerial.Obj(KEYBOARD, Map.of("dcrKeyboardDataInfo", data, "position", positions(dcr))));
    }

    private static JavaSerial.Arr positions(int dcr) {
        return new JavaSerial.Arr(JavaSerial.SHORT_ARRAY,
                List.of(new JavaSerial.Obj(JavaSerial.SHORT, Map.of("value", dcr))));
    }

    /** The object a decoded stream holds under {@code key} as an {@code int}, or empty when it is not a number. */
    static Optional<Integer> intField(Map<?, ?> obj, String key) {
        return obj.get(key) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }
}
