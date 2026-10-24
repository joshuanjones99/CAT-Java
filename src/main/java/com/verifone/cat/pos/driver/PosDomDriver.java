package com.verifone.cat.pos.driver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.pos.receipt.DomReceiptCapture.DomElement;
import com.verifone.cat.pos.receipt.DomReceiptCapture.ItemFamilies;

/**
 * The receipt capture's DOM driver, on top of {@link PosDriver}: one bulk query per
 * {@code ReceiptView_*} ID family, through the catalogue's own locators. Port of the Python
 * receipt module's {@code _read_family} (which used {@code driver.find_family}).
 *
 * <p>Selectors come only from {@link Locator#prefixSelector()}. The four families are the
 * catalogue entries Python's receipt reader names: description, amount, quantity and whole line.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PosDomDriver implements DomReceiptCapture.DomDriver {

    private final PosDriver driver;
    private final Map<String, Locator> familyByPrefix = new LinkedHashMap<>();

    public PosDomDriver(PosDriver driver) {
        this.driver = driver;
        for (Locator family : List.of(
                Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N,
                Locators.RECEIPT_VIEW_ITEM_LINE_AMOUNT_N,
                Locators.RECEIPT_VIEW_ITEM_LINE_QUANTITY_N,
                Locators.RECEIPT_VIEW_ITEM_LINE_N)) {
            familyByPrefix.put(prefixOf(family), family);
        }
    }

    /** The four item-line families, as the ID prefixes {@link DomReceiptCapture} takes. */
    public static ItemFamilies itemFamilies() {
        return new ItemFamilies(
                prefixOf(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N),
                prefixOf(Locators.RECEIPT_VIEW_ITEM_LINE_AMOUNT_N),
                prefixOf(Locators.RECEIPT_VIEW_ITEM_LINE_QUANTITY_N),
                prefixOf(Locators.RECEIPT_VIEW_ITEM_LINE_N));
    }

    /** The fixed part of an indexed template: {@code ReceiptView_ItemLine_{n}} gives its head. */
    static String prefixOf(Locator family) {
        String value = family.value();
        int brace = value.indexOf('{');
        if (brace <= 0) {
            throw new IllegalArgumentException(family.name() + " is not an indexed family");
        }
        return value.substring(0, brace);
    }

    @Override
    public List<DomElement> findFamily(String idPrefix) {
        Locator family = familyByPrefix.get(idPrefix);
        if (family == null) {
            throw new DriverError("no catalogue family for ID prefix '" + idPrefix + "'");
        }
        return driver.familyElements(family.prefixSelector());
    }
}
