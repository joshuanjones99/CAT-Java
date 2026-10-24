package com.verifone.cat.pos.pages;

import java.util.List;

import com.verifone.cat.pos.driver.PosDriver;

// Stub; ported in wave7/pagesb.
public class FuelPage extends BasePage {

    public FuelPage(PosDriver driver) {
        super(driver);
    }

    public BaseElement pump(int number) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public List<String> due(int number) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    /** The reserve text on the pump's tile, or null when it holds none. */
    public String reserved(int number) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public BaseElement prepay(int index) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public boolean pressLabelled(String label) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public void close() {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }
}
