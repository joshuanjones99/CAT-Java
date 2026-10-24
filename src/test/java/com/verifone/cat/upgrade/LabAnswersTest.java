package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The lab's installer answers and challenge answers: the values PATS's files carry, as CLAUDE.md rule 6 records. */
class LabAnswersTest {

    @Test
    void theInstallerAnswersNameTheDistributionAndTheBreadcrumbTheCodeLooksFor() {
        String text = LabAnswers.psiAnswers("buypass");

        assertTrue(text.contains("dist=BUYPASS\n"), text);
        assertTrue(text.contains("psi.completion.filename=psi_complete\n"), text);
        assertTrue(text.contains("psi.securitydata.filename=psi_message.txt\n"), text);
        assertTrue(text.contains("<message type=\"menu\" sysid=\"12\" reply=\"Commander\" />"), text);
        assertTrue(text.contains("<message type=\"menu\" sysid=\"7\" reply=\"US/Eastern\" />"), text);
    }

    @Test
    void theChallengeAnswersNameAllFourQuestionsForTheCommanderUser() {
        String xml = LabAnswers.CHALLENGE_ANSWERS_XML;

        assertTrue(xml.contains("userName=\"manager\""), xml);
        assertEquals(4, xml.split("<question ", -1).length - 1);
        assertTrue(xml.contains("questionID=\"OLDEST_NEPHEW_FIRST_NAME\""), xml);
        assertTrue(xml.contains("questionID=\"ELEMENTARY_SCHOOL_NAME\""), xml);
    }
}
