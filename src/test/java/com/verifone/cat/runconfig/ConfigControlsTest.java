package com.verifone.cat.runconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.PlanSource;
import com.verifone.cat.plans.TestPlan;

/**
 * The Config tab's choices after Run scope, the Downloads binding, the network paths (with a fake network catalogue
 * run inline) and the launch fields: the Python's {@code cat.web.form.ConfigForm}. Nothing reaches Confluence, Jira or
 * petroapps. Evidence about this repository only.
 */
class ConfigControlsTest {

    @TempDir
    Path tmp;

    /** A Downloads stand-in: the choice, its blockers, and the change listener. */
    static final class FakeDownloads implements DownloadsState {
        DownloadsState.Choice choice = new DownloadsState.Choice("", "", "skip", "", "", "", "", "buypass", "");
        List<String> problems = new ArrayList<>();
        final List<Runnable> listeners = new ArrayList<>();

        @Override
        public DownloadsState.Choice current() {
            return choice;
        }

        @Override
        public void restore(DownloadsState.Choice saved) {
            choice = saved;
        }

        @Override
        public List<String> problems() {
            return problems;
        }

        @Override
        public void onChange(Runnable listener) {
            listeners.add(listener);
        }

        void change(DownloadsState.Choice next) {
            choice = next;
            listeners.forEach(Runnable::run);
        }
    }

    /** A network source over the fixture's seed, counting what is fetched, with the old plan's cases now known. */
    static final class FakeNetwork implements PlanSource {
        final List<String> fetched = new ArrayList<>();
        final AtomicInteger catalogues = new AtomicInteger();

        @Override
        public List<TestPlan> catalogue() {
            catalogues.incrementAndGet();
            return List.of(new TestPlan(ConfigFixture.PLAN, "Charity Donations", "Automated", "Active", "4", "", ""));
        }

        @Override
        public List<PlanCase> cases(String planKey) {
            fetched.add(planKey);
            return ConfigFixture.seed().cases(planKey);
        }

        @Override
        public List<PlanRequirements> requirements() {
            return List.of();
        }
    }

    @Test
    void everyChoiceIsSavedUnderThePythonsKeysAndRestored() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        FakeDownloads downloads = new FakeDownloads();
        form.attachDownloads(downloads);

        assertTrue(form.set("tpBranch", "PETROCORE-141728"));
        assertTrue(form.set("ccOnboard", "on"));
        assertTrue(form.set("ccLab", "CST"));
        assertTrue(form.set("ccEmail", "me@example.test"));
        assertTrue(form.set("ccPassword", "demo-pass"));
        assertTrue(form.set("ccServiceId", "1234567"));
        assertTrue(form.set("execMode", "BDD"));
        assertTrue(form.set("ifsfMode", "-ifsfEPS"));
        assertTrue(form.set("coverage", "on"));
        assertTrue(form.set("xray", "off"));
        assertFalse(form.set("ccLab", "Moon"));
        assertFalse(form.set("execMode", "fast"));
        assertFalse(form.set("rig", "lab"));
        downloads.change(new DownloadsState.Choice("Solutions", "134", "new_install", "master", "74", "", "",
                "buypass", ""));

        Map<String, Object> saved = SavedChoices.read(form.savedPath());
        assertEquals("PETROCORE-141728", saved.get("config-tp-branch"));
        assertEquals(true, saved.get("config-cc-onboard"));
        assertEquals("CST", saved.get("config-cc-lab"));
        assertEquals("demo-pass", saved.get("config-cc-password"));
        assertEquals("BDD", saved.get("config-exec"));
        assertEquals("-ifsfEPS", saved.get("config-ifsf"));
        assertEquals(true, saved.get("config-coverage"));
        assertEquals(false, saved.get("config-xray"));
        assertEquals("Solutions", saved.get("config-cmd-branch"));
        assertEquals("134", saved.get("config-cmd-build"));
        assertEquals("new_install", saved.get("config-upgrade"));
        assertEquals("74", saved.get("config-sim-build"));

        ConfigForm again = new ConfigForm(ConfigFixture.catalogue(tmp, tmp.resolve("repo")), form.savedPath(),
                tmp.resolve("repo"));
        again.load();
        FakeDownloads restored = new FakeDownloads();
        again.attachDownloads(restored);
        ConfigControls c = again.controls();
        assertEquals("PETROCORE-141728", c.testPlanBranch());
        assertTrue(c.ccOnboard());
        assertEquals("CST", c.ccLab());
        assertEquals("BDD", c.execMode());
        assertFalse(c.xray());
        assertEquals("Solutions", restored.choice.commanderBranch());
        assertEquals("master", restored.choice.simBranch());
    }

    @Test
    void anUnofferedSavedValueFallsBackAndABuildWithoutItsBranchIsDropped() throws IOException {
        Path saved = tmp.resolve("cfg").resolve("last-config.json");
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, "{\"config-cc-lab\":\"Moon\",\"config-exec\":\"fast\",\"config-cmd-build\":\"9\","
                + "\"config-cmd-local-build\":\"Solutions134\",\"config-upgrade\":\"bogus\"}");
        ConfigForm form = ConfigFixture.form(tmp);
        FakeDownloads downloads = new FakeDownloads();
        form.attachDownloads(downloads);

        assertEquals("Prod", form.controls().ccLab());
        assertEquals("dev", form.controls().execMode());
        assertEquals("", downloads.choice.commanderBuild());
        assertEquals("Solutions134", downloads.choice.localBuild());
        assertEquals("", downloads.choice.upgradeMode());
    }

    @Test
    void theDownloadsBlockersStopARun() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        FakeDownloads downloads = new FakeDownloads();
        form.attachDownloads(downloads);
        assertTrue(form.missingRequirements().isEmpty(), form.missingRequirements().toString());

        downloads.problems.add("a Simulator Build number on master");
        assertEquals(List.of("a Simulator Build number on master"), form.missingRequirements());
        assertTrue(form.launchFields().isEmpty());
    }

    @Test
    void theLaunchFieldsAreThePythonsLaunchConfig() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        FakeDownloads downloads = new FakeDownloads();
        form.attachDownloads(downloads);
        downloads.change(new DownloadsState.Choice("Solutions", "", "new_install", "master", "74", "", "", "buypass",
                "Solutions120"));
        form.set("ccOnboard", "on");
        form.set("ccEmail", " me@example.test ");
        form.set("tpBranch", " ");

        LaunchFields f = form.launchFields().orElseThrow();
        assertEquals(List.of(ConfigFixture.PLAN), f.testPlans());
        assertEquals(List.of(ConfigFixture.IMPORTER_NODE, ConfigFixture.PLAIN_NODE, ConfigFixture.FUEL_NODE),
                f.suites());
        assertEquals(UpgradeMode.NEW_INSTALL, f.upgradeMode());
        assertEquals("", f.commanderBranch(), "a branch without its build is not carried");
        assertEquals("", f.localBuild(), "a local build only with no branch");
        assertEquals("master", f.simBranch());
        assertEquals("master", f.testPlanBranch(), "blank is master");
        assertTrue(f.provisionRegister());
        assertTrue(f.commanderConnect());
        assertEquals("me@example.test", f.commanderConnectSettings().email());

        LaunchConfig config = f.toConfig("lab", List.of("Petro Simulator Suite"), List.of("printer"));
        assertEquals("lab", config.rig());
        assertEquals("74", config.simBuild());
        assertEquals(List.of("printer"), config.connections());
        assertTrue(config.xrayReport());
        assertFalse(config.keepSimulators());
    }

    @Test
    void refreshWithNoNetworkSaysSoAndRefreshWithOneReadsThePageAndTheTickedPlans() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.OLD_PLAN);
        assertTrue(form.refreshFromConfluence().contains("no Atlassian source"));
        assertTrue(form.view().catalogueNote().contains("refresh failed"));

        FakeNetwork network = new FakeNetwork();
        form.withNetwork(new PlanCatalogue(tmp.resolve("cache"), network, ConfigFixture.seed(), tmp.resolve("repo")),
                Runnable::run);
        assertEquals("", form.refreshFromConfluence());
        assertEquals(1, network.catalogues.get());
        assertTrue(network.fetched.contains(ConfigFixture.OLD_PLAN));
        assertFalse(form.refreshing());
        assertEquals(List.of(), form.plans(), "a plan the page no longer offers is unticked");
        assertTrue(form.view().catalogueAge().startsWith("cached"), form.view().catalogueAge());
    }

    @Test
    void aTickedPlanWithNoFetchedCaseListIsFetchedOnceAndAWaitingStartRuns() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        FakeNetwork network = new FakeNetwork();
        List<Runnable> queued = new ArrayList<>();
        form.withNetwork(new PlanCatalogue(tmp.resolve("cache"), network, ConfigFixture.seed(), tmp.resolve("repo")),
                queued::add);
        AtomicInteger started = new AtomicInteger();
        form.onFetched(started::incrementAndGet);
        form.togglePlan(ConfigFixture.PLAN);

        form.view();
        form.view();
        assertEquals(1, queued.size(), "once per plan per session");
        assertEquals(List.of(ConfigFixture.PLAN), form.fetchingCases());
        assertTrue(form.runWhenFetched().startsWith("Fetching the case list for " + ConfigFixture.PLAN + " from Jira"));

        queued.get(0).run();
        assertEquals(List.of(ConfigFixture.PLAN), network.fetched);
        assertTrue(form.fetchingCases().isEmpty());
        assertEquals(1, started.get(), "the run Start waited on starts");
        form.view();
        assertEquals(1, queued.size());
    }

    @Test
    void aPlanRowCountsCasesAsXrayDoesAndSaysHowManyRun() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        List<String> labels = new ArrayList<>();
        form.view().sections().forEach(s -> s.rows().forEach(r -> labels.add(r.label())));
        assertTrue(labels.get(0).contains("4 cases"), labels.get(0));
        assertTrue(labels.get(0).endsWith("3 runnable"), labels.get(0));
        assertTrue(labels.get(1).endsWith("cases not fetched"), labels.get(1));
        assertEquals("none runnable", ConfigForm.runnableHint(List.of(new PlanCase("K", "s", null))));
    }

    @Test
    void aPlanWithNoMigratedCaseNamesWherePetroappsWasRead() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.withPetroapps(tmp.resolve("no-petroapps"));
        form.togglePlan(ConfigFixture.OLD_PLAN);
        List<String> missing = form.missingRequirements();
        assertEquals(1, missing.size(), missing.toString());
        assertTrue(missing.get(0).startsWith("a test plan with at least one migrated case"), missing.get(0));
    }

    @Test
    void theTagsCountGivesTheRecordedRunTime() {
        assertEquals("~1 min", RecordedDurations.durationWords(10));
        assertEquals("~31 min", RecordedDurations.durationWords(31 * 60));
        assertEquals("~2 h", RecordedDurations.durationWords(7200));
        assertEquals("~2 h 5 min", RecordedDurations.durationWords(7500));
        Map<String, String> nodes = Map.of("A", "a.feature", "B", "b.feature");
        assertEquals(" · ~2 min", RecordedDurations.timeWords(List.of("A"), nodes, Map.of("a.feature", 120.0)));
        assertEquals(" · ~2 min (1 timed)",
                RecordedDurations.timeWords(List.of("A", "B"), nodes, Map.of("a.feature", 120.0)));
        assertEquals("", RecordedDurations.timeWords(List.of("B"), nodes, Map.of()));
        assertTrue(new RecordedDurations(tmp.resolve("none.json")).read().isEmpty());
    }
}
