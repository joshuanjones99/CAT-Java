package com.verifone.cat.upgrade;

/**
 * The lab's answers to the PSI installer's dialogs, and to the Commander's challenge questions.
 *
 * <p>A port of the constants in the Python {@code petrotest.upgrade.new_install.psi_answers} and
 * {@code petrotest.upgrade.psi_init.CHALLENGE_ANSWERS}. They are hardcoded by an explicit rig-owner
 * decision (CAT's CLAUDE.md, hard rule 6, second carve-out, 2026-09-18, HANDOFF.md §1.63): they are
 * lab-only values, identical on every rig this runs against. No other credential may be added here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class LabAnswers {

    /** The Commander's account, as the challenge questions name it (the CGILink user, CLAUDE.md rule 6). */
    static final String COMMANDER_USER = "manager";

    /** The installer's completion breadcrumb prefix; the device's name follows it. */
    static final String BREADCRUMB_PREFIX = "psi_complete";

    /** The file the installer writes the Commander's generated password into. */
    static final String SECURITY_DATA_FILE = "psi_message.txt";

    /** The installer's config file name, written into the build directory on every run. */
    public static final String PSI_CONFIG_FILE = "psi_automn_config.prop";

    /**
     * {@code uchallengequestions}' payload: the four question ids and the lab's answers, PATS's
     * {@code challengequestion.xml} (CLAUDE.md rule 6, second carve-out).
     */
    public static final String CHALLENGE_ANSWERS_XML =
            "<userprofile:challengeAnswers xmlns:userprofile=\"urn:vfi-petro:userprofile.2017-03-20\" "
                    + "userName=\"" + COMMANDER_USER + "\">"
                    + "<question questionID=\"OLDEST_NEPHEW_FIRST_NAME\"><answer>xzxzxzxzxz</answer></question>"
                    + "<question questionID=\"STREET_NAME_IN_THIRD_GRADE\"><answer>xxxsxs</answer></question>"
                    + "<question questionID=\"FIRST_COMPANY\"><answer>vvcvcvcvcvcv</answer></question>"
                    + "<question questionID=\"ELEMENTARY_SCHOOL_NAME\"><answer>dsdsdsdsd</answer></question>"
                    + "</userprofile:challengeAnswers>";

    private LabAnswers() {}

    /**
     * CAT's {@code psi_automn_config.prop}: the installer's dialog answers, by form and sysid. The lab's
     * site name, US/Eastern, the English locale, "reformat the HDD for Commander applications" and its
     * confirmation, as PATS's file gives them (CLAUDE.md rule 6, first carve-out).
     *
     * @param dist the build's distribution name; written upper-cased as {@code dist=}
     */
    public static String psiAnswers(String dist) {
        return "dist=" + dist.toUpperCase(java.util.Locale.ROOT) + "\n"
                + "formname.security.information="
                + form(message("question", 0, "VERIFONE PQA LAB"),
                        message("question", 2, "none"),
                        message("menu", 6, "United States (English)"),
                        message("menu", 7, "US/Eastern")) + "\n"
                + "formname.configuration.information=" + form(message("question", 15, "NO")) + "\n"
                + "formname.hdd.information="
                + form(message("menu", 10, "[FCC] - Reformat HDD and use for Commander applications"),
                        message("question", 11, "12345678"),
                        message("menu", 12, "Commander")) + "\n"
                + "psi.completion.filename=" + BREADCRUMB_PREFIX + "\n"
                + "psi.securitydata.filename=" + SECURITY_DATA_FILE + "\n";
    }

    private static String message(String kind, int sysid, String reply) {
        return "<message type=\"" + kind + "\" sysid=\"" + sysid + "\" reply=\"" + reply + "\" />";
    }

    /** Concatenates already-rendered dialog messages, as the installer's form reads them. */
    private static String form(String... messages) {
        return String.join("", messages);
    }
}
