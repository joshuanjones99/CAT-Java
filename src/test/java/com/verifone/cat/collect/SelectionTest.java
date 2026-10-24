package com.verifone.cat.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The pure half of {@code petrotest.collect}: names, baselines, the rule table and the matching primitives. */
class SelectionTest {

    @ParameterizedTest
    @CsvSource({
            "PETROCORE-36912-CATReceipt.txt, true",
            "PETROCORE-36912-1-CATReceipt.txt, true",
            "PETROCORE-36912-CATTlog.xml, true",
            "PETROCORE-36912-2-CATTlog.xml, true",
            "PETROCORE-36912-PostFuelGetRewardsResponse-PCATS02-CATPacket.xml, true",
            "PETROCORE-36912-PostFuelGetRewardsResponse.xml, false",
            "PETROCORE-36912-CATReport.xml, true",
            "PETROCORE-36912-Report.xml, false",
            "PETROCORE-36912-Tlog.xml, true",
            "PETROCORE-36912-1-Receipt.txt, false",
            "PETROCORE-36912-Rule.txt, false",
            "PETROCORE-36912-TestCase.xml, false",
            "PETROCORE-369121-CATReceipt.txt, false",
    })
    void catBaselinesAreToldFromPatsFiles(String name, boolean ours) {
        assertEquals(ours, Selection.isBaseline(name, "PETROCORE-36912"));
    }

    @Test
    void theCatTestsAreThePyAndTheFeatureAndNothingElse() {
        assertTrue(Selection.isCatTest("PETROCORE-1-TestCase.py"));
        assertTrue(Selection.isCatTest("PETROCORE-1-TestCase.feature"));
        assertFalse(Selection.isCatTest("PETROCORE-1-TestCase.xml"));
        assertFalse(Selection.isCatTest("PETROCORE-1-TestCase.scenario"));
    }

    @Test
    void theCaseKeyIsTheTestNameWithoutItsTestCaseTail() {
        assertEquals("PETROCORE-61338", Selection.caseOf("PETROCORE-61338-TestCase.py"));
        assertEquals("PETROCORE-61338", Selection.caseOf("PETROCORE-61338-TestCase.feature"));
        assertEquals("test_thing", Selection.caseOf("test_thing.py"));
    }

    @Test
    void fnmatchKnowsStarAndQuestionAndNothingMore() {
        assertTrue(Selection.fnmatch("*-testcase.*").matcher("petrocore-1-testcase.py").matches());
        assertTrue(Selection.fnmatch("a?c").matcher("abc").matches());
        assertFalse(Selection.fnmatch("a?c").matcher("abbc").matches());
        assertTrue(Selection.fnmatch("*.txt").matcher("x.txt").matches());
        assertFalse(Selection.fnmatch("*.txt").matcher("x.txt.bak").matches());
    }

    @Test
    void theRuleTableHasEveryPatsRuleInTheScriptsOrder() {
        List<String> names = Rules.RULES.stream().map(Rule::name).toList();
        assertEquals("TestCase", names.get(0));
        assertEquals("Packets/BUYPASS", names.get(11));
        assertEquals("Payloads rule", names.get(names.size() - 1));
        assertEquals(29, names.size());
        assertEquals(15, Rules.SHARED_ASSETS.size());
    }

    @Test
    void caseFoldersPartitionTheUnionByCaseKey() {
        Blob blob = new Blob("AutomationTests/TestCases/PETROCORE-2/PETROCORE-2-Rule.txt", "a".repeat(40));
        Map<String, List<Blob>> index = Map.of(
                "automationtests/testcases/petrocore-2/petrocore-2-rule.txt", List.of(blob));
        Map<String, Map<String, List<Blob>>> folders = Selection.caseFolders(index);
        assertEquals(List.of("petrocore-2"), List.copyOf(folders.keySet()));
        assertEquals(List.of("petrocore-2-rule.txt"), List.copyOf(folders.get("petrocore-2").keySet()));
    }
}
