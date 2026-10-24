package com.verifone.cat.commander.connect;

import java.time.Duration;

/**
 * The waits {@link ConnectService} uses, named as the Python's {@code commander_connect} constants are. A record so
 * tests can shorten them; production takes {@link #defaults()}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param onboardTimeout    how long one onboarding may take to register
 * @param onboardSettle     a failed state is believed only this long after a sign-in
 * @param onboardRetryWait  at most, between sign-ins: the tunnel's re-plan to relay a learned host
 * @param onboardPoll       how often the registration is polled
 * @param bridgeSettle      how long the MQTT bridge may take to come Online after a broker restart
 * @param onlineTimeout     how long the Commander may take to read Online after a coresvcs restart
 * @param onlinePoll        how often cloud connectivity is polled
 * @param coresvcsSettle    before a coresvcs restart is polled, so the old VM is seen going down
 * @param coresvcsTimeout   how long coresvcs may take to come back
 * @param coresvcsPoll      how often coresvcs is polled to come back
 * @param relayPoll         how often the tunnel's relay is polled before a sign-in is retried
 */
public record ConnectTiming(
        Duration onboardTimeout,
        Duration onboardSettle,
        Duration onboardRetryWait,
        Duration onboardPoll,
        Duration bridgeSettle,
        Duration onlineTimeout,
        Duration onlinePoll,
        Duration coresvcsSettle,
        Duration coresvcsTimeout,
        Duration coresvcsPoll,
        Duration relayPoll) {

    /** The Python's values: 180 s to register, 3 sign-ins, 120 s between them, 90 s for the bridge, 300 s online. */
    public static ConnectTiming defaults() {
        return new ConnectTiming(
                Duration.ofSeconds(180),
                Duration.ofSeconds(10),
                Duration.ofSeconds(120),
                Duration.ofSeconds(5),
                Duration.ofSeconds(90),
                Duration.ofSeconds(300),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(300),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5));
    }
}
