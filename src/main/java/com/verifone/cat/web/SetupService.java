package com.verifone.cat.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.preflight.Result;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItem;
import com.verifone.cat.setup.SetupItems;
import com.verifone.cat.setup.ToolStaging;
import com.verifone.cat.shell.Shell;

/**
 * The Setup page's audit and what CAT does with it on start: the Python CAT's {@code CatService.setup_check},
 * {@code _setup_pass}, {@code save_setup_fields}, {@code repair_setup_item} and {@code stage_tools}.
 *
 * <p>A check runs the audit and its repair pass ({@link SetupAudit#runAndRepair()}) on a worker thread, then signs in
 * with the saved logins ({@link SetupAudit#checkSavedLogins}) when sign-ins are required, keeps the setup status the
 * sidebar marker shows, and, once the Verifone login has signed in, downloads the missing Java 17 sets and simulators
 * ({@link ToolStaging}) once a session by itself. Each outcome is a toast in the Python's words.
 *
 * <p>Opening the page probes nothing: a check runs on start ({@code serve} without {@code --no-setup}) or on Check
 * again. Tests inject the audit factory, the staging and an inline executor, so nothing touches the machine.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class SetupService {

    private static final Logger LOG = LoggerFactory.getLogger("cat.web");

    /** The setup rows a Java 17 / simulator download can turn green. */
    static final List<String> TOOL_ITEMS = List.of(SetupItems.JAVA_RUNTIME, SetupItems.SIMULATORS_STAGED);

    /** A consistent copy of the audit for one render. */
    record Snapshot(List<SetupItem> items, Map<String, Result> results, boolean checked, boolean checking,
            Set<String> rejected, Set<String> signedIn, String status, String tools, SetupEnv env) {}

    private final Supplier<SetupAudit> audits;
    private final Function<SetupEnv, ToolStaging> stagings;
    private final Shell shell;
    private final Executor executor;
    private final Executor stagingExecutor;
    private final boolean requireCredentials;

    private SetupAudit audit;
    private boolean checking;
    private boolean toolsStageTried;

    /**
     * @param audits    makes a fresh audit for each check
     * @param stagings  the Java 17 / simulator staging for an audit's environment
     * @param executor  where a check runs; staging runs on {@code stagingExecutor}, so a long download never holds
     *                  up Check again
     * @param requireCredentials sign in with the saved logins on each check, and gate runs on them
     */
    SetupService(Supplier<SetupAudit> audits, Function<SetupEnv, ToolStaging> stagings, Shell shell, Executor executor,
            Executor stagingExecutor, boolean requireCredentials) {
        this.audits = audits;
        this.stagings = stagings;
        this.shell = shell;
        this.executor = executor;
        this.stagingExecutor = stagingExecutor;
        this.requireCredentials = requireCredentials;
    }

    /** A service over one audit, run inline: no staging, no sign-in gate. For tests. */
    SetupService(SetupAudit audit) {
        this(() -> audit, env -> ToolStaging.NONE, new Shell(), Runnable::run, Runnable::run, false);
    }

    /** The service over the real workstation. Constructing it reads nothing. */
    static SetupService standard(Shell shell, boolean requireCredentials) {
        return new SetupService(() -> SetupAudit.standard(SetupEnv.system()),
                env -> ToolStaging.standard(env.vars(), env.logins()), shell, daemon("cat-web-setup"),
                daemon("cat-web-tools"), requireCredentials);
    }

    static SetupService standard() {
        return standard(new Shell(), false);
    }

    private static Executor daemon(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    boolean requireCredentials() {
        return requireCredentials;
    }

    Shell shell() {
        return shell;
    }

    /** Check again, and the check on start: never two at once. {@code notifyIfOk} toasts "Setup complete.". */
    void check(boolean notifyIfOk) {
        synchronized (this) {
            if (checking) {
                return;
            }
            checking = true;
        }
        executor.execute(() -> pass(notifyIfOk));
    }

    /** Check again, as the page's button calls it. */
    void check() {
        check(true);
    }

    private void pass(boolean notifyIfOk) {
        shell.loading("setup", "checking setup");
        try {
            SetupAudit fresh;
            try {
                fresh = audits.get();
                fresh.runAndRepair();
            } catch (RuntimeException e) {
                LOG.error("setup check failed: {}", e.toString());
                shell.setupStatus("Setup check failed: " + e.getMessage());
                shell.notify("Setup check failed: " + e.getMessage(), "error", 15);
                return;
            }
            SetupAudit previous;
            synchronized (this) {
                previous = audit;
            }
            if (requireCredentials) {
                shell.loading("setup", "checking saved sign-ins");
                fresh.checkSavedLogins(null);
            } else if (previous != null && !previous.rejected().isEmpty()) {
                fresh.checkSavedLogins(previous.rejected());
            }
            synchronized (this) {
                audit = fresh;
            }
            refreshStatus();
            if (fresh.signedIn().contains(SetupItems.VERIFONE_LOGIN)) {
                stageTools(false);
            }
            Map<String, Result> remaining = fresh.stillNeedsAttention();
            if (notifyIfOk) {
                if (remaining.isEmpty()) {
                    shell.notify("Setup complete.", "positive", 5);
                }
                return;
            }
            if (!fresh.rejected().isEmpty()) {
                StringBuilder details = new StringBuilder();
                for (String name : fresh.rejected()) {
                    details.append('\n').append(name).append(": ").append(fresh.results().get(name).detail());
                }
                shell.notify("A saved login was rejected; re-enter it on the Setup page." + details, "error", 30);
                return;
            }
            if (remaining.isEmpty()) {
                return;
            }
            if (requireCredentials && !fresh.missingCredentials().isEmpty()) {
                shell.notify("Sign in before running: open the Setup page to enter "
                        + String.join(", ", fresh.missingCredentials().keySet()), "warning", 15);
                return;
            }
            if (fresh.missingRequired().isEmpty()) {
                return;
            }
            StringBuilder detail = new StringBuilder();
            remaining.forEach((name, result) -> detail.append('\n').append(name).append(": ").append(result.detail()));
            shell.notify("Setup needs attention:" + detail, "warning", 10);
        } finally {
            shell.doneLoading("setup");
            synchronized (this) {
                checking = false;
            }
        }
    }

    private void refreshStatus() {
        SetupAudit current;
        synchronized (this) {
            current = audit;
        }
        if (current == null) {
            return;
        }
        shell.setupStatus(Shell.setupStatusText(current.stillNeedsAttention().size(),
                current.missingCredentials().size(), current.rejected().size()));
    }

    /** What a card's save answers: whether it worked, and what to say. */
    record Said(boolean ok, String text) {}

    /**
     * Saves a card's fields, then signs in with them. A blank field keeps what is saved, and a credential is signed in
     * with at once; a good Verifone sign-in starts the Java 17 / simulator download.
     */
    Said saveFields(String name, Map<String, String> values) {
        SetupItem item;
        Result live;
        synchronized (this) {
            if (audit == null) {
                return new Said(false, "Setup has not been checked yet.");
            }
            item = audit.item(name);
            if (item == null || !item.hasFields()) {
                return new Said(false, "That setup item takes no typed values.");
            }
            Result result = audit.applyManualItem(name, values);
            refreshStatus();
            if (!result.ok()) {
                return new Said(false, result.detail());
            }
            if (!item.credential() || item.liveCheck() == null) {
                return new Said(true, result.detail().isEmpty() ? "Saved." : result.detail());
            }
            live = audit.liveCheck(item);
            audit.recordLive(name, live);
        }
        refreshStatus();
        if (!live.ok()) {
            return new Said(false, live.detail());
        }
        if (SetupItems.VERIFONE_LOGIN.equals(name)) {
            stageTools(true);
        }
        return new Said(true, "Signed in: these credentials are valid.");
    }

    /** Applies one automatable item again (the audit's own repair). */
    synchronized Said repair(String name) {
        if (audit == null) {
            return new Said(false, "Setup has not been checked yet.");
        }
        SetupItem item = audit.item(name);
        if (item == null || item.manual()) {
            return new Said(false, "That setup item is not one CAT repairs.");
        }
        Result result = audit.applyItem(name);
        refreshStatus();
        return new Said(result.ok(), result.detail().isEmpty() ? (result.ok() ? "Repaired." : "Not repaired.")
                : result.detail());
    }

    /** Downloads the missing Java 17 sets and simulators, once a session by itself (again on a new sign-in). */
    void stageTools(boolean retry) {
        SetupEnv env;
        synchronized (this) {
            if (shell.toolsStaging() || toolsStageTried && !retry || audit == null) {
                return;
            }
            toolsStageTried = true;
            env = audit.env();
        }
        stagingExecutor.execute(() -> stage(stagings.apply(env)));
    }

    private void stage(ToolStaging staging) {
        List<String> missing;
        try {
            missing = staging.missing();
        } catch (RuntimeException e) {
            LOG.error("checking for missing simulators failed: {}", e.toString());
            return;
        }
        if (missing.isEmpty()) {
            return;
        }
        String what = String.join(", ", missing);
        shell.toolsStaging(true);
        shell.loading("tools", "downloading " + what + " (runs wait for it)");
        shell.setupStatus("Downloading Java 17 / simulators…");
        shell.notify("Signed in to Artifactory: downloading " + what + " in the background. "
                + "Runs wait until it is done.", "info", 15);
        List<String> problems;
        try {
            problems = staging.stageMissing(LOG::info);
        } catch (RuntimeException e) {
            LOG.error("downloading simulators failed: {}", e.toString());
            problems = List.of(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            shell.toolsStaging(false);
            shell.doneLoading("tools");
        }
        if (!problems.isEmpty()) {
            shell.notify("Not everything downloaded; sign in again on the Setup page to retry:\n"
                    + String.join("\n", problems), "error", 30);
        } else {
            shell.notify("Downloaded and staged: " + what, "positive", 5);
        }
        synchronized (this) {
            if (audit != null) {
                for (String name : TOOL_ITEMS) {
                    if (audit.item(name) != null) {
                        audit.redetect(name);
                    }
                }
            }
        }
        refreshStatus();
    }

    /**
     * Why a run may not start for setup's sake, or "": no sign-in saved, or Java 17 / the simulators still
     * downloading (the Python's {@code _signed_in_launch}). Detects the credential items now, so a login saved a
     * moment ago counts. Always "" when sign-ins are not required ({@code --no-setup}).
     */
    String runRefusal() {
        if (!requireCredentials) {
            return "";
        }
        SetupEnv env;
        List<SetupItem> items;
        synchronized (this) {
            env = audit == null ? null : audit.env();
            items = audit == null ? null : audit.items();
        }
        Map<String, Result> gaps;
        try {
            if (env == null) {
                SetupAudit fresh = audits.get();
                env = fresh.env();
                items = fresh.items();
            }
            gaps = SetupAudit.credentialGaps(items, env);
        } catch (RuntimeException e) {
            gaps = new LinkedHashMap<>();
            gaps.put("the credential check (" + e.getMessage() + ")", null);
        }
        if (!gaps.isEmpty()) {
            return "Run not started: sign in first on the Setup page. Missing: " + String.join(", ", gaps.keySet());
        }
        if (shell.toolsStaging()) {
            return "Run not started: Java 17 / the simulators are still downloading. "
                    + "Start it again when CAT says they are staged.";
        }
        return "";
    }

    /** The warning a run starts with when a saved login was rejected, or "". */
    synchronized String rejectedWarning() {
        if (audit == null || audit.rejected().isEmpty()) {
            return "";
        }
        return "Starting anyway, but the saved login was rejected (" + String.join(", ", new TreeSet<>(audit.rejected()))
                + "): downloads and the petroapps fetch will fail until it is fixed on the Setup page.";
    }

    synchronized Snapshot snapshot() {
        if (audit == null) {
            return new Snapshot(List.of(), Map.of(), false, checking, Set.of(), Set.of(), shell.setupStatus(),
                    shell.loading("tools"), null);
        }
        return new Snapshot(audit.items(), audit.results(), true, checking, audit.rejected(), audit.signedIn(),
                shell.setupStatus(), shell.loading("tools"), audit.env());
    }
}
