package com.verifone.cat.runconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Config tab's choices and their saved file, against the fake seed. Test evidence only. */
class ConfigFormTest {

    @TempDir
    Path tmp;

    private Path saved() {
        return tmp.resolve("cfg").resolve("last-config.json");
    }

    @Test
    void togglingAPlanSavesItUnderTheTuisKeys() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        assertEquals(List.of(), form.plans(), "two plans offered: none ticked");

        form.togglePlan(ConfigFixture.PLAN);
        assertEquals(List.of(ConfigFixture.PLAN), form.plans());

        Map<String, Object> written = SavedChoices.read(saved());
        assertEquals(List.of(ConfigFixture.PLAN), written.get("config-plans"));
        assertEquals("whole", written.get("config-scope"));
        assertTrue(Files.exists(saved()));
    }

    @Test
    void aPlanTheCatalogueDoesNotOfferIsIgnored() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan("PETROCORE-404");
        assertEquals(List.of(), form.plans());
        assertFalse(Files.exists(saved()));
    }

    @Test
    void choicesReadBackFromTheSameFileInANewForm() throws IOException {
        ConfigForm first = ConfigFixture.form(tmp);
        first.togglePlan(ConfigFixture.PLAN);
        first.setScope("cases", null, false);
        first.tickCase(ConfigFixture.PLAIN);

        ConfigForm second = new ConfigForm(ConfigFixture.catalogue(tmp, tmp.resolve("repo")), saved(),
                tmp.resolve("repo"));
        second.load();

        assertEquals(List.of(ConfigFixture.PLAN), second.plans());
        assertEquals(RunScope.Mode.CASES, second.scope().mode());
        assertEquals(List.of(ConfigFixture.PLAIN), second.scope().cases());
        assertFalse(second.scope().prerequisites());
    }

    @Test
    void keysTheFormDoesNotOwnSurviveASave() throws IOException {
        Files.createDirectories(saved().getParent());
        Files.writeString(saved(), """
                {"config-tp-branch": "feature/x", "config-cc-email": "lab@example.com",
                 "config-jacoco": true, "config-download": "old"}
                """);
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);

        Map<String, Object> written = SavedChoices.read(saved());
        assertEquals("feature/x", written.get("config-tp-branch"));
        assertEquals("lab@example.com", written.get("config-cc-email"));
        assertFalse(written.containsKey("config-jacoco"), "a retired key is dropped, not written back");
        assertFalse(written.containsKey("config-download"));
    }

    @Test
    void aSavedPlanNoLongerOfferedIsDropped() throws IOException {
        Files.createDirectories(saved().getParent());
        Files.writeString(saved(), "{\"config-plans\": [\"PETROCORE-404\", \"" + ConfigFixture.PLAN + "\"]}");
        ConfigForm form = ConfigFixture.form(tmp);
        assertEquals(List.of(ConfigFixture.PLAN), form.plans());
    }

    @Test
    void theRunScopeNeedsAPlanBeforeItCanStart() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        assertEquals(List.of("a test plan"), form.missingRequirements());
        form.togglePlan(ConfigFixture.PLAN);
        assertTrue(form.missingRequirements().isEmpty(), form.missingRequirements().toString());
    }

    @Test
    void casesWithNothingTickedSaysSoAndCannotStart() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("cases", null, true);

        ConfigView view = form.view();
        assertEquals(List.of("a run scope that selects a case (Cases: no case is ticked)"), view.missing());
        assertEquals(List.of("Nothing to run yet: pick a run scope that selects a case (Cases: no case is ticked)."),
                view.scopeWarnings());
    }

    @Test
    void tickingACaseMovesTheRunScopeAndAddsItsPrerequisite() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("cases", null, true);
        form.tickCase(ConfigFixture.PLAIN);

        ConfigView view = form.view();
        assertTrue(view.partial());
        assertTrue(view.missing().isEmpty(), view.missing().toString());
        assertEquals("2 of 3: 2, and 1 prerequisite", view.scopeSummary());
        assertTrue(view.scopeWarnings().get(0).contains("is added (1, before 2)"), view.scopeWarnings().toString());
        ConfigView.CaseRow importer = view.caseRows().get(0);
        assertEquals(ConfigFixture.IMPORTER, importer.key());
        assertTrue(importer.added());
        assertTrue(importer.ticked());
    }

    @Test
    void withPrerequisitesLeftOutTheImporterIsNotRun() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("cases", null, false);
        form.tickCase(ConfigFixture.PLAIN);

        ConfigView view = form.view();
        assertEquals(List.of(ConfigFixture.PLAIN), view.scope().cases());
        assertFalse(view.caseRows().get(0).ticked());
        assertTrue(view.scopeWarnings().get(0).contains("add prerequisites"), view.scopeWarnings().toString());
    }

    @Test
    void tagsFollowTheExpressionUntilACaseIsTickedByHand() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("tags", "@fuel", false);

        assertEquals("1 of 3 cases", form.view().tagCount());
        assertNull(form.scope().tagCases(), "the ticks follow the expression");
        assertTrue(ticked(form, ConfigFixture.FUEL));

        form.tickCase(ConfigFixture.FUEL);
        assertEquals(List.of(), form.scope().tagCases());
        assertFalse(ticked(form, ConfigFixture.FUEL));
        assertTrue(form.view().missing().get(0).contains("every case is unticked"), form.view().missing().toString());
    }

    @Test
    void aTagsEditResetsTheHandTicks() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("tags", "@fuel", true);
        form.tickCase(ConfigFixture.PLAIN);
        assertEquals(List.of(ConfigFixture.PLAIN, ConfigFixture.FUEL), form.scope().tagCases());

        form.setScope("tags", "@fuel", true);
        assertEquals(List.of(ConfigFixture.PLAIN, ConfigFixture.FUEL), form.scope().tagCases(),
                "the same text keeps the hand ticks");

        form.setScope("tags", "@fuel and @x", true);
        assertNull(form.scope().tagCases(), "a new expression follows its ticks again");
    }

    private static boolean ticked(ConfigForm form, String key) {
        return form.view().caseRows().stream().filter(row -> row.key().equals(key)).findFirst()
                .orElseThrow().ticked();
    }

    @Test
    void theNeedsLineNamesTheTickedPlansRequirements() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        assertEquals("", form.view().needs());
        form.togglePlan(ConfigFixture.PLAN);
        assertTrue(form.view().needs().contains("Commander Connect (" + ConfigFixture.PLAN + ")"),
                form.view().needs());
        assertTrue(form.view().needs().contains("simulators Fuel Sim"), form.view().needs());
    }

    @Test
    void theFilterHidesPlansWithoutChangingTheChoices() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.setFilter("donations");
        ConfigView view = form.view();
        assertEquals(1, view.sections().stream().mapToInt(s -> s.rows().size()).sum());
        assertEquals(2, view.planCount());
        assertTrue(form.plans().isEmpty());
    }

    @Test
    void theDeprecatedSectionSortsLast() throws IOException {
        ConfigView view = ConfigFixture.form(tmp).view();
        assertEquals(List.of("Automated", "Deprecated"),
                view.sections().stream().map(ConfigView.Section::name).toList());
    }

    @Test
    void theCatalogueIsCalledWhatItIs() throws IOException {
        ConfigView view = ConfigFixture.form(tmp).view();
        assertEquals("seed", view.catalogueSource());
        assertEquals("shipped snapshot - never fetched here", view.catalogueAge());
    }
}
