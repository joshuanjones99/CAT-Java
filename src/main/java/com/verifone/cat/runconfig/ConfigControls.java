package com.verifone.cat.runconfig;

import java.util.List;
import java.util.Map;

/**
 * The Config tab's sections after Run scope, as data for the web layer: Test plan branch, Commander Connect,
 * Execution, and what is going on in the background (Refresh from Confluence, the Jira case-list fetch). From
 * {@link ConfigForm#controls()}; the Downloads section is the web layer's own shared panel.
 *
 * @param testPlanBranch the petroapps branch, as typed
 * @param ccOnboard      the Onboard switch
 * @param ccLab          the lab; one of {@link #ccLabs}
 * @param ccLabs         the labs offered
 * @param ccEmail        the account email
 * @param ccPassword     the password, saved in the file as the Python saves it (demo accounts); masked on the page
 * @param ccServiceId    the Commander's service ID
 * @param execMode       {@code dev} or {@code BDD}
 * @param execOptions    {@code (label, value)} pairs, in the Python's order and words
 * @param ifsfMode       "" or an IFSF flag
 * @param ifsfOptions    {@code (label, value)} pairs
 * @param coverage       the JaCoCo switch
 * @param xray           Report to Xray
 * @param refreshing     Refresh from Confluence is reading the page
 * @param fetchingCases  the ticked plans whose case list is being fetched from Jira now
 * @param help           each control's help text, by the Python's control id
 */
public record ConfigControls(
        String testPlanBranch,
        boolean ccOnboard,
        String ccLab,
        List<String> ccLabs,
        String ccEmail,
        String ccPassword,
        String ccServiceId,
        String execMode,
        List<Map.Entry<String, String>> execOptions,
        String ifsfMode,
        List<Map.Entry<String, String>> ifsfOptions,
        boolean coverage,
        boolean xray,
        boolean refreshing,
        List<String> fetchingCases,
        Map<String, String> help) {
}
