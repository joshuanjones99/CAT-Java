package com.verifone.cat.gitauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Python's {@code tests/unit/test_gitauth.py}. git runs for real against a fixture checkout whose
 * {@code origin} carries an inline password, with the system and global config switched off and
 * {@code credential.helper} pointed at a throwaway {@code store} file, so this account's Credential Manager is never
 * read or written and nothing reaches the network ({@code check} is never called with a real remote).
 */
class GitAuthTest {

    static final String ORIGIN = "https://someone:old-secret@bitbucket.example.invalid:8443/scm/petapps/petroapps.git";

    @TempDir
    Path tmp;

    Path repo;
    GitAuth git;
    List<String> helper;

    @BeforeEach
    void checkout() throws IOException {
        Path store = tmp.resolve("credentials");
        Path config = tmp.resolve("gitconfig");
        Files.writeString(config, "[credential]\n\thelper = store --file " + store.toString().replace('\\', '/') + "\n",
                StandardCharsets.UTF_8);
        git = GitAuth.withEnv(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", config.toString(),
                "HOME", tmp.toString()));
        // Belt and braces: reset the helper list, then the throwaway store only.
        helper = List.of("credential.helper=", "credential.helper=store --file " + store.toString().replace('\\', '/'));
        repo = tmp.resolve("petroapps");
        Files.createDirectories(repo);
        run("init", "-q");
        run("remote", "add", "origin", ORIGIN);
    }

    private GitAuth.Done run(String... args) {
        return GitAuth.runGit(repo, List.of(args), null, List.of(),
                Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", tmp.resolve("gitconfig").toString()));
    }

    @Test
    void parseDropsTheInlineCredentialAndKeepsThePort() {
        GitAuth.Remote remote = GitAuth.Remote.parse(ORIGIN);
        assertTrue(remote.embedsPassword());
        assertEquals("https://bitbucket.example.invalid:8443/scm/petapps/petroapps.git", remote.url());
        assertEquals("bitbucket.example.invalid:8443", remote.host());
        assertFalse(GitAuth.Remote.parse(remote.url()).embedsPassword());
        assertTrue(GitAuth.Remote.parse("https://user-token@bitbucket.example.invalid/x.git").embedsUser());
        assertFalse(GitAuth.Remote.parse("https://user-token@bitbucket.example.invalid/x.git").embedsPassword());
        assertFalse(GitAuth.Remote.parse("ssh://git@bitbucket.example.invalid/x.git").isHttp());
    }

    @Test
    void storeStripsOriginAndSavesTheLogin() {
        assertFalse(git.stored(repo, helper));

        assertEquals("", git.store(repo, "joe", "new-secret", helper));

        assertFalse(git.origin(repo).orElseThrow().embedsPassword());
        assertFalse(run("remote", "get-url", "origin").stdout().contains("old-secret"));
        assertTrue(git.stored(repo, helper));
    }

    @Test
    void storeAlsoStripsAUsernameThatCarriesNoPassword() {
        run("remote", "set-url", "origin", "https://user-token@bitbucket.example.invalid/x.git");
        assertTrue(git.origin(repo).orElseThrow().embedsUser());

        assertEquals("", git.store(repo, "joe", "new-secret", helper));
        assertFalse(git.origin(repo).orElseThrow().embedsUser());
    }

    @Test
    void noCheckoutHasNoOrigin() {
        assertTrue(git.origin(tmp.resolve("nothing")).isEmpty());
        assertFalse(git.stored(tmp.resolve("nothing"), helper));
        assertEquals(tmp.resolve("nothing") + " has no origin", git.store(tmp.resolve("nothing"), "a", "b", helper));
    }

    @Test
    void noGitIsOneErrorType() {
        GitAuth broken = new GitAuth((r, args, stdin, config) -> {
            throw new GitAuth.GitError("git " + args.get(0) + " could not run: IOException: not found", null);
        });
        assertThrows(GitAuth.GitError.class, () -> broken.origin(repo));
    }

    @Test
    void thePasswordGoesOnlyToStdin() {
        List<List<String>> argvs = new ArrayList<>();
        List<String> stdins = new ArrayList<>();
        GitAuth recording = new GitAuth((r, args, stdin, config) -> {
            argvs.add(args);
            stdins.add(stdin);
            if (args.get(0).equals("remote") && args.get(1).equals("get-url")) {
                return new GitAuth.Done(0, ORIGIN + "\n", "");
            }
            return new GitAuth.Done(0, "", "");
        });
        assertEquals("", recording.store(repo, "joe", "s3cret", List.of()));
        assertTrue(argvs.stream().flatMap(List::stream).noneMatch(a -> a.contains("s3cret")), argvs.toString());
        assertTrue(stdins.stream().anyMatch(s -> s != null && s.contains("password=s3cret\n")));
        assertEquals(List.of("remote", "set-url", "origin",
                "https://bitbucket.example.invalid:8443/scm/petapps/petroapps.git"), argvs.get(1));
    }

    @Test
    void checkSaysWhyNot() {
        GitAuth failing = new GitAuth((r, args, stdin, config) -> new GitAuth.Done(128, "", "fatal: Authentication failed\n"));
        assertEquals("fatal: Authentication failed", failing.check(repo));
        GitAuth silent = new GitAuth((r, args, stdin, config) -> new GitAuth.Done(2, "", ""));
        assertEquals("exit 2", silent.check(repo));
    }
}
