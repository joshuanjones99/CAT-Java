package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.configuration.ActionFields.require;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two handlers that write a simulator's files: {@code UPDATE_CONFIGURATION}, which copies an
 * {@code EndPoints/<DEVICE>} file over a simulator's property file, and {@code HTTP_SIM_EVENT}, which drops a control
 * file and a payload into Petro Simulator Suite's HTTPSIM folders. Ported from the Python's {@code configuration.py}
 * ({@code _update_configuration}, {@code _http_sim_event}, {@code _http_payload}).
 *
 * <p>Each change to a simulator's configuration is recorded with {@link CaseRun#recordSimulatorChange} before it is
 * made, so a hard failure of the case puts back what it changed. Only the first change to a target is recorded, as
 * the Python's {@code run.sim_props} keeps the first. A case that runs to its end keeps what it wrote, as PATS does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: nothing has been written to a simulator from this port.
 */
final class SimulatorFiles {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The BUYPASS Host's answer file; with FEPSIM v2 serving Buypass, its text goes to v2 instead. */
    static final String BUYPASS_PROP = "PayHostSim.prop";

    /** Petro Simulator Suite's HTTPSIM control file, under the tools root. */
    static final List<String> HTTPSIM_CONTROL =
            List.of("Petro_Simulator_Suite", "config", "HTTPSIM", "control", "httpSimControl.prop");

    /** Petro Simulator Suite's HTTPSIM payload file, under the tools root. */
    static final List<String> HTTPSIM_PAYLOAD =
            List.of("Petro_Simulator_Suite", "config", "HTTPSIM", "payload", "httpSimPayLoad.txt");

    /** The simulator targets this case has already changed (path or FEPSIM v2), so only the first is recorded. */
    static final ScratchKey<Set<Object>> CHANGED = ScratchKey.of("configuration.changed-simulators", changedType());

    private static final String ENDPOINTS = "AutomationTests/configurations/EndPoints";

    /** Which property file each simulator family reads: the Python's {@code _SIM_PROPS}, in its order. */
    private static final List<Family> FAMILIES = List.of(
            new Family(List.of("PCATS01", "PCATS02", "PCATS03", "PCATS04", "VCMG"), "LoyaltySim.prop"),
            new Family(List.of("BUYPASS", "BP"), BUYPASS_PROP),
            new Family(List.of("HPS"), "HPSConstants.properties"));

    private final ConfigurationContext context;

    SimulatorFiles(ConfigurationContext context) {
        this.context = context;
    }

    private record Family(List<String> names, String propertyFile) {
    }

    /**
     * {@code UpdateConfigurationManager}: copies an {@code EndPoints/<DEVICE>/} file over the simulator's property
     * file. PATS neither restarts the simulator nor tells it. An empty DEVICE names no simulator.
     */
    void updateConfiguration(CaseRun run, RunnableAction action) throws Exception {
        String device = action.get("DEVICE").toUpperCase(Locale.ROOT);
        String target = propertyFileFor(device);
        if (target == null) {
            throw new PatsStepError("UPDATE_CONFIGURATION: no simulator property file for '" + device + "'");
        }
        byte[] data = context.petroapps().shared(ENDPOINTS + "/" + device, require(action, "SELECT_FILE"));
        if (BUYPASS_PROP.equals(target)) {
            Optional<BuypassV2> fepsim = context.buypassV2().get();
            if (fepsim.isPresent()) {
                // FEPSIM v2 serves Buypass this run: its rule set reads the same text, sent to it.
                BuypassV2 v2 = fepsim.get();
                if (changed(run).add(v2)) {
                    String before = v2.configText() == null ? "" : v2.configText();
                    run.recordSimulatorChange(v2, () -> v2.applyConfig(before));
                }
                v2.applyConfig(new String(data, StandardCharsets.UTF_8));
                return;
            }
        }
        Path path = context.toolsRoot().resolve(device).resolve(target);
        if (!changed(run).contains(path) && Files.exists(path)) {
            byte[] before = readForRestore(path);
            changed(run).add(path);
            run.recordSimulatorChange(path, () -> writeForRestore(path, before));
        }
        Files.write(path, data);
    }

    /**
     * {@code HttpSimActionsManager.httpSimEvent}: copies the control file, then the payload, over the HTTP
     * simulator's own two files. Nothing goes over HTTP from here: the payload's write is the event, which Petro
     * Simulator Suite's HTTPSIM sends on to the register. A simulator folder that is not there means the HTTP
     * simulator is not running, and fails the step, as PATS's own {@code IOException} does.
     */
    void httpSimEvent(CaseRun run, RunnableAction action) throws IOException {
        String device = action.get("DEVICE").strip();
        if (device.isEmpty()) {
            throw new PatsStepError("HTTP_SIM_EVENT names no DEVICE (PATS's handler then does nothing)");
        }
        String controlName = require(action, "SELECT_CONTROL_FILE");
        String payloadName = require(action, "SELECT_PAYLOAD_FILE");
        String endpoint = ENDPOINTS + "/" + device;
        byte[] control;
        try {
            control = context.petroapps().shared(endpoint + "/control", controlName);
        } catch (NoSuchFileException missing) {
            throw new PatsStepError("HTTP_SIM_EVENT: no control file " + controlName + " in " + endpoint + "/control",
                    missing);
        }
        byte[] payload = httpPayload(run, endpoint + "/payload", payloadName);
        writeHttpsim(context.toolsRoot(), HTTPSIM_CONTROL, control);
        writeHttpsim(context.toolsRoot(), HTTPSIM_PAYLOAD, payload);
        LOG.info("{}: HTTPSIM event {} sent by {}", run.kase().key(), payloadName, controlName);
    }

    private static void writeHttpsim(Path root, List<String> parts, byte[] data) throws IOException {
        Path target = root;
        for (String part : parts) {
            target = target.resolve(part);
        }
        if (!Files.isDirectory(target.getParent())) {
            throw new PatsStepError("HTTP_SIM_EVENT: " + target.getParent() + " is not there; Petro Simulator "
                    + "Suite's HTTPSIM creates it when it starts, so the HTTP simulator is not running");
        }
        Files.write(target, data);
    }

    /**
     * The payload a case's flat payload folder holds under {@code name}: the case's own copy first, then the shared
     * {@code EndPoints} folder, then whichever case folder holds it (PATS copies every case's payloads into the one
     * folder, and some cases name another's).
     */
    private byte[] httpPayload(CaseRun run, String sharedFolder, String name) throws IOException {
        Optional<byte[]> own = context.caseFiles().reference(run.kase(), "httpSimPayLoad", name);
        if (own.isPresent()) {
            return own.get();
        }
        try {
            return context.petroapps().shared(sharedFolder, name);
        } catch (NoSuchFileException notShared) {
            // Not in the shared folder: another case's copy, below.
        }
        Optional<byte[]> any = context.caseFiles().fromAnyCase("httpSimPayLoad", name);
        if (any.isEmpty()) {
            throw new PatsStepError("HTTP_SIM_EVENT: no payload '" + name + "' in any case's folder");
        }
        LOG.info("{}: payload {} is another case's copy, as PATS's folder holds it", run.kase().key(), name);
        return any.get();
    }

    /** The property file a device's name maps to, or {@code null}: the Python's {@code _SIM_PROPS} match. */
    static String propertyFileFor(String device) {
        if (device.isEmpty()) {
            // An empty DEVICE is a substring of every name, so it would pick the first family.
            return null;
        }
        for (Family family : FAMILIES) {
            for (String name : family.names()) {
                if (name.contains(device) || device.contains(name)) {
                    return family.propertyFile();
                }
            }
        }
        return null;
    }

    private static Set<Object> changed(CaseRun run) {
        return run.scratchOrCreate(CHANGED, HashSet::new);
    }

    private static byte[] readForRestore(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path + " to keep it for a restore", e);
        }
    }

    private static void writeForRestore(Path path, byte[] data) {
        try {
            Files.write(path, data);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path + " back", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<Set<Object>> changedType() {
        return (Class) Set.class;
    }
}
