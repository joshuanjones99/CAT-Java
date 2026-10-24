package com.verifone.cat.provision;

import java.time.Duration;
import java.util.List;

import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The checklist items {@code provision} performs after the push (DESIGN.md 6.1). A port of
 * {@code petrotest.provision.checklist}.
 *
 * <p>{@link DummyRootCertOnCommander} and {@link ClaimPushedDirectory} do their work over {@link Transport#run}.
 * {@link BuypassFepConfig} and {@link TableDownload} are stubs that need a live Commander: they report
 * {@code SKIPPED} with a reason, so "never configured" cannot read as "configured".
 *
 * <p>The manager password is deliberately absent: it is a one-time rig-build step, not a per-run one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Checklist {

    /** The Buypass UMF port: a constant of the protocol. The address comes from the rig file. */
    public static final int BUYPASS_UMF_PORT = 41107;

    /** Applied to the Commander target, after the push and verification. */
    public static final List<ChecklistStep> CHECKLIST_STEPS = List.of(
            new DummyRootCertOnCommander(),
            new BuypassFepConfig(),
            new TableDownload());

    private Checklist() {}

    /**
     * Recreates {@code Utility.createDummyRootCertFileInCommander()}: three remote commands, no content. The three
     * are idempotent, so the step runs on every provision.
     */
    public record DummyRootCertOnCommander(String name) implements ChecklistStep {

        public static final List<String> COMMANDS = List.of(
                "mkdir -p /home/common/data/security/trustStoreDir",
                "touch /home/common/data/security/trustStoreDir/root_ca.crt",
                "chown coresvcs:vfi /home/common/data/security/trustStoreDir/ "
                        + "/home/common/data/security/trustStoreDir/root_ca.crt");

        public DummyRootCertOnCommander() {
            this("dummy_root_cert");
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            for (String command : COMMANDS) {
                CommandResult result = transport.run(command, timeout);
                if (!result.ok()) {
                    return new StepResult(name, StepStatus.FAILED, "'" + command + "' exited " + result.exitStatus()
                            + ": " + result.stderr().strip());
                }
            }
            return new StepResult(name, StepStatus.DONE, "");
        }
    }

    /** The Buypass FEP's UMF configuration. A stub: it needs a live Commander to confirm the config path. */
    public record BuypassFepConfig(String name) implements ChecklistStep {

        public BuypassFepConfig() {
            this("buypass_fep_umf");
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            return new StepResult(name, StepStatus.SKIPPED, "not implemented - needs a live Commander to confirm the"
                    + " config path and restart semantics (DESIGN.md 6.1). Target: Payment Controller UMF -> "
                    + controllerHost + ":" + BUYPASS_UMF_PORT + ". Until this lands it stays a manual checklist item.");
        }
    }

    /** The Commander table download. A stub: it needs a live Commander to find the completion signal. */
    public record TableDownload(String name) implements ChecklistStep {

        public TableDownload() {
            this("table_download");
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            return new StepResult(name, StepStatus.SKIPPED, "not implemented - needs a live Commander to determine the"
                    + " trigger and the completion signal (DESIGN.md 6.1). Must be idempotent: re-downloading tables"
                    + " on every provision would blow the 6.4 startup budget.");
        }
    }

    /**
     * {@code chown -R} a directory provision pushed into, to the user that reads it. A push runs as root, so the
     * register paths read by {@code topaz} need this (HANDOFF.md 1.73). Recursive on purpose: the directory itself
     * must be writable, not only its contents.
     */
    public record ClaimPushedDirectory(String path, String owner, String name) implements ChecklistStep {

        public ClaimPushedDirectory {
            if (name == null || name.isEmpty()) {
                name = "claim_" + RemotePaths.name(path);
            }
        }

        public ClaimPushedDirectory(String path, String owner) {
            this(path, owner, null);
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            String command = "chown -R " + owner + " " + path;
            CommandResult result = transport.run(command, timeout);
            if (!result.ok()) {
                return new StepResult(name, StepStatus.FAILED, "'" + command + "' exited " + result.exitStatus()
                        + ": " + result.stderr().strip());
            }
            return new StepResult(name, StepStatus.DONE, path + " -> " + owner);
        }
    }

    /**
     * A register that opted into a browser override. The override itself (the Python CAT's
     * {@code provision.browser_override}) is not ported, so this reports that rather than passing silently.
     * The Chromium files are still pushed, and they stay inert until something enables them.
     */
    public record BrowserOverrideNotPorted(String name) implements ChecklistStep {

        public BrowserOverrideNotPorted() {
            this("browser_override");
        }

        @Override
        public StepResult apply(Transport transport, String controllerHost, Duration timeout) {
            return new StepResult(name, StepStatus.SKIPPED, "not ported to CAT-Java: the browser override is still"
                    + " the Python CAT's (BROWSER_STRATEGY.md 5.3). The pushed Chromium files stay inert.");
        }
    }
}
