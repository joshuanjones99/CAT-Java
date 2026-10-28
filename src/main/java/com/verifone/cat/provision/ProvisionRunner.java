package com.verifone.cat.provision;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.preflight.Bounded;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * The orchestration of DESIGN.md 6.1, per target: manifest, hash guard, push, verify, checklist, stamp last. A port
 * of {@code petrotest.provision.runner}.
 *
 * <p>The stamp is written last, and only on success: a stamp before verification would let the next run skip a
 * rig that did not provision. A no-op (stamp match) reads one file and runs the checklist, which is runtime state
 * and not payload, so it still starts the shim when the shim is down.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). No target has been provisioned by this code.
 */
public final class ProvisionRunner {

    private static final Logger log = LoggerFactory.getLogger(ProvisionRunner.class);

    /** 6.1: "If they match it exits in under two seconds having done nothing." A warning above it, not a failure. */
    public static final Duration NOOP_BUDGET = Duration.ofSeconds(2);

    /** What pairing creates on a register, and what the shim's RMI host needs to listen. */
    static final String RMI_KEYSTORE = "/home/common/x509/rmi_keystore.jks";

    /** The step that restarts the POS after a shim the checklist started. */
    static final String SETTLE_STEP = "restart_pos_after_shim_start";

    static final String AGENTS_AFTER_PAIRING = "agents_after_pairing";

    private ProvisionRunner() {}

    /**
     * Provisions one box. Every 6.1 requirement is visible here.
     *
     * @param read        the payload's file contents
     * @param checklist   the checklist steps for this box, already bound to it
     * @param controllerHost the controller's address, for steps that name it
     */
    public static TargetReport provisionTarget(TargetSpec spec, String rigName, Manifest.ContentReader read,
            Transport transport, AgentProbe probe, ProvisionOptions opts, List<ChecklistStep> checklist,
            String controllerHost) {
        long started = System.nanoTime();
        TargetReport report = new TargetReport(spec.describe(), spec.role());
        Duration ssh = opts.timeouts().ssh();
        Duration http = opts.timeouts().defaultWait();
        String stampPath = Artifacts.stampPath(spec.role());
        try {
            Map<String, byte[]> contents = new HashMap<>();
            Manifest.ContentReader caching = file -> {
                byte[] data = read.read(file);
                contents.put(file.remotePath(), data);
                return data;
            };
            Manifest manifest = Manifest.build(spec.role(), caching, opts.artifacts());
            report.manifestDigest(manifest.digest());

            // --- the hash guard (6.1) ---------------------------------------------------------
            Stamp existing = Stamp.parse(transport.get(stampPath, ssh));
            if (existing != null && existing.matches(manifest) && !opts.force()) {
                report.decision(TargetReport.Decision.UP_TO_DATE);
                // Checklist steps are runtime state (a process running, a toggle), not payload content, so they run
                // even when nothing was pushed: a shim that is down is started on a stamp-match rerun too.
                for (ChecklistStep step : checklist) {
                    StepResult result = step.apply(transport, controllerHost, ssh);
                    report.checklist(result);
                    String failure = failure(step, result);
                    if (failure != null) {
                        report.decision(TargetReport.Decision.FAILED);
                        report.error(failure);
                    }
                }
                StepResult settled = null;
                if (shimStarted(report.checklist()) && registerUnpaired(spec, transport, ssh)) {
                    report.checklist(agentsAfterPairingSkipped(spec));
                } else {
                    settled = settleFreshShim(report.checklist(), transport, probe, opts, ssh, http);
                }
                if (settled != null) {
                    report.checklist(settled);
                    if (!settled.ok()) {
                        report.decision(TargetReport.Decision.FAILED);
                        report.error("checklist step " + settled.step() + " failed: " + settled.detail());
                    }
                }
                return report;
            }
            if (existing != null) {
                report.drift(existing.drift(manifest));
                log.info("{}: stamp differs, {} path(s) to update", spec.describe(), report.drift().size());
            } else if (opts.force()) {
                log.info("{}: --force, re-pushing regardless of stamp", spec.describe());
            } else {
                log.info("{}: no usable stamp, provisioning from scratch", spec.describe());
            }

            // --- the push -------------------------------------------------------------------
            for (Manifest.Entry entry : manifest.entries()) {
                transport.put(entry.remotePath(), contents.get(entry.remotePath()), entry.mode(), ssh);
                report.pushed(entry.remotePath());
            }

            // --- verification (6.1), content first ------------------------------------------
            Verification.Report verification = new Verification.Report(spec.role().wire(), transport.host());
            Verification.verifyContent(manifest, transport, ssh, verification);
            report.verification(verification);

            // The checklist starts the shim, so it runs before the agents are asked: a probe ahead of it could only fail
            // on a freshly imaged register.
            for (ChecklistStep step : checklist) {
                StepResult result = step.apply(transport, controllerHost, ssh);
                report.checklist(result);
                String failure = failure(step, result);
                if (failure != null) {
                    verification.fail(failure);
                }
            }
            if (registerUnpaired(spec, transport, ssh)) {
                // A fresh install: the shim's RMI host needs the keystore that pairing creates, so asking the agents
                // proves nothing. The push is complete and stamped; psi-init pairs, and the next run brings the shim up.
                report.checklist(agentsAfterPairingSkipped(spec));
                verification.agentsOk(true);
            } else {
                StepResult settled = settleFreshShim(report.checklist(), transport, probe, opts, ssh, http);
                if (settled != null) {
                    report.checklist(settled);
                    if (!settled.ok()) {
                        verification.fail("checklist step " + settled.step() + " failed: " + settled.detail());
                    }
                }
                Verification.verifyAgents(probe, http, verification);
            }
            if (!verification.ok()) {
                report.decision(TargetReport.Decision.FAILED);
                report.error(verification.problems().get(0));
                return report;
            }

            // --- stamp LAST, and only on success ---------------------------------------------
            Stamp stamp = Stamp.forManifest(manifest, rigName, opts.toolVersion(), opts.now() == null ? Instant.now()
                    : opts.now());
            transport.put(stampPath, stamp.toBytes(), 0644, ssh);
            report.decision(TargetReport.Decision.PROVISIONED);
        } catch (DeviceError | Bounded.TimeoutExpired | Manifest.MissingPayloadException
                | Manifest.CrlfScriptException | IllegalArgumentException | UncheckedIOException e) {
            report.decision(TargetReport.Decision.FAILED);
            report.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            report.elapsedSeconds((System.nanoTime() - started) / 1e9);
            if (report.decision() == TargetReport.Decision.UP_TO_DATE
                    && report.elapsedSeconds() > NOOP_BUDGET.toSeconds()) {
                // Not a failure, but 6.1 puts a number on this, and 6.4 depends on it.
                log.warn("provision no-op for {} took {}s, over the 6.1 two-second budget", spec.describe(),
                        String.format("%.2f", report.elapsedSeconds()));
            }
        }
        return report;
    }

    /**
     * Provisions every target of a rig, one at a time: a rig has two or three boxes, and a serial loop reads in the
     * order things happened.
     */
    public static ProvisionReport provisionRig(Rig rig, ProvisionOptions opts) {
        long started = System.nanoTime();
        String rigName = rig.name();
        String controllerHost = rig.controller().host();
        Manifest.ContentReader reader = opts.read() != null ? opts.read()
                : Manifest.payloadReader(opts.payloadRoot());
        Set<String> overridden = rig.registers().stream().filter(r -> r.browserOverride() != null)
                .map(Rig.Register::id).collect(Collectors.toSet());

        ProvisionReport report = new ProvisionReport(rigName, opts.force());
        for (TargetSpec spec : TargetSpec.fromRig(rig, opts.environ())) {
            Transport transport = opts.transports().apply(spec);
            try {
                if (opts.stage() && opts.read() == null) {
                    Stage.stagePayload(rigName, spec, transport, opts.payloadRoot(), opts.timeouts().ssh(),
                            opts.stageSources());
                }
                // The Commander gets the Commander checklist. A register gets the browser-override list when it opted
                // in, and the pos-shim list when the rig declares pos_shim: independent opt-ins, as in Python.
                List<ChecklistStep> steps = new ArrayList<>();
                if (spec.role() == TargetRole.COMMANDER) {
                    steps.addAll(opts.checklist());
                } else {
                    if (overridden.contains(spec.name())) {
                        steps.addAll(opts.registerChecklist());
                    }
                    if (rig.posShim()) {
                        steps.addAll(opts.posShimChecklist());
                    }
                }
                List<ChecklistStep> bound = steps.stream().map(s -> s.forTarget(spec, opts.payloadRoot())).toList();
                report.add(provisionTarget(spec, rigName, reader, transport, opts.probes().apply(spec), opts, bound,
                        controllerHost));
            } finally {
                transport.close();
            }
        }
        report.elapsedSeconds((System.nanoTime() - started) / 1e9);
        return report;
    }

    /**
     * Why a checklist result fails its target, or null when it does not: a FAILED step, or a SKIPPED one the box
     * {@linkplain ChecklistStep#required() requires}. The Python fails only on FAILED.
     */
    static String failure(ChecklistStep step, StepResult result) {
        if (!result.ok()) {
            return "checklist step " + result.step() + " failed: " + result.detail();
        }
        if (step.required() && result.status() == StepStatus.SKIPPED) {
            return "required checklist step " + result.step() + " was skipped: " + result.detail();
        }
        return null;
    }

    /** {@code petrotest-pos-shim.sh}'s restart and the POS restart, when the checklist started the shim (not found it). */
    static StepResult settleFreshShim(List<StepResult> results, Transport transport, AgentProbe probe,
            ProvisionOptions opts, Duration ssh, Duration http) {
        if (!shimStarted(results) || !probe.applicable()) {
            return null;
        }
        try {
            transport.run(RegisterBringup.POS_APP_RESTART, ssh);
            Poll.until(() -> probe.rmiAgentAnswers(http) ? Boolean.TRUE : null, opts.settle(), opts.poll(),
                    "the shim's status round-trip after the POS restart");
        } catch (DeviceError | Bounded.TimeoutExpired e) {
            return new StepResult(SETTLE_STEP, StepStatus.FAILED, e.getMessage(), true);
        }
        return new StepResult(SETTLE_STEP, StepStatus.DONE, "POS restarted; RMI agent answers", true);
    }

    static boolean shimStarted(List<StepResult> results) {
        return results.stream().anyMatch(r -> r.step().equals("ensure_pos_shim_running")
                && r.status() == StepStatus.DONE);
    }

    /** True only for a register that provably lacks its RMI keystore. A failed probe is not evidence of it. */
    static boolean registerUnpaired(TargetSpec spec, Transport transport, Duration ssh) {
        if (spec.role() != TargetRole.REGISTER) {
            return false;
        }
        try {
            CommandResult result = transport.run("test -f " + RMI_KEYSTORE, ssh);
            return !result.ok();
        } catch (DeviceError e) {
            return false;
        }
    }

    static StepResult agentsAfterPairingSkipped(TargetSpec spec) {
        return new StepResult(AGENTS_AFTER_PAIRING, StepStatus.SKIPPED, spec.describe() + " is not paired ("
                + RMI_KEYSTORE + " absent): the shim's RMI host cannot start until psi-init pairs it; provision again"
                + " after");
    }
}
