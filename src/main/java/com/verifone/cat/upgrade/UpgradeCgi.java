package com.verifone.cat.upgrade;

import java.util.function.Supplier;

import org.w3c.dom.Element;

import com.verifone.cat.devices.commander.PdkShim;

/**
 * The three CGILink calls the AU path's upgrade dance makes: {@code vupgradesummary}, {@code cclosepdnow} and
 * {@code cbeginupgrade}. A seam over {@link PdkShim} so a test can script the Commander's answers; production is
 * {@link #over}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the reply shapes are unconfirmed (see {@link PdkShim}'s
 * upgrade-control section).
 */
public interface UpgradeCgi {

    /** {@code cmd=vupgradesummary}. Throws a {@code DeviceError} on a fault. */
    Element upgradeSummary(Supplier<String> readOtp);

    /** {@code cmd=cclosepdnow}. Throws a {@code DeviceError} on a fault. */
    void closeDay(Supplier<String> readOtp);

    /** {@code cmd=cbeginupgrade} with the summary's token. Throws a {@code DeviceError} on a fault. */
    void beginUpgrade(String token, Supplier<String> readOtp);

    /** The real client, over CGILink. */
    static UpgradeCgi over(PdkShim shim) {
        return new UpgradeCgi() {
            @Override
            public Element upgradeSummary(Supplier<String> readOtp) {
                return shim.upgradeSummary(readOtp);
            }

            @Override
            public void closeDay(Supplier<String> readOtp) {
                shim.closeDay(readOtp);
            }

            @Override
            public void beginUpgrade(String token, Supplier<String> readOtp) {
                shim.beginUpgrade(token, readOtp);
            }
        };
    }
}
