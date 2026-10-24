package com.verifone.cat.pos.pages;

import com.verifone.cat.pos.BuildVariant;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * A group of related POS elements, bound to one driver session. Port of the Python
 * {@code petrotest.pos.pages.base.Page} (renamed here so it does not shadow Playwright's Page).
 *
 * <p>Page objects are a later slice; this is the base they will extend. Tests call page objects,
 * page objects call the catalogue, and nothing else spells an element ID (DESIGN.md 4.2 rule 1).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class BasePage {

    private final PosDriver driver;

    public BasePage(PosDriver driver) {
        if (driver == null) {
            throw new IllegalArgumentException("a page object needs a driver");
        }
        this.driver = driver;
    }

    public PosDriver driver() {
        return driver;
    }

    /** Resolve a catalogue entry, build-variant and templating included, to an element. */
    public BaseElement element(Locator locator, Object... args) {
        return new BaseElement(driver, driver.resolve(locator, args));
    }

    /** Resolve a build-variant entry against the session's build version, then template it. */
    public BaseElement element(BuildVariant variant, Object... args) {
        return new BaseElement(driver, driver.resolve(variant, args));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " register=" + driver.registerId();
    }
}
