package com.verifone.cat.pats.runner.handlers.http;

import static com.verifone.cat.pats.runner.handlers.http.Harness.act;
import static com.verifone.cat.pats.runner.handlers.http.Harness.kase;
import static com.verifone.cat.pats.runner.handlers.http.Harness.run;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.ValidationsFailed;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.http.Harness.Files;

/**
 * {@code VALIDATE_PAYLOAD} over the kept replies, and PATS's JSON comparison. A mismatch is a validation failure
 * that the case carries on past; a missing reference or a reference of no known type stops the step.
 * UNVERIFIED AGAINST HARDWARE: the replies are built by the tests, not received from a Commander.
 */
class ValidatePayloadTest {

    @TempDir
    Path folder;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static HttpContext context(Files files) {
        return new HttpContext(files, null, CloudSettle.disabled());
    }

    private CaseRun validate(HttpContext context, Files files, String... pairs) {
        return run(folder, kase(act(0, "VALIDATE_PAYLOAD", pairs)), context, null);
    }

    @Test
    void aJsonReplyThatMatchesPasses() {
        Files files = new Files();
        files.references.put("r.json", utf8("{\"a\":1,\"b\":\"x\"}"));
        files.rules.put("rule.txt", utf8("KEY_NAMES=\n"));
        HttpContext context = context(files);
        context.currentPayloads().put(".json", utf8("{\"a\":1,\"b\":\"x\"}"));

        assertDoesNotThrow(() -> validate(context, files, "SELECT_REFERENCE_FILE", "r.json",
                "SELECT_RULE_FILE", "rule.txt").run());
    }

    @Test
    void aJsonDifferenceFailsTheCaseAtItsEndNamingTheRule() {
        Files files = new Files();
        files.references.put("r.json", utf8("{\"a\":1}"));
        files.rules.put("rule.txt", utf8("KEY_NAMES=\n"));
        HttpContext context = context(files);
        context.currentPayloads().put(".json", utf8("{\"a\":2}"));

        ValidationsFailed failed = assertThrows(ValidationsFailed.class, validate(context, files,
                "SELECT_REFERENCE_FILE", "r.json", "SELECT_RULE_FILE", "rule.txt")::run);
        assertTrue(failed.getMessage().contains("payload differs from r.json by rule.txt"), failed.getMessage());
        assertTrue(failed.getMessage().contains("/a: expected 1 (Integer), got 2 (Integer)"), failed.getMessage());
    }

    @Test
    void aJsonStepWithNoReplyToCompareFailsAsAValidation() {
        Files files = new Files();
        files.references.put("r.json", utf8("{}"));
        HttpContext context = context(files);

        ValidationsFailed failed = assertThrows(ValidationsFailed.class,
                validate(context, files, "SELECT_REFERENCE_FILE", "r.json")::run);
        assertTrue(failed.getMessage().contains("no JSON reply to compare"), failed.getMessage());
    }

    @Test
    void anXmlReplyComparesUnderTheRulesTagNames() {
        Files files = new Files();
        files.references.put("r.xml", utf8("<a><b>1</b></a>"));
        files.rules.put("rule.txt", utf8("TAG_NAMES=b\nATTRIBUTE_NAMES=\n"));
        HttpContext context = context(files);
        context.currentPayloads().put(".xml", utf8("<a><b>2</b></a>"));

        assertDoesNotThrow(() -> validate(context, files, "SELECT_REFERENCE_FILE", "r.xml",
                "SELECT_RULE_FILE", "rule.txt").run());
    }

    @Test
    void anXmlDifferenceFailsTheCase() {
        Files files = new Files();
        files.references.put("r.xml", utf8("<a><b>1</b></a>"));
        files.rules.put("rule.txt", utf8("TAG_NAMES=\nATTRIBUTE_NAMES=\n"));
        HttpContext context = context(files);
        context.currentPayloads().put(".xml", utf8("<a><b>2</b></a>"));

        ValidationsFailed failed = assertThrows(ValidationsFailed.class, validate(context, files,
                "SELECT_REFERENCE_FILE", "r.xml", "SELECT_RULE_FILE", "rule.txt")::run);
        assertTrue(failed.getMessage().contains("payload differs from r.xml"), failed.getMessage());
    }

    @Test
    void aReplyWithADoctypeIsNotComparedAsXml() {
        Files files = new Files();
        files.references.put("r.xml", utf8("<a/>"));
        HttpContext context = context(files);
        context.currentPayloads().put(".xml", utf8("<!DOCTYPE a [<!ENTITY e \"x\">]><a>&e;</a>"));

        ValidationsFailed failed = assertThrows(ValidationsFailed.class,
                validate(context, files, "SELECT_REFERENCE_FILE", "r.xml")::run);
        assertTrue(failed.getMessage().contains("the reply is not XML to compare"), failed.getMessage());
    }

    @Test
    void aMissingReferenceFailsTheCaseAsAValidation() {
        Files files = new Files();
        HttpContext context = context(files);

        ValidationsFailed failed = assertThrows(ValidationsFailed.class,
                validate(context, files, "SELECT_REFERENCE_FILE", "gone.json")::run);
        assertTrue(failed.getMessage().contains("reference file gone.json does not exist"), failed.getMessage());
    }

    @Test
    void aReferenceOfNoKnownTypeStopsTheStep() {
        Files files = new Files();
        files.references.put("r.txt", utf8("text"));
        HttpContext context = context(files);

        PatsStepError stop = assertThrows(PatsStepError.class,
                validate(context, files, "SELECT_REFERENCE_FILE", "r.txt")::run);
        assertTrue(stop.getMessage().contains("no payload comparator for 'r.txt'"), stop.getMessage());
    }

    @Test
    void validateSchemaTrueStopsTheStep() {
        Files files = new Files();
        files.references.put("r.json", utf8("{}"));
        HttpContext context = context(files);

        PatsStepError stop = assertThrows(PatsStepError.class,
                validate(context, files, "SELECT_REFERENCE_FILE", "r.json", "VALIDATESCHEMA", "true")::run);
        assertTrue(stop.getMessage().contains("validateSchema=true is not ported"), stop.getMessage());
    }

    // -- PATS's JSON comparison ---------------------------------------------------------------

    @Test
    void aNumberAndItsStringReadAsDifferentTypesAndDiffer() {
        String diff = JsonPayloads.differences(utf8("{\"n\":5}"), utf8("{\"n\":\"5\"}"), "");
        assertTrue(diff.contains("/n: expected 5 (Integer), got 5 (Long)"), diff);
    }

    @Test
    void aStringTrueReadsAsABooleanAndMatches() {
        assertEquals("", JsonPayloads.differences(utf8("{\"b\":true}"), utf8("{\"b\":\"TRUE\"}"), ""));
    }

    @Test
    void aStringDoubleReadsAsADoubleAndMatches() {
        assertEquals("", JsonPayloads.differences(utf8("{\"d\":1.5}"), utf8("{\"d\":\"1.5\"}"), ""));
    }

    @Test
    void aRuleKeyDropsEveryPathThatContainsIt() {
        String ref = "{\"transactionId\":\"A\",\"status\":\"ACK\",\"items\":[{\"createdAt\":\"x\"}]}";
        String reply = "{\"transactionId\":\"B\",\"status\":\"ACK\",\"items\":[{\"createdAt\":\"y\"}]}";
        assertEquals("", JsonPayloads.differences(utf8(ref), utf8(reply),
                "KEY_NAMES=/transactionId;/createdAt;\n"));
        assertFalse(JsonPayloads.differences(utf8(ref), utf8(reply), "").isEmpty());
    }

    @Test
    void keysOnlyOnOneSideAreListed() {
        String diff = JsonPayloads.differences(utf8("{\"extra\":\"x\"}"), utf8("{\"other\":1}"), "");
        assertTrue(diff.contains("only in the reference: /extra = 'x'"), diff);
        assertTrue(diff.contains("only in the reply: /other = 1"), diff);
    }

    @Test
    void aReplyThatIsNotAnObjectNeverMatches() {
        assertEquals("cannot compare: the document is not a JSON object",
                JsonPayloads.differences(utf8("[1]"), utf8("{}"), ""));
    }

    @Test
    void anEmptyKeyListDropsNothing() {
        assertTrue(JsonPayloads.ruleKeys("KEY_NAMES=").isEmpty());
        assertEquals(java.util.List.of("/a", "/b/c"), JsonPayloads.ruleKeys("KEY_NAMES=a;/b/c;"));
    }
}
