package com.verifone.cat.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.Requirements;

/**
 * The Supported CAT Test Plans parser. Port of the Python CAT's {@code TestThePage} and the
 * ticked-plans test in {@code test_cat_requirements.py}, on the same cut-down page
 * ({@code src/test/resources/atlassian/requirements_page.xhtml}).
 */
class RequirementsPageTest {

    private static List<PlanRequirements> rows() {
        return RequirementsPage.parse(FakeAtlassian.fixture("requirements_page.xhtml"));
    }

    private static Map<String, PlanRequirements> byKey() {
        return rows().stream().collect(java.util.stream.Collectors.toMap(PlanRequirements::key, row -> row));
    }

    @Test
    void eachPlanRowIsReadByItsHeaders() {
        Map<String, PlanRequirements> rows = byKey();
        // The page writes 56&#8804; (56 or earlier): the entity is decoded before the rule reads it.
        assertEquals(new PlanRequirements("PETROCORE-79667", "56≤", false, List.of(), List.of()),
                rows.get("PETROCORE-79667"));
        PlanRequirements cadence = rows.get("PETROCORE-118517");
        assertEquals(List.of("BUYPASS Host", "Petro Simulator Suite"), cadence.simulators());
        assertEquals(List.of("printer"), cadence.devices());
        PlanRequirements tax = rows.get("PETROCORE-135480");
        assertTrue(tax.commanderConnect());
        assertEquals(List.of("pinpad_hardware"), tax.devices());
        // The Chevron table names the same column "Commander Onboarding Required".
        assertTrue(rows.get("PETROCORE-135812").commanderConnect());
    }

    @Test
    void theTickedPlansTogetherSayWhatTheRunNeeds() {
        Requirements both = Requirements.forPlans(
                List.of("PETROCORE-118517", "PETROCORE-135480", "X-1"), rows());
        assertEquals(List.of("X-1"), both.unlisted());
        assertEquals(List.of("PETROCORE-135480"), both.commanderConnect());
        assertEquals(List.of("BUYPASS Host", "Petro Simulator Suite"), both.simulators());
        assertEquals(List.of("printer", "pinpad_hardware"), both.devices());
        assertTrue(both.needsPrinter());
        // No Devices named: the defaults, printer included.
        Requirements plain = Requirements.forPlans(List.of("PETROCORE-79667"), rows());
        assertTrue(plain.simulators().isEmpty());
        assertTrue(plain.devices().isEmpty());
        assertTrue(plain.needsPrinter());
        // Devices named without the printer: it is not needed.
        assertFalse(Requirements.forPlans(List.of("PETROCORE-135480"), rows()).needsPrinter());
    }

    @Test
    void aPlanListedTwiceKeepsItsFirstRow() {
        String page = """
                <table><tbody>
                <tr><th>Feature</th><th>Jira test Plan</th><th>Required Base</th></tr>
                <tr><td>A</td><td>PETROCORE-1</td><td>57&#8805;</td></tr>
                <tr><td>A</td><td>PETROCORE-1</td><td>56&#8804;</td></tr>
                </tbody></table>""";
        List<PlanRequirements> rows = RequirementsPage.parse(page);
        assertEquals(1, rows.size());
        assertEquals("57≥", rows.get(0).base());
    }

    @Test
    void namesSplitOnCommasSemicolonsAndLineBreaksAndDashesAreNone() {
        String page = """
                <table><tbody>
                <tr><th>Jira test Plan</th><th>Simulators</th><th>Devices</th></tr>
                <tr><td>PETROCORE-2</td><td>A Host; B Host,<br/>C Host</td><td>-</td></tr>
                </tbody></table>""";
        PlanRequirements row = RequirementsPage.parse(page).get(0);
        assertEquals(List.of("A Host", "B Host", "C Host"), row.simulators());
        assertEquals(List.of(), row.devices());
    }

    @Test
    void aTableWithoutAPlanHeaderIsSkipped() {
        String page = """
                <table><tbody>
                <tr><th>Name</th><th>Plan</th></tr>
                <tr><td>x</td><td>PETROCORE-3</td></tr>
                </tbody></table>""";
        assertEquals(List.of(), RequirementsPage.parse(page));
    }
}
