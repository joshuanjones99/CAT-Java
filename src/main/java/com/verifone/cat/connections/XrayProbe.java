package com.verifone.cat.connections;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianUnavailable;
import com.verifone.cat.xray.XrayClient;

/**
 * The Xray row of the Connections step: signs in to Xray and to Jira with the stored credentials, and
 * names the account. A port of the Python CAT's {@code cat.connections._probe_xray}, which calls
 * {@code cat.xray.client.whoami}.
 *
 * <p>Both halves must work for a run to be reported, so both are tried and the first failure is the
 * answer. A signed-in answer is kept for {@link #TTL}, so the watch's rounds do not spend Atlassian's
 * request budget; a red answer is not kept, and is asked again every round, since the usual cause (not
 * signed in yet) is fixed from inside CAT.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the real sign-in is {@link #forCredentialsFile}; tests pass a fake
 * sign-in, so nothing here reaches Atlassian in a test.
 */
public final class XrayProbe implements Function<Target, ProbeResult> {

    /** Two HTTPS round trips to Atlassian, not one TCP connect: its own ceiling (the Python's 15 s). */
    public static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    /** How long a signed-in answer is trusted before Atlassian is asked again (the Python's 300 s). */
    public static final Duration TTL = Duration.ofSeconds(300);

    /** The per-request timeout of the real sign-in. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<String> signIn;
    private String signedInAs;
    private long signedInAtNanos;

    /**
     * @param signIn signs in to both halves and returns the Jira display name; throws
     *               {@link AtlassianUnavailable} with the reason when it cannot
     */
    public XrayProbe(Supplier<String> signIn) {
        this.signIn = signIn;
    }

    /** The real sign-in, with the credentials read from {@code file} (the Python's {@code whoami}). */
    public static XrayProbe forCredentialsFile(Path file) {
        return new XrayProbe(() -> whoami(file));
    }

    /**
     * Xray's {@code /authenticate} with the client pair, then Jira's {@code /myself} with the token.
     *
     * @throws AtlassianUnavailable naming the step that failed, never a credential
     */
    static String whoami(Path file) {
        AtlassianClient atlassian = AtlassianClient.forCredentialsFile(file, REQUEST_TIMEOUT);
        atlassian.xrayAuthenticate();
        return new XrayClient(atlassian, Duration.ZERO, null).myself().displayName();
    }

    /** The row's answer: {@code up} with {@code signed in as <name>}, or {@code down} with the reason. */
    @Override
    public synchronized ProbeResult apply(Target target) {
        if (signedInAs != null && System.nanoTime() - signedInAtNanos < TTL.toNanos()) {
            return new ProbeResult(target.name(), ProbeState.UP, "signed in as " + signedInAs, Target.Kind.XRAY);
        }
        try {
            String name = signIn.get();
            signedInAs = name;
            signedInAtNanos = System.nanoTime();
            return new ProbeResult(target.name(), ProbeState.UP, "signed in as " + name, Target.Kind.XRAY);
        } catch (AtlassianUnavailable e) {
            signedInAs = null;
            return new ProbeResult(target.name(), ProbeState.DOWN, e.getMessage(), Target.Kind.XRAY);
        }
    }
}
