package com.verifone.cat.gitauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.verifone.cat.collect.CollectException;
import com.verifone.cat.collect.GitUtil;

/**
 * Bitbucket credentials for the petroapps checkout, through git's own credential store: the Python CAT's
 * {@code petrotest.gitauth}.
 *
 * <p>CAT fetches petroapps with prompts off ({@code GIT_TERMINAL_PROMPT=0}, {@code GCM_INTERACTIVE=never}), so a
 * stored credential is a precondition. {@link #store} is the fix the Setup page's Verifone card applies with what
 * the operator types: it rewrites {@code origin} without an inline credential, then hands the login to
 * {@code git credential approve}, which saves it in whatever helper git is configured with (Git Credential
 * Manager on the controllers). A password is only ever written to git's stdin, never to an argument list or a log.
 *
 * <p>Every git call goes through a {@link Runner}; {@link #system()} is the real {@code git}, and tests pass a fake,
 * so no test reaches git's credential helper or the network. {@link #check} ({@code ls-remote}) is the only call
 * that reaches Bitbucket, and only a real session's Setup page makes it.
 *
 * <p>Deviation from the Python: none in behaviour. {@link #store} writes outside {@code ~/.cat/java/} (petroapps'
 * {@code .git/config} and git's credential helper), exactly as the Python does and only on the operator's Save.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): run only against a fake runner.
 */
public final class GitAuth {

    /** How long one git call may take, as the Python's {@code timeout=60}. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** One finished git call. */
    public record Done(int exitCode, String stdout, String stderr) {}

    /**
     * Runs {@code git [-c <config>]... -C <repo> <args>} with prompts off, {@code stdin} written to it.
     * Throws {@link GitError} when git cannot run at all (not installed, not launchable, timed out).
     */
    @FunctionalInterface
    public interface Runner {
        Done run(Path repo, List<String> args, String stdin, List<String> config);
    }

    /**
     * {@code origin} of a checkout, split into what git's credential protocol needs.
     *
     * @param url the URL with any inline {@code user:password@} removed
     * @param embedsPassword the configured URL carries a password inline
     * @param embedsUser it carries a username inline, with or without a password: git then asks the helper for that
     *     user only, so a login saved under the operator's own name is never offered
     */
    public record Remote(String url, String protocol, String host, boolean embedsPassword, boolean embedsUser) {

        /** The Python's {@code Remote.parse}: {@code urlsplit}, the host with its port, the userinfo dropped. */
        public static Remote parse(String url) {
            String text = url == null ? "" : url.strip();
            URI uri;
            try {
                uri = new URI(text);
            } catch (URISyntaxException e) {
                return new Remote(text, "", "", false, false);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (uri.getPort() > 0) {
                host = host + ":" + uri.getPort();
            }
            String userInfo = uri.getRawUserInfo();
            boolean user = userInfo != null;
            boolean password = userInfo != null && userInfo.contains(":");
            StringBuilder clean = new StringBuilder();
            if (!scheme.isEmpty()) {
                clean.append(scheme).append(':');
            }
            if (uri.getRawAuthority() != null) {
                clean.append("//").append(host);
            }
            if (uri.getRawPath() != null) {
                clean.append(uri.getRawPath());
            }
            if (uri.getRawQuery() != null) {
                clean.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                clean.append('#').append(uri.getRawFragment());
            }
            return new Remote(clean.toString(), scheme, host, password, user);
        }

        public boolean isHttp() {
            return "http".equals(protocol) || "https".equals(protocol);
        }
    }

    /** git could not be run at all: not installed, not launchable, or timed out. One type for every caller. */
    public static final class GitError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public GitError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Runner runner;

    public GitAuth(Runner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** The real {@code git}, with the no-prompt environment {@code collect} fetches with. */
    public static GitAuth system() {
        return new GitAuth((repo, args, stdin, config) -> runGit(repo, args, stdin, config, Map.of()));
    }

    /**
     * The real {@code git} with {@code env} added to the no-prompt environment. Tests pass
     * {@code GIT_CONFIG_NOSYSTEM}/{@code GIT_CONFIG_GLOBAL} here, as the Python's tests do, so git never reads this
     * account's own configuration or credential helper.
     */
    public static GitAuth withEnv(Map<String, String> env) {
        Map<String, String> extra = Map.copyOf(env);
        return new GitAuth((repo, args, stdin, config) -> runGit(repo, args, stdin, config, extra));
    }

    /** {@code repo}'s {@code origin}, or empty when there is no checkout or no {@code origin}. */
    public Optional<Remote> origin(Path repo) {
        if (!Files.exists(repo.resolve(".git"))) {
            return Optional.empty();
        }
        Done done = git(repo, List.of("remote", "get-url", "origin"), null, List.of());
        if (done.exitCode() != 0 || done.stdout().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Remote.parse(done.stdout().strip()));
    }

    /** Does git's credential helper already hold a login for {@code origin}'s host? Never prompts. */
    public boolean stored(Path repo, List<String> config) {
        Optional<Remote> remote = origin(repo);
        if (remote.isEmpty() || !remote.get().isHttp()) {
            return false;
        }
        Done done = git(repo, List.of("credential", "fill"), query(remote.get(), "", ""), config);
        return done.exitCode() == 0 && ("\n" + done.stdout()).contains("\npassword=");
    }

    public boolean stored(Path repo) {
        return stored(repo, List.of());
    }

    /**
     * Saves the login for {@code origin}'s host, dropping any inline credential: "" or why not.
     *
     * @param config extra {@code git -c} settings, for tests to point {@code credential.helper} elsewhere
     */
    public String store(Path repo, String username, String password, List<String> config) {
        Optional<Remote> found = origin(repo);
        if (found.isEmpty()) {
            return repo + " has no origin";
        }
        Remote remote = found.get();
        if (!remote.isHttp()) {
            return "origin is " + remote.protocol() + ", not https: nothing to store";
        }
        if (remote.embedsPassword() || remote.embedsUser()) {
            Done done = git(repo, List.of("remote", "set-url", "origin", remote.url()), null, List.of());
            if (done.exitCode() != 0) {
                return "could not remove the inline credential from origin: " + done.stderr().strip();
            }
        }
        Done done = git(repo, List.of("credential", "approve"), query(remote, username, password), config);
        if (done.exitCode() != 0) {
            return "git credential approve failed: " + done.stderr().strip();
        }
        return "";
    }

    public String store(Path repo, String username, String password) {
        return store(repo, username, password, List.of());
    }

    /** {@code git ls-remote --heads origin <branch>} with prompts off: "" when it answers, else why not. */
    public String check(Path repo, String branch, List<String> config) {
        Done done = git(repo, List.of("ls-remote", "--heads", "origin", branch), null, config);
        if (done.exitCode() == 0) {
            return "";
        }
        String why = done.stderr().strip();
        return why.isEmpty() ? "exit " + done.exitCode() : why;
    }

    public String check(Path repo) {
        return check(repo, "master", List.of());
    }

    /** The credential protocol's query: protocol, host, then the user and password when given, and a blank line. */
    static String query(Remote remote, String username, String password) {
        List<String> lines = new ArrayList<>();
        lines.add("protocol=" + remote.protocol());
        lines.add("host=" + remote.host());
        if (username != null && !username.isEmpty()) {
            lines.add("username=" + username);
        }
        if (password != null && !password.isEmpty()) {
            lines.add("password=" + password);
        }
        return String.join("\n", lines) + "\n\n";
    }

    private Done git(Path repo, List<String> args, String stdin, List<String> config) {
        return runner.run(repo, args, stdin, config == null ? List.of() : config);
    }

    /** The real runner: {@link GitUtil#run} with the no-prompt environment and each {@code -c} before the command. */
    static Done runGit(Path repo, List<String> args, String stdin, List<String> config, Map<String, String> extra) {
        List<String> argv = new ArrayList<>();
        for (String setting : config) {
            argv.add("-c");
            argv.add(setting);
        }
        argv.addAll(args);
        Map<String, String> env = new HashMap<>(GitUtil.NO_PROMPT);
        env.putAll(extra);
        try {
            GitUtil.Output out = GitUtil.run(repo, argv, env,
                    stdin == null ? null : stdin.getBytes(StandardCharsets.UTF_8), TIMEOUT);
            return new Done(out.exitCode(), new String(out.stdout(), StandardCharsets.UTF_8),
                    new String(out.stderr(), StandardCharsets.UTF_8));
        } catch (CollectException e) {
            throw new GitError("git " + args.get(0) + " could not run: " + e.getMessage(), e);
        }
    }
}
