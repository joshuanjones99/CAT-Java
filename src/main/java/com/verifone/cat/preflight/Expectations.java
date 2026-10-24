package com.verifone.cat.preflight;

import java.util.List;

/**
 * What this rig and suite are supposed to be, from the rig file. Ported from
 * {@code petrotest.preflight.context.Expectations}: the right-hand sides of the checks
 * that compare against the rig, kept on the context because {@code config_folder} is
 * suite-specific.
 */
public record Expectations(String buildVersion, String configFolder, String cashier, String cashierPassword,
        List<Integer> pumps) {

    public Expectations {
        buildVersion = buildVersion == null ? "" : buildVersion;
        configFolder = configFolder == null ? "" : configFolder;
        cashier = cashier == null ? "" : cashier;
        cashierPassword = cashierPassword == null ? "" : cashierPassword;
        pumps = pumps == null ? List.of() : List.copyOf(pumps);
    }

    /** Nothing expected: the answer when the rig names nothing. */
    public Expectations() {
        this("", "", "", "", List.of());
    }
}
