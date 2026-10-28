package com.verifone.cat.commander.registerapp;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.Say;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * {@code register-app}: puts each register back in the cashier POS app. A port of
 * {@code petrotest.register_app} (the parts that need no POS driver; see {@link PosSeams}).
 *
 * <p>A Topaz hosts either the cashier POS or the self-checkout (SCO) app. Which one is the register's own setting,
 * {@code application.active} in {@code /home/topaz/config/config_system.prop}, read once when its Tomcat starts. The
 * App Selector writes it and restarts the app; no Commander configuration touches it, so the Commander snapshot
 * restore cannot put it back. CAT's Commander Configuration step runs this before it closes the day.
 *
 * <p>Per register: the property is read over SSH and the hosted app's page is read; when either says SCO, the App
 * Selector's switcher and its POS panel are pressed. When the switcher is not drawn, the App Selector's own writes
 * and its own restart are made as {@code topaz} in a login shell. Then the page must host the POS app.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's switch was seen on Reg101 on 2026-10-20 (the files and the
 * login-shell restart); pressing the switcher has not been seen to work on a register.
 *
 * <p>Step 4 calls this ({@code launch.CommanderStages.Real#registerApp}) before the day close, as the Python's
 * Commander Configuration step does.
 */
public final class RegisterApp {

    /** The register's own setting. */
    public static final String ACTIVE_APP_PROP = "application.active";

    /** The file the setting is kept in. */
    public static final String CONFIG_PROP = "/home/topaz/config/config_system.prop";

    /** How long the switch may take: PATS waits 90 s after it, and the app restarts. */
    public static final Duration SWITCH_TIMEOUT = Duration.ofSeconds(300);

    /** Between two reads of the hosted app. */
    public static final Duration SWITCH_POLL = Duration.ofSeconds(5);

    /** How long the App Selector's switcher is looked for before the files are written. */
    public static final Duration SWITCHER_WAIT = Duration.ofSeconds(20);

    /** The register's SSH budget. */
    static final Duration SSH_TIMEOUT = Duration.ofSeconds(20);

    /**
     * What the App Selector writes when a lane is switched, then {@code restartapp.sh} as {@code topaz} in a login
     * shell, the register's own restart ("expected to be run as 'topaz' user").
     */
    public static final String SWITCH_TO_POS =
            "sed -i 's/^\\(.*app\\.active\\)=.*/\\1=pos/' /home/topaz/config/appSelector.prop"
            + " && sed -i 's/^" + ACTIVE_APP_PROP + "=.*/" + ACTIVE_APP_PROP + "=pos/' " + CONFIG_PROP
            + " && su - topaz -c 'nohup /home/topaz/bin/restartapp.sh > /tmp/cat-restartapp.log 2>&1 &'";

    /** The hosted app's page, by app: {@code posuiapp} is the POS, {@code scouiapp} the SCO. */
    public static final Map<String, String> HOSTED_PAGES = Map.of("posuiapp", "pos", "scouiapp", "sco");

    private RegisterApp() {}

    /** The app a hosted page's frame URL belongs to ({@code pos} or {@code sco}), or empty when it is neither. */
    public static Optional<String> appForFrame(String frameUrl) {
        for (Map.Entry<String, String> page : HOSTED_PAGES.entrySet()) {
            if (frameUrl.contains("/" + page.getKey() + "/")) {
                return Optional.of(page.getValue());
            }
        }
        return Optional.empty();
    }

    /** What the POS driver supplies: the hosted app's page, and the App Selector's presses. */
    public interface PosSeams {

        /**
         * The app the register's hosted page runs ({@code pos} or {@code sco}), or empty while the app restarts or
         * the page cannot be read.
         */
        Optional<String> hostedApp(Rig.Register register);

        /**
         * Presses the App Selector's switcher, then its POS panel. Throws when no switcher is drawn.
         *
         * @throws RuntimeException no switcher, or the press failed
         */
        void pressPosPanel(Rig.Register register);
    }

    /**
     * The register's {@code application.active}, lower-cased; {@code pos} when the property is absent (the app's own
     * default).
     */
    public static String activeApp(Transport transport, Duration timeout) {
        CommandResult result = transport.run("grep '^" + ACTIVE_APP_PROP + "=' " + CONFIG_PROP, timeout);
        for (String line : result.stdout().split("\\R")) {
            if (line.startsWith(ACTIVE_APP_PROP + "=")) {
                return line.substring(ACTIVE_APP_PROP.length() + 1).strip().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "pos";
    }

    /**
     * The App Selector's own writes and restart, over SSH: its switcher is drawn only while the hosted app reports
     * itself idle, and Reg101's SCO welcome screen showed none.
     *
     * @throws IllegalStateException the command exited non-zero
     */
    public static void switchByFiles(Transport transport) {
        CommandResult result = transport.run(SWITCH_TO_POS, Duration.ofSeconds(30));
        if (!result.ok()) {
            throw new IllegalStateException("switching by its files failed: " + result.stderr().strip());
        }
    }

    /**
     * {@code petrotest register-app}: every register in the POS app.
     *
     * @param transports a connected transport to each register (the Commander's account and key)
     * @param seams      the POS driver's reads and presses
     * @param timeout    how long each register may take to host the POS app
     * @return true when each already was, or was switched back; false when one could not be
     */
    public static boolean run(Rig rig, Function<Rig.Register, Transport> transports, PosSeams seams, Pauser pauser,
            Duration timeout, Say say) {
        boolean ok = true;
        for (Rig.Register register : rig.registers()) {
            String name = register.id();
            Transport transport = transports.apply(register);
            try {
                String prop = activeApp(transport, SSH_TIMEOUT);
                Optional<String> hosted = seams.hostedApp(register);
                if (prop.equals("pos") && !hosted.filter("sco"::equals).isPresent()) {
                    say.out("register-app: " + name + " runs the POS app");
                    continue;
                }
                say.out("register-app: " + name + ": " + ACTIVE_APP_PROP + "=" + prop + ", hosting "
                        + hosted.orElse("none") + ": to POS");
                long started = System.nanoTime();
                try {
                    try {
                        seams.pressPosPanel(register);
                    } catch (RuntimeException e) {
                        say.out("register-app: " + name + ": no App Selector to touch (" + e.getMessage()
                                + "); writing its settings and restarting the app");
                        switchByFiles(transport);
                    }
                    boolean hostsPos = pauser.until(() -> seams.hostedApp(register).filter("pos"::equals).isPresent(),
                            timeout, SWITCH_POLL);
                    if (!hostsPos) {
                        throw new IllegalStateException("timed out after " + timeout.toSeconds()
                                + " s waiting for " + name + " to host the POS app");
                    }
                } catch (RuntimeException e) {
                    say.err("register-app: " + name + " is not back in the POS app: " + e.getMessage());
                    ok = false;
                    continue;
                }
                say.out("register-app: " + name + " switched to POS in "
                        + (System.nanoTime() - started) / 1_000_000_000L + "s");
            } finally {
                transport.close();
            }
        }
        return ok;
    }
}
