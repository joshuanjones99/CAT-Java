package com.verifone.cat.pats.runner.handlers.bdd;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.Elements;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.PosText;
import com.verifone.cat.pos.receipt.DomReceiptCapture.DomElement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * What the BDD actions read off the POS screen: an element's text, the rows of an indexed ID family, and the
 * {@code expect_text} wait. The Python reads these through {@code run.chosen_text}, {@code find_family} and
 * {@code CaseRun.expect_text}; this seam keeps the family testable without a browser.
 *
 * <p>{@link #overPos(PosDriver)} is the production reader. {@link #UNAVAILABLE} throws
 * {@link DeviceUnavailable}, so an unwired screen fails its step by name rather than passing.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the production reader has only fake-backed tests.
 */
public interface BddScreen {

    /** One row of an indexed ID family: its element ID, its normalised text, and whether any of its copies shows. */
    record Member(String id, String text, boolean visible) {
    }

    /** The one wait {@code expect_text} makes (PATS's 60 s {@code textToBePresentInElementLocated}). */
    Duration EXPECT_TEXT_WAIT = Duration.ofSeconds(60);

    /** Poll interval for the waits this seam makes. */
    Duration POLL = Duration.ofMillis(250);

    /**
     * The text PATS's {@code findElement} reads for {@code probe}'s element: the element on top (the last visible),
     * else the first; null when nothing matches.
     */
    String chosenText(RunnableAction probe);

    /** Every row of the indexed ID family {@code family}, hidden ones too, in page order. No wait. */
    List<Member> members(Locator family);

    /**
     * {@code expect_text}: the element's text holds {@code expected} (a case-sensitive substring, normalised) within
     * {@link #EXPECT_TEXT_WAIT} of the run's budget.
     *
     * @throws PatsStepError when it does not
     */
    void expectText(CaseRun run, RunnableAction probe, String expected);

    /** No screen wired: every read raises {@link DeviceUnavailable}. */
    BddScreen UNAVAILABLE = new BddScreen() {
        @Override
        public String chosenText(RunnableAction probe) {
            throw new DeviceUnavailable("POS screen", "no screen is wired to the BDD actions");
        }

        @Override
        public List<Member> members(Locator family) {
            throw new DeviceUnavailable("POS screen", "no screen is wired to the BDD actions");
        }

        @Override
        public void expectText(CaseRun run, RunnableAction probe, String expected) {
            throw new DeviceUnavailable("POS screen", "no screen is wired to the BDD actions");
        }
    };

    /** The production reader over the POS driver. */
    static BddScreen overPos(PosDriver driver) {
        return new PosBddScreen(Objects.requireNonNull(driver, "driver"));
    }

    /** Reads the screen through {@link PosDriver}. */
    final class PosBddScreen implements BddScreen {

        private final PosDriver driver;

        PosBddScreen(PosDriver driver) {
            this.driver = driver;
        }

        @Override
        public String chosenText(RunnableAction probe) {
            Locator locator = Elements.resolve(probe.get("WEB ELEMENT"), probe.get("TYPE[ID/XPATH]", "ID"));
            List<PosDriver.ElementState> states = driver.elementStates(locator);
            if (states.isEmpty()) {
                return null;
            }
            String shown = null;
            for (PosDriver.ElementState state : states) {
                if (state.visible()) {
                    shown = state.text();
                }
            }
            return shown != null ? shown : states.get(0).text();
        }

        @Override
        public List<Member> members(Locator family) {
            List<Member> out = new ArrayList<>();
            for (DomElement element : driver.familyElements(family.prefixSelector())) {
                OptionalLong index = family.indexOf(element.id());
                if (index.isEmpty()) {
                    continue;
                }
                // The row's own locator, so its visibility is read as the driver reads any element's.
                Locator row = family.at(index.getAsLong());
                boolean visible = driver.elementStates(row).stream().anyMatch(PosDriver.ElementState::visible);
                out.add(new Member(element.id(), element.text(), visible));
            }
            return out;
        }

        @Override
        public void expectText(CaseRun run, RunnableAction probe, String expected) {
            String want = PosText.normalise(expected);
            Duration timeout = Duration.ofMillis(Math.round(run.budget(EXPECT_TEXT_WAIT.toSeconds()) * 1000));
            try {
                PollUntil.until(() -> {
                    String text = chosenText(probe);
                    return (text == null ? "\0" : text).contains(want);
                }, timeout, POLL, "an element to contain " + expected);
            } catch (DriverTimeout e) {
                throw new PatsStepError(probe.get("WEB ELEMENT") + " to contain " + expected
                        + "; it reads " + PosText.normalise(String.valueOf(chosenText(probe))), e);
            }
        }
    }
}
