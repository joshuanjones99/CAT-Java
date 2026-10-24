package com.verifone.cat.launch;

/**
 * Step 6, Generate Report. Unavailable: the HTML summary ({@code cat.report.html}, which
 * {@code petrotest/report/html.py} backs) is not ported. The JUnit XML writer and the artifact index
 * are ported ({@code report.JunitXmlWriter}, {@code report.ArtifactIndex}), and so is the event file
 * they read, but a report without its summary is not the report step, so the step does not run.
 */
final class GenerateReportStep extends UnportedStep {

    GenerateReportStep() {
        super("src/cat/launch/steps/report.py",
                "the HTML summary writer (cat.report.html, not ported); JUnit XML and the artifact index "
                        + "are ported");
    }

    @Override
    public String name() {
        return LaunchStepNames.REPORT;
    }
}
