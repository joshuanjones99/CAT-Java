package com.verifone.cat.provision;

/** Provision reports of a given shape, for tests outside this package (recovery rung 5). */
public final class ProvisionReports {

    private ProvisionReports() {}

    /** One register, provisioned; {@code restartedPos} when its checklist restarted the POS app. */
    public static ProvisionReport ok(boolean restartedPos) {
        ProvisionReport report = new ProvisionReport("lab", true);
        TargetReport target = new TargetReport("topaz101", TargetRole.REGISTER);
        target.decision(TargetReport.Decision.UP_TO_DATE);
        target.checklist(new StepResult("ensure_pos_shim_current", StepStatus.DONE, "", restartedPos));
        report.add(target);
        return report;
    }

    /** One register whose push failed. */
    public static ProvisionReport failed() {
        ProvisionReport report = new ProvisionReport("lab", true);
        TargetReport target = new TargetReport("topaz101", TargetRole.REGISTER);
        target.error("push failed: connection refused");
        report.add(target);
        return report;
    }
}
