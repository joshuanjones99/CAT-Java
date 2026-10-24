package com.verifone.cat.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimEntry;

/**
 * Each standard check, run against a fake workstation under {@code @TempDir}. Nothing here reads
 * {@code C:\CICD}, the real home directory, the registry or a real credential: every root is a
 * temporary folder and every command is a recorded fake.
 */
class SetupItemsTest {

    @TempDir
    Path root;

    private SetupFixture fx;

    private SetupFixture fixture() throws Exception {
        if (fx == null) {
            fx = new SetupFixture(root);
        }
        return fx;
    }

    private static Result detect(SetupItem item, SetupEnv env) {
        Result result = item.detect(env);
        assertEquals(item.name(), result.check(), "a result is keyed by its item's name");
        return result;
    }

    // ---- Java runtime ---------------------------------------------------------------------

    @Test
    void javaRuntimePassesOnTheJava17JdkUnderTheToolsRoot() throws Exception {
        SetupFixture f = fixture();
        f.write(f.tools.resolve("Java17").resolve("jdk-17.0.5+8").resolve("bin").resolve("javaw.exe"), "");
        f.answer = new SetupEnv.CommandResult(0, "", "openjdk version \"17.0.5\" 2022-10-18\n");

        Result result = detect(SetupItems.standard().get(0), f.env);

        assertEquals(Outcome.PASS, result.outcome(), result.detail());
        assertEquals(1, f.commands.size());
        assertTrue(f.commands.get(0).get(0).endsWith("java.exe"), f.commands.get(0).toString());
        assertEquals("-version", f.commands.get(0).get(1));
    }

    @Test
    void javaRuntimeFailsOnAnotherMajorVersion() throws Exception {
        SetupFixture f = fixture();
        f.write(f.tools.resolve("Java17").resolve("bin").resolve("javaw.exe"), "");
        f.answer = new SetupEnv.CommandResult(0, "", "openjdk version \"11.0.2\" 2019-01-15\n");

        Result result = SetupItems.javaRuntime().detect(f.env);

        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("Java 11"), result.detail());
    }

    @Test
    void javaRuntimeNamesTheMissingJdk() throws Exception {
        Result result = SetupItems.javaRuntime().detect(fixture().env);

        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().startsWith("no JDK with bin"), result.detail());
        assertTrue(f().commands.isEmpty(), "no JDK means nothing is run");
    }

    private SetupFixture f() throws Exception {
        return fixture();
    }

    @Test
    void javaMajorReadsBothVersionForms() {
        assertEquals(17, SetupItems.javaMajor("openjdk version \"17.0.5\" 2022-10-18"));
        assertEquals(8, SetupItems.javaMajor("java version \"1.8.0_181\""));
        assertNull(SetupItems.javaMajor("no version here"));
    }

    @Test
    void javawFallsBackToTheHighestJdkFolder() throws Exception {
        SetupFixture f = fixture();
        f.write(f.tools.resolve("Java17").resolve("jdk-11.0.2").resolve("bin").resolve("javaw.exe"), "");
        f.write(f.tools.resolve("Java17").resolve("jdk-17.0.5+8").resolve("bin").resolve("javaw.exe"), "");

        Path javaw = SetupItems.javaw(f.env);

        assertNotNull(javaw);
        assertTrue(javaw.toString().contains("jdk-17.0.5+8"), javaw.toString());
    }

    // ---- folders --------------------------------------------------------------------------

    @Test
    void toolsRootPassesWhenItIsADirectoryAndFailsWhenNot() throws Exception {
        SetupFixture f = fixture();
        assertEquals(Outcome.PASS, SetupItems.toolsRoot().detect(f.env).outcome());

        SetupEnv missing = f.withVars(Map.of("CAT_TOOLS_ROOT", f.root.resolve("nowhere").toString()));
        assertEquals(Outcome.FAIL, SetupItems.toolsRoot().detect(missing).outcome());
    }

    @Test
    void petroappsCheckoutIsOptionalAndNamesThePathItLookedAt() throws Exception {
        SetupFixture f = fixture();
        SetupItem corpus = SetupItems.corpusRoot();
        assertFalse(corpus.required());
        assertEquals(Outcome.PASS, corpus.detect(f.env).outcome());

        Result gone = corpus.detect(f.withVars(Map.of("PETROAPPS_ROOT", f.root.resolve("gone").toString())));
        assertEquals(Outcome.FAIL, gone.outcome());
        assertTrue(gone.detail().contains("gone"), gone.detail());
    }

    @Test
    void resultsFolderMayBeCreatedButItsParentMustBeWritable() throws Exception {
        SetupFixture f = fixture();
        SetupItem results = SetupItems.artifactsWritable();

        Result absent = results.detect(f.withVars(Map.of("CAT_RESULTS_DIR", f.root.resolve("new").resolve("deep").toString())));
        assertEquals(Outcome.PASS, absent.outcome());
        assertTrue(absent.detail().contains("will be created"), absent.detail());
        assertEquals(Outcome.PASS, results.detect(f.env).outcome());
    }

    @Test
    void rigsDirFailsNamingPetrotestRigsDirWhenItIsMissing() throws Exception {
        SetupFixture f = fixture();
        assertEquals(Outcome.PASS, SetupItems.rigsDir().detect(f.env).outcome());

        Result result = SetupItems.rigsDir().detect(f.withVars(Map.of("PETROTEST_RIGS_DIR", f.root.resolve("none").toString())));
        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("PETROTEST_RIGS_DIR"), result.detail());
    }

    // ---- the build and the browser --------------------------------------------------------

    @Test
    void catJavaBuildNeedsTheJarAndTheLibFolder() throws Exception {
        SetupFixture f = fixture();
        SetupItem build = SetupItems.catJavaBuild();
        assertEquals(Outcome.FAIL, build.detect(f.env).outcome());

        f.write(f.repo.resolve("target").resolve("cat-java.jar"), "");
        assertEquals(Outcome.FAIL, build.detect(f.env).outcome(), "a jar with no lib is not a build");

        f.dir(f.repo.resolve("target").resolve("lib"));
        assertEquals(Outcome.PASS, build.detect(f.env).outcome());
    }

    @Test
    void playwrightChromiumIsOptionalAndReadsTheBrowsersPath() throws Exception {
        SetupFixture f = fixture();
        SetupItem chromium = SetupItems.playwrightInstalled();
        assertFalse(chromium.required(), "only --fake-pos needs it");
        assertEquals(Outcome.FAIL, chromium.detect(f.env).outcome());

        f.dir(f.localAppData.resolve("ms-playwright").resolve("chromium-1148"));
        assertEquals(Outcome.PASS, chromium.detect(f.env).outcome());

        Path elsewhere = f.dir(f.root.resolve("browsers").resolve("chromium-1148"));
        Result moved = chromium.detect(f.withVars(Map.of("PLAYWRIGHT_BROWSERS_PATH", elsewhere.getParent().toString())));
        assertEquals(Outcome.PASS, moved.outcome(), moved.detail());
    }

    // ---- credentials ----------------------------------------------------------------------

    @Test
    void sshKeyDefaultsToTheToolsRootAndAcceptsOpenSshPem() throws Exception {
        SetupFixture f = fixture();
        SetupItem key = SetupItems.sshKey();
        assertEquals(Outcome.FAIL, key.detect(f.env).outcome());

        f.write(f.tools.resolve(SetupItems.SSH_KEY_FILE), "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRETBODY\n");
        Result result = key.detect(f.env);
        assertEquals(Outcome.PASS, result.outcome(), result.detail());
        assertFalse(result.detail().contains("SECRETBODY"));
    }

    @Test
    void sshKeyInPuttyFormatIsNamedAndItsContentsAreNot() throws Exception {
        SetupFixture f = fixture();
        Path ppk = f.write(f.root.resolve("key.ppk"), "PuTTY-User-Key-File-3: ssh-rsa\nSECRETBODY\n");

        Result result = SetupItems.sshKey().detect(f.withVars(Map.of("PETROTEST_COMMANDER_SSH_KEY", ppk.toString())));

        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("PuTTY .ppk"), result.detail());
        assertFalse(result.detail().contains("SECRETBODY"));
    }

    @Test
    void cashierPasswordReportsThatItIsSetWithoutItsValue() throws Exception {
        SetupFixture f = fixture();
        SetupItem cashier = SetupItems.cashierPassword();

        assertEquals(Outcome.FAIL, cashier.detect(f.env).outcome());
        Result set = cashier.detect(f.withVars(Map.of("PETROTEST_CASHIER_PASSWORD", "hunter2")));
        assertEquals(Outcome.PASS, set.outcome());
        assertFalse(set.detail().contains("hunter2"), set.detail());
    }

    @Test
    void javaAccessBridgeIsTheSwitchInTheUsersPropertiesFile() throws Exception {
        SetupFixture f = fixture();
        SetupItem bridge = SetupItems.javaAccessBridge();
        assertEquals(Outcome.FAIL, bridge.detect(f.env).outcome());

        f.write(f.home.resolve(".accessibility.properties"), "#assistive_technologies=old\n");
        assertEquals(Outcome.FAIL, bridge.detect(f.env).outcome(), "a commented-out line is not the switch");

        f.write(f.home.resolve(".accessibility.properties"),
                "#Thu\nassistive_technologies=com.sun.java.accessibility.AccessBridge\n");
        assertEquals(Outcome.PASS, bridge.detect(f.env).outcome());
    }

    @Test
    void jiraCredentialsNameTheFourKeysAndNeverShowAValue() throws Exception {
        SetupFixture f = fixture();
        SetupItem jira = SetupItems.jiraApiToken();
        assertTrue(jira.credential());
        assertEquals(Outcome.FAIL, jira.detect(f.env).outcome());

        f.write(f.home.resolve("jira_api_token.properties"),
                "email=me@example.test\njira.token=TOKENVALUE\nxray.client.id=ID\n");
        Result partial = jira.detect(f.env);
        assertEquals(Outcome.FAIL, partial.outcome());
        assertTrue(partial.detail().contains("xray.client.secret"), partial.detail());
        assertFalse(partial.detail().contains("TOKENVALUE"));

        f.write(f.home.resolve("jira_api_token.properties"),
                "email=me@example.test\njira.token=TOKENVALUE\nxray.client.id=ID\nxray.client.secret=SECRETVALUE\n");
        Result full = jira.detect(f.env);
        assertEquals(Outcome.PASS, full.outcome(), full.detail());
        assertFalse(full.detail().contains("TOKENVALUE"));
        assertFalse(full.detail().contains("SECRETVALUE"));
    }

    @Test
    void verifoneLoginNeedsTheEnvironmentAndNoInlinePetroappsPassword() throws Exception {
        SetupFixture f = fixture();
        SetupItem login = SetupItems.verifoneLogin();
        assertTrue(login.credential());
        assertEquals(Outcome.FAIL, login.detect(f.env).outcome());

        Map<String, String> signedIn = new HashMap<>();
        signedIn.put("ARTIFACTORY_USER", "jdoe");
        signedIn.put("ARTIFACTORY_PASSWORD", "PASSWORDVALUE");
        SetupEnv set = f.withVars(signedIn);
        assertEquals(Outcome.PASS, login.detect(set).outcome());

        f.write(f.petroapps.resolve(".git").resolve("config"),
                "[remote \"origin\"]\n\turl = https://jdoe:LEAKEDPASS@bitbucket.example.test/petroapps.git\n");
        Result inline = login.detect(set);
        assertEquals(Outcome.FAIL, inline.outcome());
        assertTrue(inline.detail().contains("inline"), inline.detail());
        assertFalse(inline.detail().contains("LEAKEDPASS"), inline.detail());
    }

    // ---- the simulators -------------------------------------------------------------------

    @Test
    void simulatorsStagedNamesMissingFoldersAndPassesWhenEveryRowIsThere() throws Exception {
        SetupFixture f = fixture();
        SetupItem staged = SetupItems.simulatorsStaged();
        assertTrue(staged.manual());
        assertFalse(staged.required());

        Result none = staged.detect(f.env);
        assertEquals(Outcome.FAIL, none.outcome());
        assertTrue(none.detail().contains(" not staged"), none.detail());

        for (String folder : Registry.all().stream().map(SimEntry::cwd)
                .filter(c -> c != null && !c.isBlank()).distinct().toList()) {
            f.dir(f.tools.resolve(folder));
        }
        assertEquals(Outcome.PASS, staged.detect(f.env).outcome());
    }

    // ---- the whole list -------------------------------------------------------------------

    @Test
    void theStandardListHoldsThirteenUniqueItemsWithTheTwoSignIns() {
        List<SetupItem> items = SetupItems.standard();

        assertEquals(13, items.size());
        assertEquals(items.size(), items.stream().map(SetupItem::name).distinct().count());
        assertEquals(List.of(SetupItems.JIRA_API_TOKEN, SetupItems.VERIFONE_LOGIN),
                items.stream().filter(SetupItem::credential).map(SetupItem::name).toList());
        assertTrue(items.stream().allMatch(item -> !item.fixHint().isBlank()), "every item says how to fix it");
    }
}
