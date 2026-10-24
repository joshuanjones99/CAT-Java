package com.verifone.cat.pos.pages;

import java.util.Map;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.UnresolvedLocatorError;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * Fuel grade selection. Port of the Python {@code GradeTab}. Package-private helper of the fuel
 * slice, reached through {@link FuelPage#gradeTab()}.
 *
 * <p>The element ID for the grade tab is not known: no ID in either corpus tree, nor in the Topaz
 * client, names it. {@link #select(int)} therefore refuses rather than guesses; identify the control
 * against a live register (DESIGN.md 10, Phase 0), then use {@link #selectByName}, which works
 * today through the text-predicate XPath the corpus already relies on.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class FuelGradeTab extends BasePage {

    FuelGradeTab(PosDriver driver) {
        super(driver);
    }

    /** Always refuses: see the class note. */
    BaseElement select(int index) {
        throw new UnresolvedLocatorError("grade_tab.select(" + index + "): no element ID for the fuel "
                + "grade tab has been confirmed. Nothing in either corpus tree, in the element-ids guide, "
                + "or in the Topaz GWT client matches. Identify it against a live register (DESIGN.md 10, "
                + "Phase 0) and add it to the catalogue, then use selectByName(...) meanwhile.");
    }

    /** Select a grade by its visible label, e.g. {@code "PETR-UNLD1"}. */
    BaseElement selectByName(String grade) {
        return element(Locators.XP_BUTTON_BY_TEXT.atNamed(Map.of("label", grade)));
    }
}
