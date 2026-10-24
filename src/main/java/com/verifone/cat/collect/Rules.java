package com.verifone.cat.collect;

import java.util.List;

/**
 * {@code CICDScript.bat}'s per-case copy rules ({@link #RULES}, lines 474-690) and its shared assets
 * ({@link #SHARED_ASSETS}, lines 400-441), in the script's order.
 *
 * <p>Inventory only: nothing here copies a PATS file. Each rule is what PATS would copy, reported so an operator can
 * see what is missing. The Python CAT's {@code petrotest.collect.RULES} and {@code SHARED_ASSETS}.
 */
public final class Rules {

    static final List<String> DIST_FIRST = List.of("dist", "case");
    static final List<String> CASE_FIRST = List.of("case", "dist");
    static final List<String> CASE_ONLY = List.of("case");

    /** One shared asset: the petroapps folder, whether PATS copies it recursively (`xcopy /s`), and its destination. */
    public record SharedAsset(String source, boolean recursive, String dest) {}

    public static final List<Rule> RULES = List.of(
            dist("TestCase", "*-TestCase.*", "TestCases", "{id}-TestCase.xml"),
            dist("Receipt", "*-Receipt.txt", "ReferenceFiles/Receipts", "{id}-Receipt.txt"),
            dist("Tlog", "*-Tlog.xml", "ReferenceFiles/Tlog", "{id}-Tlog.xml"),
            dist("REPORT-RULE", "*REPORT-RULE.txt", "RuleFiles/Reports", "{id}REPORT-RULE.txt"),
            dist("Report", "*-Report.xml", "ReferenceFiles/Reports", "{id}-Report.xml"),
            dist("Rule", "*-Rule.txt", "RuleFiles/Receipts", "{id}-Rule.txt"),
            caseFirst("Config-Reference", "*-Config-Reference.xml", "ReferenceFiles/Configuration",
                    "{id}-Config-Reference.xml"),
            caseFirst("Config-Input", "*-Config-Input.xml", "ConfigurationInput", "{id}-Config-Input.xml"),
            caseFirst("httpSimPayLoad", "*-httpSimPayLoad.txt", "Configuration/EndPoints/HTTPSIM/payload",
                    "{id}-httpSimPayLoad.txt"),
            // PATS copies the control file to the *payload* folder too (line 600).
            caseFirst("httpSimControl", "*httpSimControl.prop", "Configuration/EndPoints/HTTPSIM/payload",
                    "{id}-httpSimControl.prop.txt"),
            caseFirst("Response", "*Response.xml", "Configuration/EndPoints/HTTPSIM/__files", "{id}-Response.xml"),
            wholeDir("Packets/BUYPASS", "Packets/BUYPASS", "ReferenceFiles/Packets/BUYPASS"),
            wholeDir("Packets/PCATS01", "Packets/PCATS01", "ReferenceFiles/Packets/PCATS01"),
            wholeDir("Packets/PCATS02", "Packets/PCATS02", "ReferenceFiles/Packets/PCATS02"),
            plain("FDCRequest", "*FDCRequest.xml", "ReferenceFiles/Packets/FDC-CLIENT", true),
            plain("FDCResponse", "*FDCResponse.xml", "ReferenceFiles/Packets/FDC-CLIENT", true),
            plain("FDCEvent", "*FDCEvent.xml", "ReferenceFiles/Packets/FDC-CLIENT", true),
            plain("SystemEvent", "*SystemEvent.xml", "ReferenceFiles/Packets/SYSTEM-UPDATES", true),
            plain("Reference.json", "*Reference.json", "ReferenceFiles/JsonPayloads", true),
            plain("EPSRequest", "*EPSRequest.xml", "ReferenceFiles/Packets/EPS-CLIENT", true),
            plain("EPSResponse", "*EPSResponse.xml", "ReferenceFiles/Packets/EPS-CLIENT", true),
            plain("EPSPrinterData", "*EPSPrinterData.txt", "ReferenceFiles/EPSClientPrinterData", true),
            plain("EPSPrinterRule", "*-EPSPrinterRule.txt", "RuleFiles/EPSClientPrinterData", false),
            // Lines 663-669: checked under Packets\IFSF-AGENT, copied from packets\ /s.
            new Rule("IFSFRequest", "*IFSFRequest.xml", "ReferenceFiles/Packets/IFSF-AGENT", CASE_ONLY, null,
                    "packets", "Packets/IFSF-AGENT", true, false),
            new Rule("IFSFResponse", "*IFSFResponse.xml", "ReferenceFiles/Packets/IFSF-AGENT", CASE_ONLY, null,
                    "packets", "Packets/IFSF-AGENT", true, false),
            plain("Vista", "*-Vista-*.xml", "ReferenceFiles/Packets/VISTA/VISTA_CLIENT", true),
            new Rule("HTTP payload", "*-payload.*", "Payloads/HTTP", CASE_ONLY, null, "Payloads/HTTP", null, false,
                    false),
            plain("refPayload", "*-refPayload.*", "ReferenceFiles/Payloads", false),
            new Rule("Payloads rule", "*-rule.txt", "RuleFiles/Payloads", CASE_ONLY, null, "Payloads", null, false,
                    false));

    /** {@code CICDScript.bat} lines 400-441, copied once per run. */
    public static final List<SharedAsset> SHARED_ASSETS = List.of(
            new SharedAsset("AutomationTests/configurations/Collis_Images", true, "terminalConnector Collis_Images"),
            new SharedAsset("AutomationTests/rulefiles/tlog", false, "RuleFiles/Tlog"),
            new SharedAsset("AutomationTests/rulefiles/receipts", false, "RuleFiles/Receipts"),
            new SharedAsset("AutomationTests/rulefiles/reports", false, "RuleFiles/Reports"),
            new SharedAsset("isdApps/xml/schemas/src/schemas", true, "ReferenceFiles/schemas"),
            new SharedAsset("AutomationTests/configurations", true, "Configuration"),
            new SharedAsset("AutomationTests/scannerdata", false, "scannerdata"),
            new SharedAsset("AutomationTests/rulefiles/configuration", false, "RuleFiles/Configuration"),
            new SharedAsset("AutomationTests/rulefiles/packets", true, "RuleFiles/Packets"),
            new SharedAsset("AutomationTests/rulefiles/jsonPayloads", true, "RuleFiles/JsonPayloads"),
            new SharedAsset("AutomationTests/rulefiles/Payloads", false, "RuleFiles/Payloads"),
            new SharedAsset("AutomationTests/rulefiles/vista", false, "RuleFiles/Vista"),
            new SharedAsset("AutomationTests/setup", false, "setup"),
            new SharedAsset("AutomationTests/teardown", false, "teardown"),
            new SharedAsset("AutomationTests/referencefiles/IFSF-AGENT", true,
                    "ReferenceFiles/Packets/IFSF-AGENT"));

    private Rules() {}

    private static Rule dist(String name, String pattern, String dest, String missing) {
        return new Rule(name, pattern, dest, DIST_FIRST, missing, "", null, false, false);
    }

    private static Rule caseFirst(String name, String pattern, String dest, String missing) {
        return new Rule(name, pattern, dest, CASE_FIRST, missing, "", null, false, false);
    }

    private static Rule plain(String name, String pattern, String dest, boolean recursive) {
        return new Rule(name, pattern, dest, CASE_ONLY, null, "", null, recursive, false);
    }

    /** {@code if exist <subdir>\ then xcopy <subdir>\ /s}: any file below the folder counts. */
    private static Rule wholeDir(String name, String subdir, String dest) {
        return new Rule(name, "*", dest, CASE_ONLY, null, subdir, null, true, true);
    }
}
