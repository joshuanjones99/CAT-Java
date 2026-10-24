package com.verifone.cat.pats.runner.handlers.sale;

import com.verifone.cat.devices.DeviceUnavailable;

/**
 * The register-side calls a sale makes: the keys, items, tenders, fuel and voids that PATS's {@code LegacyRmi}
 * sends to the POS (through the pos-shim in CAT's design). Ported from the Python {@code run.rmi}.
 *
 * <p>No Java implementation sends these yet, so {@link #UNAVAILABLE} throws {@link DeviceUnavailable} for every call.
 * A later implementation over {@code posshim.PosShimClient}'s key path replaces it; the family does not change.
 */
public interface SaleRmi {

    /** The RMI calls that are not wired: every call throws {@link DeviceUnavailable}. */
    SaleRmi UNAVAILABLE = new Unavailable();

    /** A scanner token: the {@code scannerdata} file's fields, as the shim takes them. */
    record ScanItem(String scanData, int scanDataType, boolean decodeData, String scanDataLabel, int status) {
    }

    /** An item sale, as PATS keys it. {@code scan} presents the item's scanner file when the item is scanned. */
    record AddItem(String plu, String taxExempt, String modifier, String discount, String discountKey,
            String quantity, String idc, String priceOver, String pluNotFoundPrice, String usingMenu,
            String selectSoftkey, String choice, String selectFile, Runnable scan) {
    }

    /** A department item, as {@code add_dept_item} keys it. */
    record AddDeptItem(String dept, String amount, String departmentNo, String otherDepartment, String quantity,
            String pack, String taxExempt, String discount, String idc) {
    }

    /** Presses a sequence of keys (the shim's {@code press_keys}). */
    void pressKeys(String keys);

    /** Presses one key (the shim's {@code press_key}). */
    void pressKey(String key);

    /** ENTER, bare when {@code data} is null or "0", else ENTER carrying the data. */
    void pressEnter(String data);

    /** A key carrying data, as the shim's {@code press_with_data}. */
    void pressWithData(String key, String data);

    /** Presents a scanner token. */
    void scanItem(ScanItem item);

    /** A cash or card tender by MOP. */
    void mop(String mop, String data);

    /** Another tender, by its position on the tender screen. */
    void otherMop(String otherMop, String data, String position, String specialDiscount);

    /** TOTAL. */
    void total();

    /** An item by PLU, keyed through the item keys. */
    void addItem(AddItem item);

    /** A department item. */
    void addDeptItem(AddDeptItem item);

    /**
     * A prepay of a pump. {@code untilIdle} waits for the pump to be idle once the sale has taken it.
     */
    void prepay(String pump, String grade, String fuelProduct, String amount, String mop, Runnable untilIdle);

    /** A fuel sale on a pump. {@code untilClaimable} waits for the pump to be ready to be lifted. */
    void fuel(String pump, String currentStacked, Runnable untilClaimable);

    /** REFUND. */
    void refund();

    /** VOID_TICKET: a bare void of the ticket. */
    void voidTicket();

    /** VOID_LINE: a bare void-line key, the quantity (unless "0"), then ENTER with the PLU (unless ""). */
    void voidLine(String plu, String quantity);

    /** ERROR CORRECT, bare. */
    void errorCorrect();

    /** SUSPEND, bare. */
    void suspend();

    /** The register-side call that is not wired. */
    final class Unavailable implements SaleRmi {

        private static DeviceUnavailable unavailable(String operation) {
            return new DeviceUnavailable("the register's RMI", operation + ": no RMI client is wired to this run");
        }

        @Override
        public void pressKeys(String keys) {
            throw unavailable("press keys " + keys);
        }

        @Override
        public void pressKey(String key) {
            throw unavailable("press key " + key);
        }

        @Override
        public void pressEnter(String data) {
            throw unavailable("press ENTER");
        }

        @Override
        public void pressWithData(String key, String data) {
            throw unavailable("press " + key + " with " + data);
        }

        @Override
        public void scanItem(ScanItem item) {
            throw unavailable("scan " + item.scanData());
        }

        @Override
        public void mop(String mop, String data) {
            throw unavailable("tender " + mop);
        }

        @Override
        public void otherMop(String otherMop, String data, String position, String specialDiscount) {
            throw unavailable("tender " + otherMop);
        }

        @Override
        public void total() {
            throw unavailable("TOTAL");
        }

        @Override
        public void addItem(AddItem item) {
            throw unavailable("add PLU " + item.plu());
        }

        @Override
        public void addDeptItem(AddDeptItem item) {
            throw unavailable("add department " + item.dept());
        }

        @Override
        public void prepay(String pump, String grade, String fuelProduct, String amount, String mop,
                Runnable untilIdle) {
            throw unavailable("prepay pump " + pump);
        }

        @Override
        public void fuel(String pump, String currentStacked, Runnable untilClaimable) {
            throw unavailable("fuel pump " + pump);
        }

        @Override
        public void refund() {
            throw unavailable("REFUND");
        }

        @Override
        public void voidTicket() {
            throw unavailable("void the ticket");
        }

        @Override
        public void voidLine(String plu, String quantity) {
            throw unavailable("void line " + plu);
        }

        @Override
        public void errorCorrect() {
            throw unavailable("ERROR CORRECT");
        }

        @Override
        public void suspend() {
            throw unavailable("SUSPEND");
        }
    }
}
