package com.verifone.cat.provision;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;

/**
 * How one {@code provision} run is configured: the transport and probe per box, the payload, the checklists, the
 * clocks. Every setter returns this. A port of the keyword arguments of {@code petrotest.provision.runner.provision_rig}.
 *
 * <p>{@link #registerChecklist} runs on a register that opted into a browser override; {@link #posShimChecklist}
 * runs on every register when the rig declares {@code pos_shim: true}. They are independent, as in Python.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ProvisionOptions {

    /** The 6.1 wait for the POS to settle after a restart. */
    public static final Duration POS_APP_SETTLE = Duration.ofSeconds(300);

    /** How often the settle wait looks. */
    public static final Duration POS_APP_POLL = Duration.ofSeconds(5);

    private boolean force;
    private Path payloadRoot = ProvisionPaths.payloadRoot(System.getenv());
    private Manifest.ContentReader read;
    private Function<TargetSpec, Transport> transports = spec -> new JschTransport(spec.toSshTarget());
    private Function<TargetSpec, AgentProbe> probes = ProvisionOptions::defaultProbe;
    private Timeouts timeouts = Timeouts.defaults();
    private List<Artifact> artifacts = Artifacts.ARTIFACTS;
    private List<ChecklistStep> checklist = Checklist.CHECKLIST_STEPS;
    private List<ChecklistStep> registerChecklist = List.of();
    private List<ChecklistStep> posShimChecklist = List.of();
    private String toolVersion = "";
    private Instant now;
    private boolean stage = true;
    private Stage.Sources stageSources = Stage.Sources.fromEnv(System.getenv());
    private Duration settle = POS_APP_SETTLE;
    private Duration poll = POS_APP_POLL;
    private Map<String, String> environ = System.getenv();

    /**
     * The defaults {@code provision --rig} uses: the register checklists the Python CLI passes
     * ({@code default_register_checklists}). The claim runs first: the steps after it run as {@code topaz}.
     */
    public static ProvisionOptions defaults() {
        return new ProvisionOptions()
                .registerChecklist(List.of(
                        new Checklist.ClaimPushedDirectory("/home/topaz/browser", "topaz:vfi"),
                        new BrowserOverride.EnableBrowserOverride(BrowserOverride.TOPAZ_RESTART)))
                .posShimChecklist(List.of(
                        new Checklist.ClaimPushedDirectory("/home/topaz/automation", "topaz:vfi"),
                        new RegisterBringup.EnsurePosShimCurrent(),
                        new RegisterBringup.EnsurePosShimRunning()));
    }

    /** The probe for a box: the register's live shim, or the Commander's no-op. */
    public static AgentProbe defaultProbe(TargetSpec spec) {
        if (spec.role() == TargetRole.REGISTER) {
            return new AgentProbe.Http(spec.host(), spec.shimPort());
        }
        return new AgentProbe.Null();
    }

    public boolean force() {
        return force;
    }

    public ProvisionOptions force(boolean value) {
        force = value;
        return this;
    }

    public Path payloadRoot() {
        return payloadRoot;
    }

    public ProvisionOptions payloadRoot(Path value) {
        payloadRoot = value;
        return this;
    }

    /** The payload reader; null means the staged payload directory, with staging on. */
    public Manifest.ContentReader read() {
        return read;
    }

    public ProvisionOptions read(Manifest.ContentReader value) {
        read = value;
        return this;
    }

    public Function<TargetSpec, Transport> transports() {
        return transports;
    }

    public ProvisionOptions transports(Function<TargetSpec, Transport> value) {
        transports = value;
        return this;
    }

    public Function<TargetSpec, AgentProbe> probes() {
        return probes;
    }

    public ProvisionOptions probes(Function<TargetSpec, AgentProbe> value) {
        probes = value;
        return this;
    }

    public Timeouts timeouts() {
        return timeouts;
    }

    public ProvisionOptions timeouts(Timeouts value) {
        timeouts = value;
        return this;
    }

    public List<Artifact> artifacts() {
        return artifacts;
    }

    public ProvisionOptions artifacts(List<Artifact> value) {
        artifacts = value;
        return this;
    }

    public List<ChecklistStep> checklist() {
        return checklist;
    }

    public ProvisionOptions checklist(List<ChecklistStep> value) {
        checklist = value;
        return this;
    }

    public List<ChecklistStep> registerChecklist() {
        return registerChecklist;
    }

    public ProvisionOptions registerChecklist(List<ChecklistStep> value) {
        registerChecklist = value;
        return this;
    }

    public List<ChecklistStep> posShimChecklist() {
        return posShimChecklist;
    }

    public ProvisionOptions posShimChecklist(List<ChecklistStep> value) {
        posShimChecklist = value;
        return this;
    }

    public String toolVersion() {
        return toolVersion;
    }

    public ProvisionOptions toolVersion(String value) {
        toolVersion = value;
        return this;
    }

    /** The stamp's clock; null means now. */
    public Instant now() {
        return now;
    }

    public ProvisionOptions now(Instant value) {
        now = value;
        return this;
    }

    public boolean stage() {
        return stage;
    }

    public ProvisionOptions stage(boolean value) {
        stage = value;
        return this;
    }

    public Stage.Sources stageSources() {
        return stageSources;
    }

    public ProvisionOptions stageSources(Stage.Sources value) {
        stageSources = value;
        return this;
    }

    public Duration settle() {
        return settle;
    }

    public ProvisionOptions settle(Duration value) {
        settle = value;
        return this;
    }

    public Duration poll() {
        return poll;
    }

    public ProvisionOptions poll(Duration value) {
        poll = value;
        return this;
    }

    public Map<String, String> environ() {
        return environ;
    }

    public ProvisionOptions environ(Map<String, String> value) {
        environ = value;
        return this;
    }
}
