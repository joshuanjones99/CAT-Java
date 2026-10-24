package com.verifone.cat.fep;

import java.util.List;

/**
 * One row of {@code secondary_feps.yaml}: a front-end processor the Commander's EPS runs beside its primary one.
 * A port of {@code petrotest.secondary_fep.SecondaryFep}. Unknown keys are a load error ({@link SecondaryFeps}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param name           the FEPTable's {@code <FEPName>}: {@code fep=<name>} in vfepcfg/ufepcfg; case matters
 * @param label          the Config Client's menu label
 * @param kind           what it is for, in a word or two
 * @param enabledBy      {@code fepcfg} (ufepcfg turns it on and off) or {@code license} (Commercial Fleet)
 * @param files          petroapps files under {@code AutomationTests/configurations}, sent as one import
 * @param localOnly      a FEP with no external host at all
 * @param simulator      the simulator registry row that answers as its host, or ""
 * @param pointsAt       with a simulator: the host the FEP is pointed at ({@code {controller_host}} filled in
 *                       at use); a URL for a REST FEP, {@code host:port} for a socket FEP
 * @param listensOn      with a simulator: {@code <file>:<key>} under the tools root naming the address the
 *                       simulator binds and certifies; must be the rig's controller
 * @param fepsimInstance the FEPSIM instance that answers as this FEP's host
 * @param cloudProvider  Commander Connect's provider name, for a Commercial Fleet FEP ("" when none)
 * @param cloudPayload   the provider's JSON body, compact, with {@code {controller_host}} unfilled
 * @param trustTls       {@code host:port} whose TLS certificate the Commander's Java must trust, or ""
 * @param note           what to say when it is enabled but no simulator answers
 */
public record SecondaryFep(String name, String label, String kind, String enabledBy, List<String> files,
        boolean localOnly, String simulator, String pointsAt, String listensOn, String fepsimInstance,
        String cloudProvider, String cloudPayload, String trustTls, String note) {

    /** The {@code enabled_by} value of a Commercial Fleet FEP: only the license turns it on. */
    public static final String LICENSE = "license";

    /** The {@code enabled_by} value of an ordinary FEP: {@code ufepcfg}'s {@code <enabled>} turns it on. */
    public static final String FEPCFG = "fepcfg";

    public SecondaryFep {
        files = List.copyOf(files);
        kind = kind == null ? "" : kind;
        enabledBy = enabledBy == null || enabledBy.isEmpty() ? FEPCFG : enabledBy;
        simulator = simulator == null ? "" : simulator;
        pointsAt = pointsAt == null ? "" : pointsAt;
        listensOn = listensOn == null ? "" : listensOn;
        fepsimInstance = fepsimInstance == null ? "" : fepsimInstance;
        cloudProvider = cloudProvider == null ? "" : cloudProvider;
        cloudPayload = cloudPayload == null ? "" : cloudPayload;
        trustTls = trustTls == null ? "" : trustTls;
        note = note == null ? "" : note;
    }

    /** Only the Commercial Fleet license turns it on; {@code ufepcfg} cannot. */
    public boolean licenseEnabled() {
        return LICENSE.equals(enabledBy);
    }

    /** Its config command without the {@code v}/{@code u} prefix: {@code fepcfg&fep=<name>}. */
    public String fepcfg() {
        return "fepcfg&fep=" + name;
    }

    /** A few words on what answers it, for the Config tab's dropdown. */
    public String status() {
        String words;
        if (localOnly) {
            words = "no host needed";
        } else if (!simulator.isEmpty()) {
            words = simulator;
        } else {
            words = "no simulator";
        }
        return licenseEnabled() ? words + ", needs license" : words;
    }
}
