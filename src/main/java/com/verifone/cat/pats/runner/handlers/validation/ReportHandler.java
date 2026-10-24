package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.validate.tlog.TlogOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * {@code VALIDATE_REPORT}: a Commander report against PATS's {@code -Report.xml}, or CAT's own
 * {@code -CATReport.xml} beside the test where there is one. Ported from the Python
 * {@code validation._validate_report}, {@code _report_element} and {@code entries_sorted}.
 *
 * <p>CAT's baseline, its masks and its entry ordering are the rig owner's decision (2026-10-11): the end-of-plan
 * batch reports list every card sale the plan made, with a STAN and reference number the EPS gives afresh each run
 * and in no fixed order, so PATS's references cannot match them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
final class ReportHandler implements ActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The suffix of CAT's own report baseline. */
    static final String BASELINE_SUFFIX = "-CATReport.xml";

    /** The rule file a report reads when it names none. */
    static final String DEFAULT_RULE = "REPORT-RULE.txt";

    /** {@code compareReportXML}'s element-name aliases (`automn_app_config.prop` 121-129). */
    private static final Map<String, String> ALIASES = Map.of(
            "fpHoseTest", "fptHose",
            "dcrStat", "dcrst",
            "popdiscprgmrpt", "popDiscPrgm");

    private final ValidationContext context;

    ReportHandler(ValidationContext context) {
        this.context = context;
    }

    /**
     * {@code compareReportXML}'s extraction: {@code <name>Pd} for {@code vrubyrept}, {@code <reportData>} for
     * {@code vviperrept}. Any other command has nothing to compare, so {@code null}.
     */
    static Element reportElement(Element root, String cmd, String report) {
        String wanted;
        if ("vviperrept".equals(cmd)) {
            wanted = "reportData";
        } else if ("vrubyrept".equals(cmd)) {
            wanted = ALIASES.getOrDefault(report, report) + "Pd";
        } else {
            return null;
        }
        return ValidationSupport.firstNamed(root, wanted);
    }

    @Override
    public void handle(CaseRun run, RunnableAction action) throws Exception {
        String name = ValidationSupport.require(action, "SELECT_FILE");
        Path catPath = ValidationSupport.baselineDir(run).resolve(
                ValidationSupport.stem(name, "Report") + BASELINE_SUFFIX);
        boolean hasReference = context.references().reference("Report", name).isPresent();
        if (!Files.isRegularFile(catPath) && !hasReference) {
            throw new ValidationFailure("reference file " + name + " does not exist (PATS fails the same way)");
        }
        String cmd = ValidationSupport.require(action, "CMD");
        String report = ValidationSupport.require(action, "REPORTNAME");
        Element root = context.commander().fetchReport(cmd, report,
                action.get("PERIOD", "1"),
                action.get("FILENAME", "current"),
                "cashier".equals(action.get("REPORTTYPE").toLowerCase(Locale.ROOT)) ? action.get("CASHIERNUM") : null,
                action.get("TOPAZNUM"));
        Element actual = reportElement(root, cmd, report);
        if (actual == null) {
            throw new ValidationFailure(cmd + " " + report + ": the Commander's reply holds no report element");
        }
        String rule = action.get("SELECT_RULE_FILE", DEFAULT_RULE);
        ValidationSupport.RuleLists lists = ValidationSupport.ruleLists(context, ValidationSupport.REPORT_RULES, rule);
        List<String> tags = ValidationSupport.concat(lists.tags(), ValidationSupport.CAT_REPORT_IGNORE_TAGS);
        List<String> attributes = ValidationSupport.concat(lists.attributes(),
                ValidationSupport.CAT_REPORT_IGNORE_ATTRIBUTES);
        TlogOptions options = ValidationSupport.options(tags, attributes);

        if (run.update() || "1".equals(context.env(ValidationContext.REPORT_REBASE_ENV))) {
            if (!hasReference || !differs(referenceBytes(name), actual, tags, attributes, options).isEmpty()) {
                Files.write(catPath, ValidationSupport.serialiseBytes(actual));
                LOG.warn("{}: wrote {}; review it against {}", run.kase().key(), catPath, name);
            }
            return;
        }
        boolean own = Files.isRegularFile(catPath);
        byte[] reference = own ? Files.readAllBytes(catPath) : referenceBytes(name);
        String difference = differs(reference, actual, tags, attributes, options);
        if (!difference.isEmpty()) {
            throw new ValidationFailure("report differs from " + (own ? catPath.getFileName() : name)
                    + ":\n" + difference);
        }
    }

    /**
     * The difference from {@code reference}, or empty when they match. A difference that the entry ordering
     * explains (the same entries in another order) is not one.
     */
    private static String differs(byte[] reference, Element actual, List<String> tags, List<String> attributes,
            TlogOptions options) {
        String difference = ValidationSupport.xmlDiff(reference, actual, options);
        if (difference.isEmpty()) {
            return "";
        }
        byte[] ordered;
        try {
            ordered = ValidationSupport.serialiseBytes(
                    ValidationSupport.entriesSorted(ValidationSupport.parse(reference), tags, attributes));
        } catch (RuntimeException e) {
            return difference;
        }
        Element unordered = ValidationSupport.entriesSorted(actual, tags, attributes);
        return ValidationSupport.xmlDiff(ordered, unordered, options).isEmpty() ? "" : difference;
    }

    private byte[] referenceBytes(String name) {
        return context.references().reference("Report", name).orElseThrow(
                () -> new PatsStepError("no Report reference named " + name));
    }
}
