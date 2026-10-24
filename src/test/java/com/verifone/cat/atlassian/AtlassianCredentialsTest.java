package com.verifone.cat.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.atlassian.AtlassianCredentials.Jira;
import com.verifone.cat.atlassian.AtlassianCredentials.Xray;

/**
 * The credentials file, read as the Python CAT reads it. Port of the Python CAT's
 * {@code TestReadProperties}, {@code TestLoadCredentials} and the xray parts of
 * {@code test_cat_atlassian.py}. Every file here is a temporary one with fake values.
 */
class AtlassianCredentialsTest {

    private static final String FAKE_TOKEN = "not-a-real-token";

    @TempDir
    Path dir;

    @Test
    void readsTheTwoKeysMonarchWritesAndSkipsComments() {
        Path file = FakeAtlassian.write(dir, "jira.properties",
                "# written by Get-JiraApiToken\n! bang comment\n\nemail : tester@verifone.com\njira.token=" + FAKE_TOKEN + "\n");
        assertEquals(new Jira("tester@verifone.com", FAKE_TOKEN), AtlassianCredentials.loadJira(file));
    }

    @Test
    void aBackslashEscapedEqualsSignInTheTokenIsUnescaped() {
        // Java's properties writer escapes an embedded '=' as '\='. An unescaped read would hand
        // the site a token with a stray backslash in it.
        Path file = FakeAtlassian.write(dir, "jira.properties", "email=a@b.com\njira.token=abcDEF\\=1B44364A\n");
        assertEquals("abcDEF=1B44364A", AtlassianCredentials.loadJira(file).token());
    }

    @Test
    void aMissingFileNamesTheSetupStepAndIsNotAFileNotFound() {
        Path absent = dir.resolve("absent.properties");
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> AtlassianCredentials.loadJira(absent));
        assertTrue(thrown.getMessage().contains(AtlassianCredentials.SETUP_STEP));
        assertTrue(thrown.getMessage().contains(absent.toString()));
    }

    @Test
    void aFileMissingTheTokenNamesTheKeyAndTheSetupStep() {
        Path file = FakeAtlassian.write(dir, "jira.properties", "email=tester@verifone.com\n");
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> AtlassianCredentials.loadJira(file));
        assertTrue(thrown.getMessage().contains("jira.token"));
        assertTrue(thrown.getMessage().contains(AtlassianCredentials.SETUP_STEP));
    }

    @Test
    void theXrayPairIsReadBesideTheJiraPair() {
        Path file = FakeAtlassian.write(dir, "jira.properties",
                "email=a@b.com\njira.token=tok\nxray.client.id=xid\nxray.client.secret=s\\=cret\n");
        assertEquals(new Jira("a@b.com", "tok"), AtlassianCredentials.loadJira(file));
        assertEquals(new Xray("xid", "s=cret"), AtlassianCredentials.loadXray(file));
    }

    @Test
    void aFileWithoutTheXrayPairNamesTheMissingKeys() {
        Path file = FakeAtlassian.write(dir, "jira.properties", "email=a@b.com\njira.token=tok\n");
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> AtlassianCredentials.loadXray(file));
        assertTrue(thrown.getMessage().contains("xray.client.id"));
    }

    @Test
    void aMalformedPropertiesFileIsUnavailableNotARawException() {
        Path file = FakeAtlassian.write(dir, "jira.properties", "email=a@b.com\njira.token=\\uZZZZ\n");
        assertThrows(AtlassianUnavailable.class, () -> AtlassianCredentials.loadJira(file));
    }

    @Test
    void theRecordsNeverPrintTheirSecrets() {
        String jira = new Jira("tester@verifone.com", FAKE_TOKEN).toString();
        String xray = new Xray("xid", "fake-secret").toString();
        assertFalse(jira.contains(FAKE_TOKEN) || jira.contains("tester"));
        assertFalse(xray.contains("fake-secret") || xray.contains("xid"));
    }

    @Test
    void theDefaultFileIsTheOneMonarchReads() {
        assertEquals("jira_api_token.properties", AtlassianCredentials.defaultPath().getFileName().toString());
    }
}
