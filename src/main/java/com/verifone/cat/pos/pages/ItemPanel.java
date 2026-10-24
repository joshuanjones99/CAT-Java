package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.driver.PosDriver;

// Stub; ported in wave7/pagesb.
public class ItemPanel extends BasePage {

    /** The item-panel ids Python's MOP IntEnum names (only the ones pos.py uses). */
    public enum MOP {
        PAYMENT_GATEWAY(145397),
        CASH(145394),
        OTHER(145398);

        private final int id;

        MOP(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public ItemPanel(PosDriver driver) {
        super(driver);
    }

    public void select(MOP item, Duration timeout) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }
}
