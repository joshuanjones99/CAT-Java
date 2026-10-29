package com.verifone.cat.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.gitauth.GitAuth;
import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;

/**
 * The repairs, the sign-in cards' saves and the live sign-ins: the Python's {@code test_cat_setup.py},
 * {@code test_setup_attention.py} and {@code test_gitauth.py::TestVerifoneLogin}. Every environment is a temporary
 * folder; git, DPAPI and the network are fakes, so nothing here reads or writes this account's real stores.
 */
class SetupRepairTest {

    @TempDir
    Path tmp;

    // ---- the repair pass ----------------------------------------------------------------

    @Test
    void runAndRepairAppliesOnlyFailingAutomatableItems() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        List<String> applied = new ArrayList<>();
        SetupItem auto = new SetupItem("auto", "", false, false, false, "fix", env -> Result.failed("auto", "broken"))
                .withRepair(env -> {
                    applied.add("auto");
                    return Result.passed("auto", "fixed it");
                });
        SetupItem fine = new SetupItem("fine", "", false, false, false, "", env -> Result.passed("fine", "ok"))
                .withRepair(env -> {
                    applied.add("fine");
                    return Result.passed("fine", "");
                });
        SetupItem manual = new SetupItem("manual", "", true, true, false, "install it", env -> Result.failed("manual", "no"));
        SetupAudit audit = new SetupAudit(List.of(auto, fine, manual), f.env);

        Map<String, Result> repairs = audit.runAndRepair();

        assertEquals(List.of("auto"), applied);
        assertEquals(List.of("auto"), List.copyOf(repairs.keySet()));
        assertTrue(audit.results().get("auto").wasRepair());
        assertEquals(Outcome.PASS, audit.results().get("auto").outcome());
        assertEquals(List.of("manual"), List.copyOf(audit.stillNeedsAttention().keySet()));
        assertEquals(List.of("manual"), List.copyOf(audit.missingRequired().keySet()));
    }

    @Test
    void anItemWithNoRepairIsUnrepairableWithItsFixHint() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        SetupItem none = new SetupItem("none", "", true, false, false, "do it by hand", env -> Result.failed("none", "x"));
        Result result = none.apply(f.env);
        assertEquals(Outcome.UNREPAIRABLE, result.outcome());
        assertEquals("do it by hand", result.detail());
    }

    @Test
    void theCashierDefaultIsSetInTheViewAndPersistedWithSetxOnlyWhenAsked() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        SetupItem cashier = SetupItems.cashierPassword();
        assertFalse(cashier.manual());

        Result fixed = cashier.apply(f.env);
        assertEquals(Outcome.PASS, fixed.outcome(), fixed.detail());
        assertFalse(fixed.detail().contains(SetupItems.DEFAULT_CASHIER_PASSWORD + " "));
        assertEquals(Outcome.PASS, cashier.detect(f.env).outcome());
        assertTrue(f.commands.isEmpty(), "a test environment never runs setx: " + f.commands);

        List<List<String>> ran = new ArrayList<>();
        SetupEnv persisting = new SetupEnv(Map.of(), f.home, f.repo, (argv, timeout) -> {
            ran.add(argv);
            return new SetupEnv.CommandResult(0, "", "");
        }, GitAuth.offline(), SavedLogins.envOnly(Map.of(), f.home), true);
        cashier.apply(persisting);
        assertEquals(List.of(List.of("setx", SetupItems.CASHIER_PASSWORD_ENV, SetupItems.DEFAULT_CASHIER_PASSWORD)), ran);
    }

    @Test
    void theResultsFolderIsCreated() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        assertFalse(Files.exists(f.results));
        Result fixed = SetupItems.artifactsWritable().apply(f.env);
        assertEquals(Outcome.PASS, fixed.outcome(), fixed.detail());
        assertTrue(Files.isDirectory(f.results));
        assertFalse(Files.exists(f.results.resolve(".cat_test")));
    }

    @Test
    void aSetPetroappsRootThatIsMissingIsNotOverwritten() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        SetupEnv env = f.withVars(Map.of("PETROAPPS_ROOT", tmp.resolve("nowhere").toString()));
        Result result = SetupItems.corpusRoot().apply(env);
        assertEquals(Outcome.UNREPAIRABLE, result.outcome());
        assertTrue(result.detail().contains("does not exist"), result.detail());
    }

    @Test
    void theAccessBridgeRepairRunsTheJdksOwnJabswitch() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        Path bin = f.tools.resolve("Java17").resolve("bin");
        f.write(bin.resolve("javaw.exe"), "");
        Result missing = SetupItems.javaAccessBridge().apply(f.env);
        assertEquals(Outcome.FAIL, missing.outcome());
        assertTrue(missing.detail().contains("jabswitch.exe"), missing.detail());

        f.write(bin.resolve("jabswitch.exe"), "");
        f.answer = new SetupEnv.CommandResult(0, "", "");
        Result fixed = SetupItems.javaAccessBridge().apply(f.env);
        assertEquals(Outcome.PASS, fixed.outcome(), fixed.detail());
        assertEquals(List.of(bin.resolve("jabswitch.exe").toString(), "-enable"), f.commands.get(0));
    }

    // ---- the SSH key: JSch reads a .ppk itself ---------------------------------------------

    @Test
    void aPpkJschLoadsIsAcceptedOnceTheVariableNamesIt() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        Path key = f.tools.resolve(SetupItems.SSH_KEY_FILE);
        Files.writeString(key, ppkV2(), StandardCharsets.UTF_8);
        SetupItem item = SetupItems.sshKey();

        Result before = item.detect(f.env);
        assertEquals(Outcome.FAIL, before.outcome());
        assertTrue(before.detail().contains("is not set"), before.detail());

        SetupAudit audit = new SetupAudit(List.of(item), f.env);
        audit.runAndRepair();
        assertEquals(key.toString(), f.env.var(SetupItems.SSH_KEY_ENV));
        Result after = item.detect(f.env);
        assertEquals(Outcome.PASS, after.outcome(), after.detail());
        assertTrue(after.detail().contains("JSch reads it"), after.detail());
    }

    @Test
    void aPpkJschCannotLoadIsUnrepairableAndItsContentsAreNeverShown() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        Path key = f.write(f.root.resolve("key.ppk"), "PuTTY-User-Key-File-2: ssh-rsa\nEncryption: aes256-cbc\nSECRETBODY\n");
        SetupEnv env = f.withVars(Map.of(SetupItems.SSH_KEY_ENV, key.toString()));
        Result fixed = SetupItems.sshKey().apply(env);
        assertEquals(Outcome.UNREPAIRABLE, fixed.outcome(), fixed.detail());
        assertFalse(fixed.detail().contains("SECRETBODY"));
    }

    // ---- the Atlassian card ------------------------------------------------------------------

    @Test
    void theAtlassianCardSavesToCatJavasOwnFileMergingAndKeepingOtherLines() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        SetupItem jira = SetupItems.jiraApiToken();
        assertTrue(jira.hasFields());
        assertEquals(List.of("email", "jira.token", "xray.client.id", "xray.client.secret"),
                jira.fields().stream().map(ManualField::key).toList());
        SetupAudit audit = new SetupAudit(List.of(jira), f.env);

        Result blank = audit.applyManualItem(SetupItems.JIRA_API_TOKEN, Map.of("email", "me@example.test"));
        assertEquals(Outcome.FAIL, blank.outcome());
        assertTrue(blank.detail().contains("cannot be blank"), blank.detail());
        Path own = f.home.resolve(".cat").resolve("java").resolve("jira_api_token.properties");
        assertFalse(Files.exists(own));

        // The shared file Monarch keeps is read for what is already saved, and never written.
        Path shared = f.write(f.home.resolve("jira_api_token.properties"),
                "monarch.only=keep\nemail=old@example.test\njira.token=OLDTOKEN\n");
        Files.createDirectories(own.getParent());
        Files.writeString(own, "# mine\nother=1\n");
        Result saved = audit.applyManualItem(SetupItems.JIRA_API_TOKEN, Map.of("email", "",
                "jira.token", "NEW=TOKEN", "xray.client.id", "ID", "xray.client.secret", "SECRET"));
        assertEquals(Outcome.PASS, saved.outcome(), saved.detail());
        assertFalse(saved.detail().contains("TOKEN"), saved.detail());
        String text = Files.readString(own);
        assertTrue(text.startsWith("# mine\nother=1\n"), text);
        assertTrue(text.contains("email=old@example.test\n"), text);
        assertTrue(text.contains("jira.token=NEW\\=TOKEN\n"), text);
        assertEquals("monarch.only=keep\nemail=old@example.test\njira.token=OLDTOKEN\n", Files.readString(shared));

        assertEquals(Outcome.PASS, jira.detect(f.env).outcome());
        assertEquals("NEW=TOKEN", jira.saved(f.env).get("jira.token"));
    }

    // ---- the Verifone card -------------------------------------------------------------------

    /** A reversible stand-in for DPAPI: never the plain bytes on disk, and decryptable here. */
    private static byte[] scramble(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ 0x5A);
        }
        return out;
    }

    private SetupEnv verifoneEnv(SetupFixture f, GitAuth git) {
        SavedLogins logins = new SavedLogins(Map.of(), f.home,
                List.of(f.home.resolve(".cat").resolve("java").resolve(SavedLogins.ARTIFACTORY_FILE)),
                SetupRepairTest::scramble, SetupRepairTest::scramble);
        return new SetupEnv(f.vars, f.home, f.repo, (argv, timeout) -> new SetupEnv.CommandResult(1, "", ""),
                git, logins, false);
    }

    /** A git over petroapps' origin, recording each call; {@code stored} answers {@code credential fill}. */
    private static GitAuth fakeGit(String origin, boolean[] stored, List<List<String>> calls) {
        return new GitAuth((repo, args, stdin, config) -> {
            calls.add(args);
            if (args.get(0).equals("remote") && args.get(1).equals("get-url")) {
                return new GitAuth.Done(0, origin + "\n", "");
            }
            if (args.equals(List.of("credential", "fill"))) {
                return stored[0] ? new GitAuth.Done(0, "protocol=https\nhost=x\npassword=p\n", "")
                        : new GitAuth.Done(128, "", "");
            }
            if (args.equals(List.of("credential", "approve"))) {
                stored[0] = true;
            }
            return new GitAuth.Done(0, "", "");
        });
    }

    @Test
    void theVerifoneDetectNamesBothMissingLoginsAndNeverThePassword() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        Files.createDirectories(f.petroapps.resolve(".git"));
        SetupEnv env = verifoneEnv(f, fakeGit("https://someone:old-secret@bitbucket.example.invalid/x.git",
                new boolean[] {false}, new ArrayList<>()));
        Result result = SetupItems.verifoneLogin().detect(env);
        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("Artifactory"), result.detail());
        assertTrue(result.detail().contains("origin URL carries a password inline"), result.detail());
        assertFalse(result.detail().contains("old-secret"), result.detail());
    }

    @Test
    void savingTheVerifoneLoginSignsInToBoth() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        Files.createDirectories(f.petroapps.resolve(".git"));
        boolean[] stored = {false};
        List<List<String>> calls = new ArrayList<>();
        SetupEnv env = verifoneEnv(f, fakeGit("https://bitbucket.example.invalid/x.git", stored, calls));
        SetupItem login = SetupItems.verifoneLogin();
        assertEquals(Outcome.FAIL, login.detect(env).outcome());

        Result saved = new SetupAudit(List.of(login), env).applyManualItem(SetupItems.VERIFONE_LOGIN,
                Map.of("username", "joe@verifone.com", "password", "new-secret"));

        assertEquals(Outcome.PASS, saved.outcome(), saved.detail());
        assertEquals("saved for Artifactory and Bitbucket", saved.detail());
        Path file = f.home.resolve(".cat").resolve("java").resolve(SavedLogins.ARTIFACTORY_FILE);
        String text = Files.readString(file);
        assertFalse(text.contains("new-secret"), text);
        assertTrue(text.contains("\"Username\":\"joe\""), text);
        assertTrue(text.contains(HexFormat.of().formatHex(scramble("new-secret".getBytes(StandardCharsets.UTF_16LE)))));
        assertEquals("joe", env.logins().artifactory().orElseThrow().user());
        assertEquals(Outcome.PASS, login.detect(env).outcome());
        assertEquals(Map.of("username", "joe", "password", "new-secret"), login.saved(env));
    }

    @Test
    void aBlankVerifoneFieldSavesNothingAndAGitFailureIsARow() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        Files.createDirectories(f.petroapps.resolve(".git"));
        List<List<String>> calls = new ArrayList<>();
        SetupEnv env = verifoneEnv(f, fakeGit("https://bitbucket.example.invalid/x.git", new boolean[] {false}, calls));
        Result blank = SetupItems.saveVerifone(env, Map.of("username", "joe", "password", ""));
        assertEquals(Outcome.FAIL, blank.outcome());
        assertFalse(Files.exists(env.logins().ownArtifactoryFile()));
        assertTrue(calls.isEmpty(), calls.toString());

        GitAuth noGit = new GitAuth((repo, args, stdin, config) -> {
            throw new GitAuth.GitError("git remote could not run: IOException: not found", null);
        });
        Result failed = SetupItems.saveVerifone(verifoneEnv(f, noGit), Map.of("username", "joe", "password", "s3"));
        assertEquals(Outcome.FAIL, failed.outcome());
        assertTrue(failed.detail().startsWith("Artifactory saved; Bitbucket not"), failed.detail());
        assertFalse(failed.detail().contains("s3"), failed.detail());
    }

    @Test
    void aTestEnvironmentCannotSaveALogin() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        Result result = SetupItems.saveVerifone(f.env, Map.of("username", "joe", "password", "p"));
        assertEquals(Outcome.ERROR, result.outcome());
        assertFalse(Files.exists(f.home.resolve(".cat")));
    }

    // ---- the live sign-ins ---------------------------------------------------------------------

    @Test
    void aRejectedLiveSignInPutsTheSavedLoginBackInNeedsAttention() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        f.write(f.home.resolve("jira_api_token.properties"),
                "email=a\njira.token=b\nxray.client.id=c\nxray.client.secret=d\n");
        LiveSignIns live = new LiveSignIns() {
            @Override
            public Result jira(SetupEnv env) {
                return Result.failed(SetupItems.JIRA_API_TOKEN, "saved, but Atlassian rejected it");
            }

            @Override
            public Result verifone(SetupEnv env) {
                return Result.passed(SetupItems.VERIFONE_LOGIN, "signed in");
            }
        };
        SetupAudit audit = new SetupAudit(SetupItems.standard(live), f.env);
        audit.run();
        assertTrue(audit.results().get(SetupItems.JIRA_API_TOKEN).ok());

        audit.checkSavedLogins(null);

        assertEquals(Set.of(SetupItems.JIRA_API_TOKEN), audit.rejected());
        assertTrue(audit.missingCredentials().containsKey(SetupItems.JIRA_API_TOKEN));
        // The Verifone login was not saved, so it was not signed in with.
        assertFalse(audit.signedIn().contains(SetupItems.VERIFONE_LOGIN));

        audit.recordLive(SetupItems.JIRA_API_TOKEN, Result.passed(SetupItems.JIRA_API_TOKEN, "ok"));
        assertTrue(audit.rejected().isEmpty());
        assertEquals(Set.of(SetupItems.JIRA_API_TOKEN), audit.signedIn());
    }

    @Test
    void aLiveCheckThatThrowsIsAFailedSignIn() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        SetupItem item = new SetupItem("x", "", true, false, true, "", env -> Result.passed("x", ""))
                .withLiveCheck(env -> {
                    throw new IllegalStateException("no route");
                });
        Result result = new SetupAudit(List.of(item), f.env).liveCheck(item);
        assertEquals(Outcome.FAIL, result.outcome());
        assertEquals("sign-in check failed: no route", result.detail());
    }

    @Test
    void theVerifoneLiveWordsAreThePythons() {
        assertEquals("signed in to Artifactory and Bitbucket", LiveSignIns.verifoneResult(List.of()).detail());
        assertEquals("saved, but signing in failed — Bitbucket: denied",
                LiveSignIns.verifoneResult(List.of("Bitbucket: denied")).detail());
        Result rejected = LiveSignIns.jiraRejected("jira_api_token",
                new com.verifone.cat.atlassian.AtlassianUnavailable("GET /rest/api/3/myself -> HTTP 401: x"));
        assertTrue(rejected.detail().contains("rejected the email and API token"), rejected.detail());
    }

    // ---- cat-java setup ------------------------------------------------------------------------

    @Test
    void theSetupCommandFixesReportsAndExitsByTheRequiredItems() throws Exception {
        SetupFixture f = new SetupFixture(tmp);
        SetupItem auto = new SetupItem("auto", "", false, false, false, "", env -> Result.failed("auto", "x"))
                .withRepair(env -> Result.passed("auto", "made it"));
        SetupItem login = new SetupItem("login", "", true, false, true, "", env -> Result.failed("login", "none saved"));
        SetupItem needed = new SetupItem("needed", "", true, true, false, "", env -> Result.failed("needed", "install"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int code = new SetupCommand(() -> new SetupAudit(List.of(auto, login, needed), f.env), out).run(new String[0]);

        String text = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(1, code);
        assertTrue(text.contains("fixed  auto: made it"), text);
        assertTrue(text.contains("1 item(s) need your attention:\n  - needed: install"), text);
        assertTrue(text.contains("open the Setup page.\n  - login: none saved"), text);

        int ok = new SetupCommand(() -> new SetupAudit(List.of(auto, login), f.env), out).run(new String[0]);
        assertEquals(0, ok, "a missing sign-in is not a setup failure");
        assertEquals(2, new SetupCommand(() -> new SetupAudit(List.of(), f.env), out).run(new String[] {"--x"}));
    }

    // ---- a PPK v2 key, generated here -----------------------------------------------------------

    /** An unencrypted {@code PuTTY-User-Key-File-2: ssh-rsa} key with its MAC, as puttygen writes it. */
    static String ppkV2() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
        ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
        DataOutputStream pub = new DataOutputStream(publicBytes);
        string(pub, "ssh-rsa".getBytes(StandardCharsets.US_ASCII));
        mpint(pub, key.getPublicExponent());
        mpint(pub, key.getModulus());
        ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
        DataOutputStream prv = new DataOutputStream(privateBytes);
        mpint(prv, key.getPrivateExponent());
        mpint(prv, key.getPrimeP());
        mpint(prv, key.getPrimeQ());
        mpint(prv, key.getCrtCoefficient());
        ByteArrayOutputStream macBytes = new ByteArrayOutputStream();
        DataOutputStream mac = new DataOutputStream(macBytes);
        string(mac, "ssh-rsa".getBytes(StandardCharsets.US_ASCII));
        string(mac, "none".getBytes(StandardCharsets.US_ASCII));
        string(mac, "test".getBytes(StandardCharsets.US_ASCII));
        string(mac, publicBytes.toByteArray());
        string(mac, privateBytes.toByteArray());
        byte[] macKey = MessageDigest.getInstance("SHA-1")
                .digest("putty-private-key-file-mac-key".getBytes(StandardCharsets.US_ASCII));
        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(macKey, "HmacSHA1"));
        StringBuilder out = new StringBuilder("PuTTY-User-Key-File-2: ssh-rsa\r\nEncryption: none\r\nComment: test\r\n");
        block(out, "Public-Lines", publicBytes.toByteArray());
        block(out, "Private-Lines", privateBytes.toByteArray());
        out.append("Private-MAC: ").append(HexFormat.of().formatHex(hmac.doFinal(macBytes.toByteArray()))).append("\r\n");
        return out.toString();
    }

    private static void block(StringBuilder out, String name, byte[] data) {
        String text = Base64.getEncoder().encodeToString(data);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 64) {
            lines.add(text.substring(i, Math.min(text.length(), i + 64)));
        }
        out.append(name).append(": ").append(lines.size()).append("\r\n");
        lines.forEach(line -> out.append(line).append("\r\n"));
    }

    private static void string(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static void mpint(DataOutputStream out, BigInteger value) throws IOException {
        string(out, value.toByteArray());
    }
}
