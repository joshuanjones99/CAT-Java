package com.verifone.cat.pats.runner.handlers.http;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.validate.PyRepr;
import com.verifone.cat.validate.tlog.Tlog;
import com.verifone.cat.validate.tlog.TlogOptions;
import com.verifone.cat.validate.tlog.TlogParseException;
import com.verifone.cat.validate.tlog.TlogRule;

/**
 * PATS's {@code VALIDATE_PAYLOAD} ({@code CommonActionManager.validatePayload}): the last reply, as
 * {@code SEND_HTTP_REQUEST} kept it, against the reference payload by the reference's extension. Ported from the
 * Python {@code petrotest.pats.runner.http._validate_payload}, which is the specification.
 *
 * <p>JSON compares by {@link JsonPayloads}. XML compares by the TLog comparison, which stands in for PATS's
 * {@code XMLDiffComparisonHandler} with its ignore listener: the rule file's {@code TAG_NAMES} and
 * {@code ATTRIBUTE_NAMES} are ignored. Parsing refuses a DOCTYPE and external entities, so a reply cannot pull a
 * file or a URL in.
 *
 * <p>A mismatch, a missing reference or no reply to compare is a validation failure: the case carries on and fails at
 * its end, as PATS's {@code setResult(false)} does. A reference that is neither JSON nor XML stops the step.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the reply is fake-backed in this repository's tests.
 */
final class ValidatePayload implements ActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    private final HttpContext context;

    ValidatePayload(HttpContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void handle(CaseRun run, RunnableAction action) {
        String reference = action.raw("SELECT_REFERENCE_FILE");
        if (reference == null) {
            throw new PatsStepError(action.describe() + " has no SELECT_REFERENCE_FILE field (PATS would throw here)");
        }
        String rule = action.get("SELECT_RULE_FILE");
        if (action.get("VALIDATESCHEMA").toLowerCase(Locale.ROOT).equals("true")) {
            throw new PatsStepError("validateSchema=true is not ported to CAT (no case uses it)");
        }
        Optional<byte[]> expected = context.files().referencePayload(reference);
        if (expected.isEmpty()) {
            throw new ValidationFailure("reference file " + reference + " does not exist (PATS fails the same way)");
        }
        String extension = reference.contains(".") ? reference.substring(reference.lastIndexOf('.') + 1) : "";
        Optional<String> ruleText = payloadRule(run, rule);
        String kind = extension.toUpperCase(Locale.ROOT);
        if (kind.equals("JSON")) {
            byte[] current = context.currentPayloads().get("." + extension.toLowerCase(Locale.ROOT));
            if (current == null) {
                throw new ValidationFailure("no JSON reply to compare (PATS's CurrentPayload.json is missing)");
            }
            String difference = JsonPayloads.differences(expected.get(), current, ruleText.orElse(""));
            if (!difference.isEmpty()) {
                throw new ValidationFailure("payload differs from " + reference + " by " + rule + ":\n" + difference);
            }
        } else if (kind.equals("XML")) {
            compareXml(reference, expected.get(), rule, ruleText.orElse(""));
        } else {
            throw new PatsStepError("no payload comparator for " + PyRepr.reprString(reference)
                    + " (PATS throws here)");
        }
    }

    /**
     * {@code RuleFiles\Payloads\<name>}: the case's own copy, else the shared folder's. A missing one is a warning, as
     * in PATS, and nothing is ignored.
     */
    private Optional<String> payloadRule(CaseRun run, String name) {
        if (name.strip().isEmpty()) {
            return Optional.empty();
        }
        Optional<byte[]> found = context.files().payloadRule(name);
        if (found.isEmpty()) {
            LOG.warn("{}: payload rule file {} does not exist", run.kase().key(), name);
            return Optional.empty();
        }
        return Optional.of(new String(found.get(), StandardCharsets.UTF_8));
    }

    /**
     * {@code XMLDiffComparisonHandler}: the kept reply, its lines joined, against the reference under the rule's
     * lists.
     */
    private void compareXml(String reference, byte[] expected, String rule, String ruleText) {
        byte[] current = context.currentPayloads().get(".xml");
        String text = current == null ? "" : new String(current, StandardCharsets.UTF_8).replaceAll("\\R", "");
        Element actual;
        try {
            actual = parseXml(text);
        } catch (Exception e) {
            throw new ValidationFailure("the reply is not XML to compare (PATS's CurrentPayload.xml): " + e.getMessage());
        }
        Map<String, List<String>> parsed = TlogRule.parseText(ruleText);
        TlogOptions options = new TlogOptions(
                new HashSet<>(parsed.getOrDefault("TAG_NAMES", List.of())),
                new HashSet<>(parsed.getOrDefault("ATTRIBUTE_NAMES", List.of())),
                true,
                false);
        Tlog.Diff diff;
        try {
            diff = Tlog.compare(expected, actual, options);
        } catch (TlogParseException e) {
            // PETROCORE-36862's -Tlog.xml is two documents end to end: a failed validation, not a stop.
            throw new ValidationFailure("the reference is not well-formed XML: " + e.getMessage());
        }
        if (!diff.ok()) {
            throw new ValidationFailure("payload differs from " + reference + " (rule " + rule + "):\n"
                    + diff.describe());
        }
    }

    private static Element parseXml(String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(text))).getDocumentElement();
    }
}
