package com.verifone.cat.runconfig;

import java.util.List;

import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;

/**
 * Everything the Config tab decides about a run: the Python's {@code FormLaunchConfigMixin._launch_config} fields,
 * from {@link ConfigForm#launchFields()}. The web Run tab builds its {@link LaunchConfig} from these with
 * {@link #toConfig}; the rig, the simulators and the connections are the run's own (the plan's Supported CAT Test
 * Plans row replaces the defaults at run start).
 *
 * @param testPlans        the ticked plans, in tick order
 * @param suites           the ticked plans' migrated case nodes, each once, in run order
 * @param scope            the Run scope
 * @param upgradeMode      the Downloads section's Upgrade mode
 * @param dist             the Distribution (FEP)
 * @param testPlanBranch   the petroapps branch ({@code master} when blank)
 * @param executionMode    {@code dev} or {@code BDD}
 * @param ifsfMode         "" or one of {@code -ifsfPOS}, {@code -ifsfEPS}, {@code -ifsfAuth}
 * @param coverage         the JaCoCo switch
 * @param commanderBranch  the Commander Build's branch, only with its number
 * @param commanderBuild   the Commander Build's number, only with its branch
 * @param localBuild       with no branch, this PC's build folder ("" is the newest)
 * @param foundationBranch a viper-only build's foundation branch, only with its number
 * @param foundationBuild  the foundation build's number
 * @param simBranch        the Simulator Build's branch, only with its number
 * @param simBuild         the Simulator Build's number
 * @param provisionRegister always true from the Config tab, as the Python's
 * @param xrayReport       Report to Xray
 * @param commanderConnect the Onboard switch
 * @param commanderConnectSettings the lab, email, service ID and password it onboards with (trimmed)
 */
public record LaunchFields(
        List<String> testPlans,
        List<String> suites,
        RunScope scope,
        UpgradeMode upgradeMode,
        String dist,
        String testPlanBranch,
        String executionMode,
        String ifsfMode,
        boolean coverage,
        String commanderBranch,
        String commanderBuild,
        String localBuild,
        String foundationBranch,
        String foundationBuild,
        String simBranch,
        String simBuild,
        boolean provisionRegister,
        boolean xrayReport,
        boolean commanderConnect,
        CommanderConnect commanderConnectSettings) {

    public LaunchFields {
        testPlans = List.copyOf(testPlans);
        suites = List.copyOf(suites);
    }

    /**
     * The run: these fields on {@code rig}, starting {@code simulators} and probing {@code connections}.
     * {@code keepSimulators}, {@code fakePos} and the run_plan-only flags stay off, as from the Python's Config tab.
     */
    public LaunchConfig toConfig(String rig, List<String> simulators, List<String> connections) {
        return new LaunchConfig(rig, suites, testPlans, upgradeMode, false, false, false, simulators, scope,
                xrayReport, coverage, commanderConnect, commanderConnectSettings, dist, testPlanBranch,
                executionMode, List.of(), connections, false, false, false, commanderBranch, commanderBuild,
                simBranch, simBuild, ifsfMode, localBuild, foundationBranch, foundationBuild, provisionRegister,
                false,
                scope.mode() == RunScope.Mode.TAGS ? scope.tags() : "",
                scope.mode() == RunScope.Mode.CASES ? scope.cases() : List.of(),
                scope.mode() == RunScope.Mode.FROM ? scope.fromCase() : "");
    }
}
