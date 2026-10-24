package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The fuel panel: the pump tile grid on the POS main screen. Port of the Python
 * {@code petrotest.pos.pages.fuel.FuelPanel}.
 *
 * <p>{@code FuelPanel_0_FuelButton_{n}_*} differs between builds ({@code _Data} and
 * {@code _LabelPosition}); {@link Locators#FUEL_BUTTON} is the one conditional, resolved against
 * the session's build version, so no page spells either suffix.
 *
 * <p>The fuel point detail overlay and the grade tab are reached through {@link #detail()} and
 * {@link #gradeTab()}; they are package-private helpers of this slice.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). {@link #pumpStatus} reading the amount is the
 * Python's assumption, not yet checked on a live register.
 */
public class FuelPage extends BasePage {

    /** A pump tile's money figure: "32.40", "+20.00". */
    private static final Pattern AMOUNT = Pattern.compile("\\+?\\d+\\.\\d\\d");

    public FuelPage(PosDriver driver) {
        super(driver);
    }

    /** The tile for pump {@code number}: {@code pos.fuel_panel.pump(1).tap()}. */
    public BaseElement pump(int number) {
        return element(Locators.FUEL_BUTTON, number);
    }

    /** The tile's running-total label ({@code _LabelCurrent}). */
    public BaseElement pumpStatus(int number) {
        return element(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT, number);
    }

    /**
     * The fills due on pump {@code number}, as its tile shows them: {@code ["+10.00", "+6.00"]}.
     * One look, no wait. A due fill reads with a leading "+"; a fill already rung into the open
     * sale reads without it and is not due.
     */
    public List<String> due(int number) {
        List<String> out = new ArrayList<>();
        for (Locator locator : List.of(
                Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT,
                Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_STACKED)) {
            for (PosDriver.ElementState state : driver().elementStates(locator.at(number))) {
                if (state.text().startsWith("+")) {
                    out.add(state.text());
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The amount held on pump {@code number} with nothing dispensed, or empty. One look. Read
     * only on an idle register, where an amount with no "+" is a reserve (a prepay or preset the
     * pump still holds), not a fill rung into a sale.
     */
    public Optional<String> reserved(int number) {
        for (PosDriver.ElementState state : driver().elementStates(
                Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT.at(number))) {
            String amount = state.text().strip();
            if (!amount.isEmpty() && !amount.startsWith("+") && AMOUNT.matcher(amount).matches()) {
                return Optional.of(amount);
            }
        }
        return Optional.empty();
    }

    /**
     * Wait for pump {@code number}'s tile to show {@code amount}, replacing a fixed sleep after
     * HANDLE_DOWN. Fuelling takes longer than a UI action, so the caller passes the rig's
     * {@code fuel_complete} budget (DESIGN.md 8); a null budget is refused, not defaulted.
     */
    public void expectPumpAmount(int number, String amount, Duration fuelComplete) {
        if (fuelComplete == null) {
            throw new IllegalArgumentException("expectPumpAmount needs the rig's fuel_complete budget");
        }
        pumpStatus(number).expectText(amount, false, fuelComplete);
    }

    /** The fuel point detail overlay: prepay and pump function buttons. */
    public FuelDetailOverlay detail() {
        return new FuelDetailOverlay(driver());
    }

    /** Fuel grade selection. */
    public FuelGradeTab gradeTab() {
        return new FuelGradeTab(driver());
    }
}
