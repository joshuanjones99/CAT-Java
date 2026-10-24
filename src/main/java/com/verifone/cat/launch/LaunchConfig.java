package com.verifone.cat.launch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.verifone.cat.runconfig.RunScope;

/**
 * What one run is asked to do: the Python CAT's {@code cat.launch.config.LaunchConfig}, for the
 * fields the framework and the seven steps need in this port.
 *
 * <p>Carried here: the rig, the suites and plans, the upgrade mode, the fake-POS switches, the
 * simulator selection, the Run tab's {@link RunScope}, the Xray switch and the {@code pytest} argv. Not
 * carried yet, because the steps that read them are not ported: the Downloads section, Commander
 * Connect, the Secondary FEPs, JaCoCo, the probe targets beyond the Xray row, the BUYPASS host, the
 * Commander config snapshot, and the Configuration execution mode. A later slice adds each one with
 * its step.
 *
 * <p>A run that is not the whole plan is refused by {@link RunLauncher} (see there), because the
 * partial selection reads petroapps' case files, which this port does not read yet.
 *
 * @param rig          the rig's name or {@code .yaml} path, as {@code RigLoader} reads it; may be
 *                     empty only for a fake-POS run
 * @param suites       pytest node paths, one per case or directory
 * @param testPlans    the Jira plan keys the run was asked for; recorded, not resolved here
 * @param upgradeMode  how the rig is upgraded before the tests
 * @param fakePos      run against the fake POS instead of a rig (evidence about CAT, not a rig)
 * @param fakePinpad   the pinpad double; implied by {@code fakePos}
 * @param snapshotUpdate pytest's {@code --snapshot-update}
 * @param simulators   the simulators step 3 should start, by registry name; empty means none
 * @param scope        the Run tab's scope; {@link RunScope#whole()} unless a partial run was asked for
 * @param xrayReport  report the run to Xray: the Connections step adds the Xray row and the launcher
 *                     starts the reporting sidecar. Never under {@code fakePos}, whatever this says
 */
public record LaunchConfig(
        String rig,
        List<String> suites,
        List<String> testPlans,
        UpgradeMode upgradeMode,
        boolean fakePos,
        boolean fakePinpad,
        boolean snapshotUpdate,
        List<String> simulators,
        RunScope scope,
        boolean xrayReport) {

    public LaunchConfig {
        rig = rig == null ? "" : rig.strip();
        suites = List.copyOf(suites == null ? List.of() : suites);
        testPlans = List.copyOf(testPlans == null ? List.of() : testPlans);
        upgradeMode = upgradeMode == null ? UpgradeMode.SKIP : upgradeMode;
        simulators = List.copyOf(simulators == null ? List.of() : simulators);
        scope = scope == null ? RunScope.whole() : scope;
        if (rig.isEmpty() && !fakePos) {
            throw new IllegalArgumentException("a run needs a rig, or fakePos");
        }
    }

    /** A run with the Xray switch off, as every caller before the switch was carried. */
    public LaunchConfig(
            String rig,
            List<String> suites,
            List<String> testPlans,
            UpgradeMode upgradeMode,
            boolean fakePos,
            boolean fakePinpad,
            boolean snapshotUpdate,
            List<String> simulators,
            RunScope scope) {
        this(rig, suites, testPlans, upgradeMode, fakePos, fakePinpad, snapshotUpdate, simulators, scope, false);
    }

    /** A whole-plan run of {@code rig} with every default: nothing upgraded, nothing started, Xray off. */
    public static LaunchConfig forRig(String rig) {
        return new LaunchConfig(rig, List.of(), List.of(), UpgradeMode.SKIP, false, false, false,
                List.of(), RunScope.whole(), false);
    }

    /** True when a partial run (tags, cases, or from a case) was asked for. */
    public boolean partial() {
        return scope.mode() != RunScope.Mode.WHOLE;
    }

    /**
     * The one short phrase naming what is run: the distinct directories the suites live in, in
     * order. The same summary the Python CAT's {@code LaunchConfig.suite} gives.
     */
    public String suiteSummary() {
        Set<String> dirs = new LinkedHashSet<>();
        for (String node : suites) {
            dirs.add(suiteDirectory(node));
        }
        return String.join(" ", dirs);
    }

    /**
     * The argv a run's test session would be started with (the Tests Run step, not ported yet).
     * Never {@code -n}: a plan runs serially, in plan order.
     */
    public List<String> pytestArgv(String pythonExe) {
        List<String> argv = new ArrayList<>();
        argv.add(pythonExe);
        argv.add("-m");
        argv.add("pytest");
        argv.addAll(suites);
        if (fakePos) {
            argv.add("--fake-pos");
        } else {
            argv.add("--rig");
            argv.add(rig);
        }
        if (fakePinpad && !fakePos) {
            argv.add("--fake-pinpad");
        }
        if (snapshotUpdate) {
            argv.add("--snapshot-update");
        }
        return List.copyOf(argv);
    }

    /** {@code tests/suites/charity/test_x.py::test_y} gives {@code tests/suites/charity}. */
    static String suiteDirectory(String node) {
        String path = node.split("::", 2)[0].replace('\\', '/');
        if (path.endsWith(".py") || path.endsWith(".feature")) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }
        return path;
    }

    /** Fluent construction for the command line and tests; every field defaults as {@link #forRig} does. */
    public static final class Builder {

        private final String rig;
        private final List<String> suites = new ArrayList<>();
        private final List<String> testPlans = new ArrayList<>();
        private final List<String> simulators = new ArrayList<>();
        private UpgradeMode upgradeMode = UpgradeMode.SKIP;
        private boolean fakePos;
        private boolean fakePinpad;
        private boolean snapshotUpdate;
        private RunScope scope = RunScope.whole();
        private boolean xrayReport;

        public Builder(String rig) {
            this.rig = rig;
        }

        public Builder suite(String node) {
            suites.add(node);
            return this;
        }

        public Builder testPlan(String key) {
            testPlans.add(key);
            return this;
        }

        public Builder simulator(String name) {
            simulators.add(name);
            return this;
        }

        public Builder upgradeMode(UpgradeMode mode) {
            upgradeMode = mode;
            return this;
        }

        public Builder fakePos(boolean on) {
            fakePos = on;
            return this;
        }

        public Builder fakePinpad(boolean on) {
            fakePinpad = on;
            return this;
        }

        public Builder snapshotUpdate(boolean on) {
            snapshotUpdate = on;
            return this;
        }

        public Builder scope(RunScope value) {
            scope = value;
            return this;
        }

        public Builder xrayReport(boolean on) {
            xrayReport = on;
            return this;
        }

        public LaunchConfig build() {
            return new LaunchConfig(rig, suites, testPlans, upgradeMode, fakePos, fakePinpad,
                    snapshotUpdate, simulators, scope, xrayReport);
        }
    }
}
