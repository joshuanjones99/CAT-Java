package com.verifone.cat.setup;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianPlanSource;
import com.verifone.cat.atlassian.AtlassianUnavailable;
import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryErrors;
import com.verifone.cat.gitauth.GitAuth;
import com.verifone.cat.preflight.Result;

/**
 * The two sign-ins' {@code check_live()}: what the Setup page runs after a save, and what the start of the web UI runs
 * over the saved logins. Network calls, so an interface: {@link #REAL} reaches Atlassian, Artifactory and Bitbucket;
 * tests pass a fake.
 *
 * <p>Ported from the Python CAT's {@code JiraApiToken.check_live} and {@code VerifoneLogin.check_live}, word for word
 * in what an operator reads.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no live sign-in has been made from CAT-Java.
 */
public interface LiveSignIns {

    /** Monarch's nightly builds: the folder the Verifone sign-in lists (the Python's {@code MASTER_BUILDS_REPO}). */
    String MASTER_BUILDS_REPO = "RMS_Nightly_CLW/Petro/master";

    /** How long one sign-in request may take, as the Python's {@code timeout=30.0}. */
    Duration TIMEOUT = Duration.ofSeconds(30);

    /** Signs in to Jira ({@code GET /myself}), then reads the Automated-Features page. */
    Result jira(SetupEnv env);

    /** Lists the master builds on Artifactory and {@code ls-remote}s petroapps, as the run will. */
    Result verifone(SetupEnv env);

    /** The real sign-ins. */
    LiveSignIns REAL = new LiveSignIns() {
        @Override
        public Result jira(SetupEnv env) {
            String name = SetupItems.JIRA_API_TOKEN;
            AtlassianClient client = AtlassianClient.forCredentialsFile(env.logins().jiraFile(), TIMEOUT);
            try {
                client.getJson("/rest/api/3/myself", Map.of());
            } catch (AtlassianUnavailable e) {
                return jiraRejected(name, e);
            }
            try {
                client.getJson("/wiki/api/v2/pages/" + AtlassianPlanSource.AUTOMATED_FEATURES_PAGE, Map.of());
            } catch (AtlassianUnavailable e) {
                return Result.failed(name, "saved, but reading the Automated-Features page failed: " + e.getMessage());
            }
            return Result.passed(name, "email and token read the Automated-Features page");
        }

        @Override
        public Result verifone(SetupEnv env) {
            List<String> problems = new ArrayList<>();
            try {
                new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                        env.logins().artifactoryCredentials(), TIMEOUT).listFolder(MASTER_BUILDS_REPO);
            } catch (RuntimeException e) {
                problems.add(ArtifactoryErrors.describe(e, MASTER_BUILDS_REPO));
            }
            problems.addAll(bitbucket(env));
            return verifoneResult(problems);
        }
    };

    /** The Bitbucket half of the Verifone sign-in: {@code ls-remote} petroapps, when it has an origin. */
    static List<String> bitbucket(SetupEnv env) {
        Path petroapps = SetupItems.petroappsRoot(env);
        try {
            Optional<GitAuth.Remote> origin = env.git().origin(petroapps);
            if (origin.isPresent()) {
                String why = env.git().check(petroapps);
                if (!why.isEmpty()) {
                    return List.of("Bitbucket: " + why);
                }
            }
            return List.of();
        } catch (GitAuth.GitError e) {
            return List.of("Bitbucket: " + e.getMessage());
        }
    }

    /** The Verifone sign-in's result from its problems, in the Python's words. */
    static Result verifoneResult(List<String> problems) {
        if (!problems.isEmpty()) {
            return Result.failed(SetupItems.VERIFONE_LOGIN,
                    "saved, but signing in failed — " + String.join("; ", problems));
        }
        return Result.passed(SetupItems.VERIFONE_LOGIN, "signed in to Artifactory and Bitbucket");
    }

    /** The Jira sign-in's failure: a 401 is a rejected token, said as the Python says it. */
    static Result jiraRejected(String name, AtlassianUnavailable e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("-> HTTP 401")) {
            return Result.failed(name, "saved, but Atlassian rejected the email and API token (Jira 'GET /myself' "
                    + "-> HTTP 401). The token may be incomplete (a real one ends in '=' and 8 "
                    + "hex characters), revoked or expired: create a new one at "
                    + "id.atlassian.com/manage-profile/security/api-tokens and Reset this card.");
        }
        return Result.failed(name, "saved, but signing in to Jira failed: " + message);
    }
}
