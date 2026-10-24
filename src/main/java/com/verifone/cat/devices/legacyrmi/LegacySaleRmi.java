package com.verifone.cat.devices.legacyrmi;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.legacyrmi.LegacyRmi.DeptSale;
import com.verifone.cat.devices.legacyrmi.LegacyRmi.ItemSale;
import com.verifone.cat.pats.runner.handlers.sale.SaleRmi;

/**
 * {@link SaleRmi} over {@link LegacyRmi}: the sale family's register calls, through the pos-shim's key path.
 *
 * <p>Mapping, and where it departs from the seam's own words:
 * <ul>
 *   <li>{@code pressKeys(String)} is documented as a sequence of keys, but its one caller presses one key, and
 *       the seam does not say how a sequence is separated. A value with whitespace is refused rather than split
 *       on a guessed separator. It is sent raw, as the Python's {@code run.rmi.shim.press_keys} is.</li>
 *   <li>{@code pressWithData} and {@code pressKeys} are raw shim calls, so they skip the key guard and the overlay
 *       clear, as the Python's {@code run.rmi.shim} calls do. The sale family clears overlays itself before them.</li>
 *   <li>{@link ItemSale} and {@link DeptSale} fields that arrive null take the Python's inert defaults.</li>
 *   <li>The seam returns {@code void}; the shim's replies are dropped here (a refusal still throws).</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class LegacySaleRmi implements SaleRmi {

    private final LegacyRmi rmi;

    public LegacySaleRmi(LegacyRmi rmi) {
        this.rmi = rmi;
    }

    @Override
    public void pressKeys(String keys) {
        if (keys == null || keys.isEmpty() || keys.chars().anyMatch(Character::isWhitespace)) {
            throw new DeviceUnavailable("press keys '" + keys + "'",
                    "one key per call: the seam's sequence form is not split here, so a multi-key value is refused");
        }
        rmi.pressRawKey(keys);
    }

    @Override
    public void pressKey(String key) {
        rmi.pressKey(key);
    }

    @Override
    public void pressEnter(String data) {
        rmi.pressEnter(data);
    }

    @Override
    public void pressWithData(String key, String data) {
        rmi.pressRawWithData(key, data);
    }

    @Override
    public void scanItem(ScanItem item) {
        rmi.rawScanItem(item.scanData(), item.scanDataType(), item.decodeData(), item.scanDataLabel(), item.status());
    }

    @Override
    public void mop(String mop, String data) {
        rmi.mop(mop, data);
    }

    @Override
    public void otherMop(String otherMop, String data, String position, String specialDiscount) {
        rmi.otherMop(otherMop, data, position, specialDiscount);
    }

    @Override
    public void total() {
        rmi.total();
    }

    @Override
    public void addItem(AddItem item) {
        ItemSale d = ItemSale.of(item.plu());
        rmi.addItem(new ItemSale(item.plu(), or(item.taxExempt(), d.taxExempt()), or(item.modifier(), d.modifier()),
                or(item.discount(), d.discount()), or(item.discountKey(), d.discountKey()),
                or(item.quantity(), d.quantity()), or(item.idc(), d.idc()), or(item.priceOver(), d.priceOver()),
                or(item.pluNotFoundPrice(), d.pluNotFoundPrice()), or(item.usingMenu(), d.usingMenu()),
                or(item.selectSoftkey(), d.selectSoftkey()), or(item.choice(), d.choice()),
                or(item.selectFile(), d.selectFile()), item.scan()));
    }

    @Override
    public void addDeptItem(AddDeptItem item) {
        DeptSale d = DeptSale.of(item.dept(), item.amount());
        rmi.addDeptItem(new DeptSale(item.dept(), item.amount(), or(item.departmentNo(), d.departmentNo()),
                or(item.otherDepartment(), d.otherDepartment()), or(item.quantity(), d.quantity()),
                or(item.pack(), d.pack()), or(item.taxExempt(), d.taxExempt()), or(item.discount(), d.discount()),
                or(item.idc(), d.idc())));
    }

    @Override
    public void prepay(String pump, String grade, String fuelProduct, String amount, String mop, Runnable untilIdle) {
        rmi.prepay(pump, grade, fuelProduct, amount, mop, untilIdle);
    }

    @Override
    public void fuel(String pump, String currentStacked, Runnable untilClaimable) {
        rmi.fuel(pump, currentStacked, untilClaimable);
    }

    @Override
    public void refund() {
        rmi.refund();
    }

    @Override
    public void voidTicket() {
        rmi.voidTicket();
    }

    @Override
    public void voidLine(String plu, String quantity) {
        rmi.voidLineItem(plu, quantity);
    }

    @Override
    public void errorCorrect() {
        rmi.errorCorrect();
    }

    @Override
    public void suspend() {
        rmi.suspend();
    }

    private static String or(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
