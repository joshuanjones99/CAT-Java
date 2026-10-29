// CAT's java agent for Petro Simulator Suite's tank level simulator (cat.sims.petro_tls).
// Petro runs its TLS simulator headless (mode 4, how CAT and PATS run it), and two things
// it needs exist only in its UI. Measured 2026-10-13; the agent supplies both.
//
// 1. Serial drivers. The TLS simulator opens its port with
//    CommPortIdentifier.getPortIdentifier, which sees only ports a loaded driver registered,
//    and the only caller of DriverManager.loadDrivers is the UI's TLS settings screen:
//    "NoSuchPortException: COM2 not found". This RXTX build's LibraryLoader also knows
//    os.name values only up to "Windows 10", so on Windows 11 its DLL never loads. The
//    agent loads the drivers before Petro's main, reporting "Windows 10" for that alone.
// 2. Tanks. The simulator answers a Commander poll from TLSClientAdapter's tank list, and
//    only the UI fills it (TLSConfigurationManager.readTankInfo, sent over port 9688).
//    With none, every reply is empty and the Commander logs "TLS setup commands failed".
//    Once Petro has read its TLS device type, the agent calls that same reader for
//    TLS_config.properties' Tanks and sets the list, unless something already has.
//
// Calls Petro and RXTX by reflection (both are on Petro's class path), so nothing is
// needed to compile this. Loaded through -javaagent:<jar>.

package cat.petro;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;

public final class RxtxAgent {
    private static final String SIM = "com.verifone.isd.pcapps.simulators.";

    private RxtxAgent() {}

    public static void premain(String args) {
        loadDrivers();
        Thread tanks = new Thread(RxtxAgent::loadTanks, "cat-tls-tanks");
        tanks.setDaemon(true);
        tanks.start();
    }

    private static void loadDrivers() {
        String os = System.getProperty("os.name");
        try {
            if (os != null && os.startsWith("Windows")) {
                System.setProperty("os.name", "Windows 10");
            }
            Class<?> manager = Class.forName("gnu.io.DriverManager");
            Object instance = manager.getMethod("getInstance").invoke(null);
            manager.getMethod("loadDrivers").invoke(instance);
            System.out.println("cat-rxtx-agent: RXTX serial drivers loaded");
        } catch (Throwable t) {
            System.out.println("cat-rxtx-agent: RXTX serial drivers not loaded: " + t);
        } finally {
            if (os != null) {
                System.setProperty("os.name", os);
            }
        }
    }

    private static void loadTanks() {
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            Class<?> client =
                    Class.forName(SIM + "forecourt.tls.comm.client.TLSClientAdapter", true, loader);
            Method getTanks = client.getMethod("getTanks");
            // Up to 5 minutes for Petro's main to start the TLS simulator: it sets the device
            // type the reader keys on (Veeder or Veeder 450) before it does.
            if (!started(loader, "TLSRTImpl", "TLS450RTImpl")) {
                System.out.println("cat-rxtx-agent: TLS simulator not started; no tanks loaded");
                return;
            }
            if (getTanks.invoke(null) != null) {
                System.out.println("cat-rxtx-agent: TLS tanks already set; left alone");
                return;
            }
            Properties tls = new Properties();
            try (InputStream in = new FileInputStream("config/TLS_config.properties")) {
                tls.load(in);
            }
            Class<?> reader = Class.forName(SIM + "ui.ctrl.TLSConfigurationManager", true, loader);
            Object list = reader.getMethod("readTankInfo", String.class)
                    .invoke(reader.getConstructor().newInstance(), tls.getProperty("Tanks", "1"));
            client.getMethod("setTanks", List.class).invoke(null, list);
            System.out.println("cat-rxtx-agent: TLS tanks loaded: " + ((List<?>) list).size());
        } catch (Throwable t) {
            System.out.println("cat-rxtx-agent: TLS tanks not loaded: " + t);
        }
    }

    /** True once one of `names`' singletons exists, polled for up to 5 minutes. */
    private static boolean started(ClassLoader loader, String... names) throws Exception {
        java.lang.reflect.Field[] instances = new java.lang.reflect.Field[names.length];
        for (int n = 0; n < names.length; n++) {
            instances[n] = Class.forName(SIM + "forecourt.tls.rt." + names[n], true, loader)
                    .getDeclaredField("instance");
            instances[n].setAccessible(true);
        }
        for (int i = 0; i < 300; i++) {
            for (java.lang.reflect.Field instance : instances) {
                if (instance.get(null) != null) {
                    return true;
                }
            }
            Thread.sleep(1000);
        }
        return false;
    }
}
