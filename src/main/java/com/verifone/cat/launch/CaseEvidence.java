package com.verifone.cat.launch;

import com.verifone.cat.pos.driver.CaseArtifacts;

/**
 * A {@link RunServices.Connector} whose POS driver writes its evidence through a {@link CaseArtifacts}, which step 5
 * points at each case's folder for the case's duration: the Python's {@code pos_artifacts} fixture. A connector that
 * builds no driver, or builds one without a sink, is not one of these, and its failed validations capture nothing.
 */
interface CaseEvidence {

    /** The sink the connected POS driver writes to, or null when no driver was connected. */
    CaseArtifacts caseArtifacts();
}
