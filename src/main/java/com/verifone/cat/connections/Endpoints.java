package com.verifone.cat.connections;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.verifone.cat.rig.Rig;

/**
 * The three endpoints a run cannot start without, as the Python CAT's {@code cat.connections}
 * derives them from the rig model.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). {@link #of} and {@link #targetsFor} are
 * {@code targets_for} and {@code optional_target_names}; the Python module is the specification.
 *
 * <p>Still not derived, so not probed here: the Xray row (the Python run adds it when the run reports
 * to Xray, which CAT-Java does not carry yet), the Receipt Printer row (its {@code printer_port} is a
 * serial listing, which needs the printer slice), and the VATS checks for {@code pinpad: vats}.
 *
 * <p>The two ports are the platform's, not rig fields, as in the Python CAT: CGILink on
 * port 80 ({@code docs/commander-config-surface.md}), and SSH on port 22, which
 * {@code provision} connects over. A register is probed on SSH, not on its CDP port, because
 * the CDP port only opens once provisioning has pushed the browser.
 *
 * @param commanderHost   the Commander's host
 * @param registerId      the primary register's id, used only in its row label
 * @param registerHost    the primary register's host
 * @param controllerHost  the address the controller PC should hold on its rig adapter
 */
public record Endpoints(String commanderHost, String registerId, String registerHost, String controllerHost) {

    /** CGILink, the Commander's configuration surface. */
    public static final int COMMANDER_SERVICE_PORT = 80;

    /** SSH, the port a register is provisioned over. */
    public static final int REGISTER_SERVICE_PORT = 22;

    /** The primary register's row label prefix, as the status page shows it. */
    private static final String REGISTER_PREFIX = "Register ";

    /** The three endpoints of {@code rig}: its Commander, primary register and controller. */
    public static Endpoints of(Rig rig) {
        Rig.Register primary = rig.primaryRegister();
        return new Endpoints(rig.commander().host(), primary.id(), primary.host(), rig.controller().host());
    }

    /** The three targets every run probes, in the order the status page shows them, all fixed. */
    public List<Target> fixedTargets() {
        return List.of(
                Target.tcp("Commander", commanderHost, COMMANDER_SERVICE_PORT).asFixed(),
                Target.tcp(REGISTER_PREFIX + registerId, registerHost, REGISTER_SERVICE_PORT).asFixed(),
                Target.local("Host PC", controllerHost).asFixed());
    }

    /**
     * Every selectable (non-fixed) connection {@code rig} can offer, in picker order: the extra
     * registers first, then the {@code devices:} keys in order. The three fixed targets are not here.
     */
    public static List<String> optionalTargetNames(Rig rig) {
        List<String> names = new ArrayList<>();
        rig.registers().stream().skip(1).forEach(extra -> names.add(extra.id()));
        names.addAll(new TreeMap<>(rig.devices()).keySet());
        return List.copyOf(names);
    }

    /**
     * The fixed targets, plus the extra registers and devices in {@code selected}, as
     * {@code targets_for}: one row per name, the first one kept. A selected name the rig does not
     * declare is a row with no address, which reports {@code unknown}, not {@code down}.
     *
     * @param selected the optional connection names chosen for the run; may be empty
     */
    public static List<Target> targetsFor(Rig rig, Collection<String> selected) {
        List<Target> targets = new ArrayList<>(Endpoints.of(rig).fixedTargets());
        Set<String> chosen = new LinkedHashSet<>(selected);

        for (Rig.Register extra : rig.registers().subList(1, rig.registers().size())) {
            if (chosen.contains(extra.id())) {
                targets.add(Target.tcp(REGISTER_PREFIX + extra.id(), extra.host(), REGISTER_SERVICE_PORT));
            }
        }
        Map<String, Rig.Device> devices = new TreeMap<>(rig.devices());
        for (Map.Entry<String, Rig.Device> device : devices.entrySet()) {
            if (chosen.contains(device.getKey())) {
                targets.add(Target.tcp(device.getKey(), device.getValue().host(), device.getValue().port()));
            }
        }
        // Selected but undeclared: the row is shown, and says why it cannot be probed.
        Set<String> known = new LinkedHashSet<>(optionalTargetNames(rig));
        for (String name : new TreeSet<>(chosen)) {
            if (!known.contains(name)) {
                targets.add(Target.tcp(name, "", null));
            }
        }

        // One row per name: a device whose key collides with a fixed row's name would otherwise
        // give two rows that overwrite each other's state in the watch.
        Map<String, Target> byName = new LinkedHashMap<>();
        for (Target target : targets) {
            byName.putIfAbsent(target.name(), target);
        }
        return List.copyOf(byName.values());
    }
}
