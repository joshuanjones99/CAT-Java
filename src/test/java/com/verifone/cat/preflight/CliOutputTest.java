package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CliOutputTest {

    private static final Charset CODE_PAGE_437 = Charset.forName("IBM437");

    @Test
    void anEmDashThatTheConsoleCannotEncodeBecomesAHyphenNotReplacementText() {
        // IBM437 (the US console code page) has no em dash, which printed as a replacement mark.
        assertFalse(CODE_PAGE_437.newEncoder().canEncode("—"));
        assertEquals("rig lab is not testable - failed verification twice",
                CliOutput.fit("rig lab is not testable — failed verification twice", CODE_PAGE_437));
    }

    @Test
    void textTheCharsetCanEncodeIsUnchanged() {
        assertEquals("PETROCORE-36847 é ok", CliOutput.fit("PETROCORE-36847 é ok", StandardCharsets.UTF_8));
        assertEquals("plain ascii", CliOutput.fit("plain ascii", StandardCharsets.US_ASCII));
    }

    @Test
    void otherUnencodableCharactersBecomeAQuestionMarkAndEnDashesBecomeHyphens() {
        assertEquals("a?b - c", CliOutput.fit("a☃b – c", StandardCharsets.US_ASCII));
    }

    @Test
    void theConsoleCharsetIsNeverMissing() {
        assertTrue(CliOutput.consoleCharset() != null);
    }
}
