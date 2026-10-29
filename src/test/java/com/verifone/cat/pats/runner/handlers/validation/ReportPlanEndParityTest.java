package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.context;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.declaredNothing;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.receiptSource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.resource;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.run;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.xml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pats.runner.CaseRun;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The end-of-plan loyalty detail reports (PETROCORE-37031, PETROCORE-37033) that failed in CAT-Java run
 * {@code a4eda2bf8602}: the Java verdict against PATS's own {@code -Report.xml} and {@code REPORT-RULE.txt} equals
 * the Python's on the same inputs.
 *
 * <p>The inputs under {@code pats/runner/validation/report37031/} are PATS's references (petroapps, read-only, copied)
 * and two reports made from each: {@code -reordered} (every {@code batch} shuffled, every {@code epsSTAN} and
 * {@code refNum} new, as the EPS gives them each run) and {@code -short} (that report with one sale left out, as an
 * earlier failed case leaves the plan's batch report). {@code python-verdicts.json} is what the Python's
 * {@code _validate_report} comparison ({@code _xml_diff}, then {@code entries_sorted}) says of each, made by running
 * it from {@code C:\gitrepos\CAT\.venv} on 2026-10-29. Neither run folder kept the live report, so these are not
 * the rig's captures: they are evidence about this comparison, not about the rig.
 */
class ReportPlanEndParityTest {

    private static final String DIR = "pats/runner/validation/report37031/";

    @TempDir
    Path folder;

    @ParameterizedTest
    @CsvSource({
        "PETROCORE-37031, SecureTerminalBatchLoyaltyDetail, reordered",
        "PETROCORE-37031, SecureTerminalBatchLoyaltyDetail, short",
        "PETROCORE-37033, LoyaltyPpuTransactionDetails, reordered",
        "PETROCORE-37033, LoyaltyPpuTransactionDetails, short",
    })
    void theVerdictIsThePythons(String key, String report, String variant) throws IOException {
        String reference = resource(DIR + key + "-Report.xml");
        String live = resource(DIR + key + "-" + variant + ".xml");
        JsonNode verdicts = new ObjectMapper().readTree(resource(DIR + "python-verdicts.json"));
        String python = verdicts.get(key + "-" + variant + ".xml").asText();

        ValidationKit.FakeReferences refs = new ValidationKit.FakeReferences()
                .reference("Report", key + "-Report.xml", reference)
                .sharedFile("AutomationTests/rulefiles/reports", "REPORT-RULE.txt", resource(DIR + "REPORT-RULE.txt"));
        ValidationKit.FakeCommander commander = new ValidationKit.FakeCommander();
        commander.reports.put("vviperrept/" + report, xml("<root>" + live.replaceFirst("^<\\?xml[^>]*\\?>", "")
                + "</root>"));
        ValidationContext context = context(receiptSource(""), new ValidationKit.FakePos(), commander,
                new ValidationKit.FakePackets(), refs, noEnv());
        CaseRun run = run(kase(key, action(2, "VALIDATE_REPORT", "REPORTNAME", report, "CMD", "vviperrept",
                "SELECT_FILE", key + "-Report.xml", "SELECT_RULE_FILE", "REPORT-RULE.txt", "REPORTTYPE", "period",
                "PERIOD", "1", "FILENAME", "current", "CASHIERNUM", "1", "TOPAZNUM", "101")),
                context, folder, false, declaredNothing(), folder);

        String java = verdict(run);

        if (python.isEmpty()) {
            assertEquals("", java, "the Python passes " + key + "-" + variant);
        } else {
            assertFalse(java.isEmpty(), "the Python fails " + key + "-" + variant);
            String expected = "report differs from " + key + "-Report.xml:\n" + python;
            assertTrue(java.contains(expected), "Java:\n" + java + "\n\nPython:\n" + expected);
        }
    }
}
