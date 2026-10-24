package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ValidationFailure;
import com.verifone.cat.validate.PyRepr;
import com.verifone.cat.validate.tlog.TlogOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * {@code RESET_PACKETS} and {@code VALIDATE_PACKETS}: the simulator's captured packets against the case's
 * {@code Packets/<DEVICE>/} references, with the packet rule file. Ported from the Python
 * {@code validation._reset_packets} and {@code _validate_packets}.
 *
 * <p>The verdict is PATS's: the last file compared, in name order, decides. A difference in an earlier file is
 * logged as a WARNING, since PATS never reports it. A listed packet the simulator never wrote is named in the
 * failure, so the verdict is not misread.
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
final class PacketHandler {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The simulators PATS reads packets from (CommonActionManager's resetPacket and validatePackets). */
    static final Set<String> DEVICES = Set.of("BUYPASS", "PCATS01", "PCATS02");

    /** The poll ceiling for packets PATS has not yet written when the budget is zero (Python: {@code or 0.25}). */
    static final double DEFAULT_WAIT = 0.25;

    /** The suffix of a packet's CAT baseline, after the device name. */
    static final String BASELINE_SUFFIX = "-CATPacket.xml";

    /** How often the captured listing is looked at while the listed packets are awaited. */
    private static final long LOOK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final ValidationContext context;

    PacketHandler(ValidationContext context) {
        this.context = context;
    }

    /** {@code CommonActionManager.resetPacket}: empties the simulator's capture, for the devices PATS reads. */
    void reset(CaseRun run, RunnableAction action) {
        String device = action.get("DEVICE").toUpperCase(Locale.ROOT);
        if (!DEVICES.contains(device)) {
            LOG.info("{}: RESET_PACKETS for '{}' does nothing, as in PATS", run.kase().key(), device);
            return;
        }
        context.packets().reset(device);
    }

    /** {@code CommonActionManager.validatePackets}: the captured packets against the case's references. */
    void validate(CaseRun run, RunnableAction action) throws Exception {
        String device = action.get("DEVICE").toUpperCase(Locale.ROOT);
        if (!DEVICES.contains(device)) {
            throw new ValidationFailure("PATS does not support packet validation for '" + device + "'");
        }
        List<String> wanted = Arrays.stream(ValidationSupport.require(action, "SELECT_REFERENCE_FILE").split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
        String prefix = ValidationSupport.packetPrefix(run.kase().key());
        Set<String> listed = wanted.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        TreeMap<String, Path> found = awaitCaptured(run, device, prefix, listed);

        boolean update = run.update() || "1".equals(context.env(ValidationContext.PACKET_REBASE_ENV));
        String rule = ValidationSupport.require(action, "SELECT_RULE_FILE");
        ValidationSupport.RuleLists lists = ValidationSupport.ruleLists(context, ValidationSupport.PACKET_RULES, rule);
        TlogOptions options = ValidationSupport.options(lists.tags(), lists.attributes());

        Verdict last = null;
        List<String> overlooked = new ArrayList<>();
        List<Shown> shown = new ArrayList<>();
        for (Map.Entry<String, Path> entry : found.entrySet()) {
            String key = entry.getKey();
            if (!listed.contains(key)) {
                continue; // PATS: a captured packet not in the list is only a remark
            }
            String name = wanted.stream().filter(n -> n.toLowerCase(Locale.ROOT).equals(key)).findFirst()
                    .orElseThrow();
            Path catPath = ValidationSupport.baselineDir(run).resolve(
                    ValidationSupport.pathStem(name) + "-" + device + BASELINE_SUFFIX);
            boolean hasReference = context.references().reference("Packets/" + device, name).isPresent();
            if (!Files.isRegularFile(catPath) && !hasReference) {
                continue; // PATS: a missing reference is only a remark
            }
            Path captured = entry.getValue();
            Element actual = ValidationSupport.parse(Files.readAllBytes(captured));
            if (update) {
                if (!hasReference || !ValidationSupport.xmlDiff(
                        ValidationSupport.serialiseBytes(ValidationSupport.withoutTimeOffsets(
                                ValidationSupport.parse(referenceBytes(device, name)))),
                        ValidationSupport.withoutTimeOffsets(actual), options).isEmpty()) {
                    Files.write(catPath, Files.readAllBytes(captured));
                    LOG.warn("{}: wrote {}; review it against {}", run.kase().key(), catPath, name);
                }
                continue;
            }
            boolean own = Files.isRegularFile(catPath);
            byte[] reference = own ? Files.readAllBytes(catPath) : referenceBytes(device, name);
            try {
                reference = ValidationSupport.serialiseBytes(
                        ValidationSupport.withoutTimeOffsets(ValidationSupport.parse(reference)));
            } catch (RuntimeException malformed) {
                // The comparison reports a malformed reference, as PATS's does.
            }
            Element shownActual = ValidationSupport.withoutTimeOffsets(actual);
            String difference = ValidationSupport.xmlDiff(reference, shownActual, options);
            String shownName = own ? catPath.getFileName().toString() : name;
            String[] texts = ValidationSupport.xmlTexts(reference, shownActual, options);
            shown.add(new Shown(shownName, texts[0], texts[1]));
            if (last != null && !last.difference().isEmpty()) {
                overlooked.add(last.name() + ":\n" + last.difference());
            }
            last = new Verdict(shownName, difference);
        }
        if (update) {
            return;
        }
        run.setCompared(
                shown.stream().map(s -> "== " + s.name() + " ==\n" + s.expected()).collect(Collectors.joining("\n")),
                shown.stream().map(s -> "== " + s.name() + " ==\n" + s.actual()).collect(Collectors.joining("\n")));
        if (last == null) {
            throw new ValidationFailure("no captured " + device + " packet matched " + PyRepr.repr(wanted)
                    + " (captured: " + PyRepr.repr(new ArrayList<>(found.keySet())) + ")");
        }
        if (!overlooked.isEmpty()) {
            LOG.warn("{}: packet differences PATS's verdict does not see (it keeps only the last file's):\n{}",
                    run.kase().key(), String.join("\n", overlooked));
        }
        if (!last.difference().isEmpty()) {
            throw new ValidationFailure("packets differ:\n" + last.name() + ":\n" + last.difference()
                    + neverCaptured(wanted, found.keySet()));
        }
    }

    /**
     * The captured packets of {@code device}, keyed by {@code <prefix>-<file name>} in lower case, waiting a
     * bounded time for each listed one to appear. PATS compares whatever is there when the wait runs out.
     */
    private TreeMap<String, Path> awaitCaptured(CaseRun run, String device, String prefix, Set<String> listed) {
        double budget = run.budget(0.0);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                (long) ((budget > 0 ? budget : DEFAULT_WAIT) * 1000));
        TreeMap<String, Path> found = captured(device, prefix);
        while (!found.keySet().containsAll(listed) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(LOOK_NANOS);
            found = captured(device, prefix);
        }
        return found;
    }

    private TreeMap<String, Path> captured(String device, String prefix) {
        TreeMap<String, Path> found = new TreeMap<>();
        for (Path file : context.packets().captured(device)) {
            if (Files.isRegularFile(file)) {
                found.put((prefix + "-" + file.getFileName()).toLowerCase(Locale.ROOT), file);
            }
        }
        return found;
    }

    private byte[] referenceBytes(String device, String name) {
        return context.references().reference("Packets/" + device, name)
                .orElseThrow(() -> new PatsStepError(
                        "no packet reference named " + name + " for " + device));
    }

    /**
     * A note naming the listed packets the simulator never wrote, or "". PATS's verdict is the last file compared,
     * so a missing response hands it to its request.
     */
    static String neverCaptured(List<String> wanted, Set<String> found) {
        List<String> missing = wanted.stream()
                .filter(name -> !found.contains(name.toLowerCase(Locale.ROOT)))
                .toList();
        if (missing.isEmpty()) {
            return "";
        }
        return "\nnever captured: " + String.join(", ", missing)
                + ". The simulator wrote no such packet, so the verdict fell to the last file it did write.";
    }

    /** The last file compared, and the difference it had: PATS's verdict. */
    private record Verdict(String name, String difference) {
    }

    /** One compared file's expected and actual text, for the side by side. */
    private record Shown(String name, String expected, String actual) {
    }
}
