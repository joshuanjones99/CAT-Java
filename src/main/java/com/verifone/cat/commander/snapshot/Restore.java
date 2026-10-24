package com.verifone.cat.commander.snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.devices.DeviceError;

/**
 * Comparing a snapshot with the Commander now, and putting back what differs. A port of {@code commander_snapshot}'s
 * {@code diff}, {@code _compare}, {@code restore}, {@code RestoreReport}, {@code _with_maintenance_group} and
 * {@code restore_for_rig}.
 *
 * <p>Only restorable areas are ever sent ({@link Areas#exclusion}); an area the capture never got fails on its own and
 * the rest are still restored, while a damaged manifest or area file refuses the whole restore.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's partial restore has run on the lab rig (2026-10-15); no
 * merge restore has run on a Commander.
 */
public final class Restore {

    /**
     * Passes over the areas left different. A second pass is for order: an item restored before the department it
     * names can fault, and go through once the department is back.
     */
    public static final int RESTORE_PASSES = 2;

    private Restore() {}

    /**
     * One area of a snapshot against the Commander now.
     *
     * @param status   {@code same}, {@code different} or {@code failed} (the view now faulted, or the snapshot's area
     *                 cannot be used)
     * @param detail   the difference lines, or the failure
     * @param excluded {@link Areas#exclusion} today; an excluded area is reported, never restored
     * @param notes    a merge's notes: what this build has or lacks that the snapshot does not
     */
    public record AreaDiff(String area, String status, List<String> detail, String excluded, List<String> notes) {

        public AreaDiff {
            detail = List.copyOf(detail);
            notes = List.copyOf(notes);
        }

        AreaDiff(String area, String status, List<String> detail, String excluded) {
            this(area, status, detail, excluded, List.of());
        }
    }

    /** What {@link #restore} did to each area that differed. */
    public static class RestoreReport {
        int checked;
        final List<String> restored = new ArrayList<>();
        final Map<String, List<String>> stillDifferent = new LinkedHashMap<>();
        final Map<String, String> failed = new LinkedHashMap<>();

        public int checked() {
            return checked;
        }

        public List<String> restored() {
            return List.copyOf(restored);
        }

        public Map<String, List<String>> stillDifferent() {
            return Map.copyOf(stillDifferent);
        }

        /** Area -> why, in the order they failed. */
        public Map<String, String> failed() {
            return new LinkedHashMap<>(failed);
        }

        /** Nothing left different and nothing failed. */
        public boolean ok() {
            return stillDifferent.isEmpty() && failed.isEmpty();
        }

        public String summary() {
            return "snapshot: " + checked + " areas checked, " + restored.size() + " restored, "
                    + stillDifferent.size() + " still different, " + failed.size() + " failed";
        }
    }

    // -- diff -----------------------------------------------------------------------------------

    /**
     * Views each snapshot area now and compares normalised forms. Restorable areas only, unless
     * {@code includeExcluded}. {@code merge}: a snapshot of another build, compared on what both builds have.
     *
     * @param only these areas only; null for all of them
     * @throws IllegalArgumentException {@code snapshot} holds no manifest
     */
    public static List<AreaDiff> diff(CommanderConfig client, Path snapshot, Collection<String> only,
            boolean includeExcluded, boolean merge, Rules rules) {
        return diff(client, snapshot, manifestOf(snapshot), only, includeExcluded, merge, rules);
    }

    /** {@link #diff(CommanderConfig, Path, Collection, boolean, boolean, Rules)} of every restorable area. */
    public static List<AreaDiff> diff(CommanderConfig client, Path snapshot) {
        return diff(client, snapshot, null, false, false, Rules.DEFAULT);
    }

    private static Map<String, Object> manifestOf(Path snapshot) {
        Map<String, Object> manifest = Snapshots.loadManifest(snapshot);
        if (manifest == null) {
            throw new IllegalArgumentException("no " + Snapshots.MANIFEST + " in " + snapshot);
        }
        return manifest;
    }

    /** (same, diff lines, merge notes) for one view; {@link Merge.NotMergeable} propagates. */
    private record Compared(boolean same, List<String> lines, List<String> notes) {}

    private static Compared compare(String area, String saved, String now, boolean merge, Rules rules) {
        if (!merge) {
            boolean same = Normalise.normalise(area, saved, rules).equals(Normalise.normalise(area, now, rules));
            return new Compared(same, same ? List.of()
                    : Normalise.differences(area, saved, now, Normalise.DIFF_LIMIT, rules), List.of());
        }
        Merge.Merged merged = Merge.mergeDocument(area, saved, now, rules);
        boolean same = Normalise.normalise(area, merged.xml(), rules).equals(Normalise.normalise(area, now, rules));
        return new Compared(same, same ? List.of()
                : Normalise.differences(area, merged.xml(), now, Normalise.DIFF_LIMIT, rules), merged.notes());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> areasOf(Map<String, Object> manifest) {
        Object areas = manifest.get("areas");
        return areas instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<AreaDiff> diff(CommanderConfig client, Path snapshot, Map<String, Object> manifest,
            Collection<String> only, boolean includeExcluded, boolean merge, Rules rules) {
        // Only what this Commander can answer (site_areas): a FEP an older capture requested that this site lacks
        // is not compared, and a FEP view site_areas adds that this snapshot never captured cannot be.
        Object requested = manifest.get("requested_areas");
        List<String> named = new ArrayList<>();
        if (requested instanceof List<?> list) {
            list.forEach(a -> named.add(String.valueOf(a)));
        }
        named.addAll(areasOf(manifest).keySet());
        Set<String> held = new LinkedHashSet<>(named);
        List<String> inScope = new ArrayList<>();
        for (String area : Areas.siteAreas(new ArrayList<>(held), Capture.fepViews(client)).kept()) {
            if (held.contains(area)) {
                inScope.add(area);
            }
        }
        Set<String> wanted = new LinkedHashSet<>(inScope);
        if (only != null) {
            wanted.retainAll(new LinkedHashSet<>(only));
        }
        Map<String, String> problems = Capture.snapshotErrors(snapshot, manifest, wanted);
        // An area the capture never got is that area's failure, not the snapshot's (on the lab Commander the FEP
        // views faulted on every capture). A damaged file or manifest still refuses the whole restore.
        Map<String, String> uncaptured = new LinkedHashMap<>();
        problems.forEach((area, reason) -> {
            if (reason.startsWith(Capture.NOT_CAPTURED)) {
                uncaptured.put(area, reason);
            }
        });
        List<AreaDiff> results = new ArrayList<>();
        if (uncaptured.size() < problems.size()) {
            problems.forEach((area, reason) -> results.add(new AreaDiff(area, "failed", List.of(reason), null)));
            return results;
        }
        uncaptured.forEach((area, reason) -> results.add(new AreaDiff(area, "failed", List.of(reason), null)));
        Map<String, Object> entries = areasOf(manifest);
        Set<String> sorted = new TreeSet<>(Areas.ORDER);
        sorted.addAll(entries.keySet());
        for (String area : sorted) {
            if (!wanted.contains(area) || uncaptured.containsKey(area)) {
                continue;
            }
            String reason = Areas.exclusion(area);
            if (reason != null && !includeExcluded) {
                continue;
            }
            String saved;
            String now;
            try {
                saved = read(snapshot, entries, area);
                now = Capture.viewArea(client, area);
            } catch (DeviceError | IOException | UncheckedIOException e) {
                results.add(new AreaDiff(area, "failed", List.of(String.valueOf(e.getMessage())), reason));
                continue;
            }
            Compared compared;
            try {
                compared = compare(area, saved, now, merge, rules);
            } catch (Merge.NotMergeable e) {
                results.add(new AreaDiff(area, "failed", List.of("not merge-restorable: " + e.getMessage()), reason));
                continue;
            }
            results.add(new AreaDiff(area, compared.same() ? "same" : "different", compared.lines(), reason,
                    compared.notes()));
        }
        return results;
    }

    /** The snapshot's file for {@code area}, decoded strictly as UTF-8. */
    private static String read(Path snapshot, Map<String, Object> entries, String area) throws IOException {
        Object entry = entries.get(area);
        Object file = entry instanceof Map<?, ?> m ? m.get("file") : null;
        if (!(file instanceof String name)) {
            throw new IOException(area + ": the manifest names no file");
        }
        byte[] data = Files.readAllBytes(snapshot.resolve(name));
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(name + " is not UTF-8", e);
        }
    }

    // -- restore --------------------------------------------------------------------------------

    private static int maintenanceRank(String area) {
        int at = area.indexOf("&dataset=");
        String dataset = at < 0 ? "" : area.substring(at + "&dataset=".length());
        int rank = Areas.MAINTENANCE_ORDER.indexOf(dataset);
        return rank < 0 ? Areas.MAINTENANCE_ORDER.size() : rank;
    }

    /**
     * {@code pending}, and when it holds a maintenance dataset, every restorable one, together in
     * {@link Areas#MAINTENANCE_ORDER}: replacing the items can take with it the item lists, combos and deals that
     * named them, which no other view would then be checked for.
     */
    static List<String> withMaintenanceGroup(List<String> pending, Collection<String> restorable) {
        Set<String> group = new LinkedHashSet<>();
        int first = -1;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            String area = pending.get(i);
            if (Areas.baseArea(area).equals("Maintenance")) {
                group.add(area);
                if (first < 0) {
                    first = i;
                }
            } else {
                rest.add(area);
            }
        }
        if (group.isEmpty()) {
            return pending;
        }
        for (String area : restorable) {
            if (Areas.baseArea(area).equals("Maintenance") && area.contains("&dataset=")) {
                group.add(area);
            }
        }
        List<String> ordered = new ArrayList<>(group);
        ordered.sort(Comparator.comparingInt(Restore::maintenanceRank));
        List<String> out = new ArrayList<>(rest.subList(0, first));
        out.addAll(ordered);
        out.addAll(rest.subList(first, rest.size()));
        return out;
    }

    /** {@link #restore(CommanderConfig, Path, Collection, Consumer, boolean, Rules)} of every area, no merge. */
    public static RestoreReport restore(CommanderConfig client, Path snapshot, Consumer<String> say) {
        return restore(client, snapshot, null, say, false, Rules.DEFAULT);
    }

    /**
     * {@code u<area>} with the snapshot's view for every restorable area that differs, then views it again and
     * confirms it matches. Excluded areas are never sent. Areas go in the base import's order, and those still
     * different or failed get one more pass ({@link #RESTORE_PASSES}). {@code merge} (a snapshot of another build):
     * each area is viewed first and {@link Merge#mergeDocument}'s result is sent, not the snapshot's view.
     *
     * @throws IllegalArgumentException {@code snapshot} holds no manifest
     */
    public static RestoreReport restore(CommanderConfig client, Path snapshot, Collection<String> only,
            Consumer<String> say, boolean merge, Rules rules) {
        Map<String, Object> manifest = manifestOf(snapshot);
        RestoreReport report = new RestoreReport();
        List<AreaDiff> diffs = diff(client, snapshot, manifest, only, false, merge, rules);
        report.checked = diffs.size();
        for (AreaDiff d : diffs) {
            if (d.status().equals("failed")) {
                report.failed.put(d.area(), d.detail().get(0));
                say.accept("snapshot: FAILED " + d.area() + ": " + d.detail().get(0));
            }
        }
        for (AreaDiff d : diffs) {
            if (!d.notes().isEmpty() && !d.status().equals("failed")) {
                String more = d.notes().size() > 6 ? "; and " + (d.notes().size() - 6) + " more" : "";
                say.accept("snapshot: " + d.area() + ": " + String.join("; ", d.notes().subList(0,
                        Math.min(6, d.notes().size()))) + more);
            }
        }
        List<String> pending = new ArrayList<>();
        List<String> restorable = new ArrayList<>();
        for (AreaDiff d : diffs) {
            if (d.status().equals("different")) {
                pending.add(d.area());
            }
            if ((d.status().equals("different") || d.status().equals("same")) && Areas.exclusion(d.area()) == null) {
                restorable.add(d.area());
            }
        }
        pending = withMaintenanceGroup(pending, restorable);
        if (!pending.isEmpty()) {
            say.accept("snapshot: " + pending.size() + " of " + diffs.size() + " areas differ: "
                    + String.join(", ", pending));
        }
        Map<String, Object> entries = areasOf(manifest);
        for (int attempt = 1; attempt <= RESTORE_PASSES; attempt++) {
            List<String> left = new ArrayList<>();
            for (String area : pending) {
                if (Areas.exclusion(area) != null) { // never, whatever the caller passed
                    continue;
                }
                String saved;
                try {
                    saved = read(snapshot, entries, area);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Compared compared;
                try {
                    // Another build's snapshot is merged into the live view first. Records only the live view has
                    // survive a merge, so an emptied area is not empty.
                    String document = merge
                            ? Merge.mergeDocument(area, saved, Capture.viewArea(client, area), rules).xml()
                            : saved;
                    for (String payload : Restores.restoreDocuments(area, document)) {
                        client.updateConfiguration(area, payload);
                    }
                    String now = Capture.viewArea(client, area);
                    compared = compare(area, saved, now, merge, rules);
                } catch (DeviceError | Merge.NotMergeable e) {
                    report.failed.put(area, String.valueOf(e.getMessage()));
                    report.stillDifferent.remove(area);
                    left.add(area);
                    say.accept("snapshot: pass " + attempt + ": FAILED " + area + ": " + e.getMessage());
                    continue;
                }
                if (compared.same()) {
                    report.restored.add(area);
                    report.failed.remove(area);
                    report.stillDifferent.remove(area);
                    say.accept("snapshot: restored " + area);
                } else {
                    report.stillDifferent.put(area, compared.lines());
                    report.failed.remove(area);
                    left.add(area);
                    // One line: the run log keeps only a message's first line.
                    say.accept("snapshot: pass " + attempt + ": " + area + " still differs: "
                            + String.join("; ", compared.lines()));
                }
            }
            pending = left;
            if (pending.isEmpty()) {
                break;
            }
        }
        say.accept(report.summary());
        return report;
    }

    // -- step 4 ---------------------------------------------------------------------------------

    /**
     * Step 4's restore: the snapshot {@link Snapshots#chooseSnapshot} picks for the Commander's build.
     *
     * <p>Null, with one line said, when there is nothing to restore from: no snapshot under {@code root} at all
     * (checked first, so an uncaptured controller reads no SSH), the base level unreadable, or no snapshot this build
     * may use. Never raises for those. Another build's snapshot is merge-restored, and says so.
     *
     * @param suiteinfo  the Commander's {@code suiteinfo.dat} (over SSH); called only when a snapshot exists
     * @param baseFolder petroapps {@code base/}, to say when it has changed since the capture; null skips that
     */
    public static RestoreReport restoreForRig(Path root, Supplier<Map<String, String>> suiteinfo,
            CommanderConfig client, Path baseFolder, Consumer<String> say, Rules rules) {
        if (Snapshots.allSnapshots(root).isEmpty()) {
            say.accept("no Commander snapshot under " + root + ": nothing restored (docs/commander-snapshot.md)");
            return null;
        }
        Map<String, String> suite;
        String level;
        try {
            suite = suiteinfo.get();
            level = suite.get("Base_Level");
            if (level == null) {
                throw new IllegalArgumentException("'Base_Level'");
            }
        } catch (RuntimeException e) { // any failure to read it means: no snapshot to choose
            say.accept("Commander snapshot not restored: its Base_Level could not be read (" + e.getMessage() + ")");
            return null;
        }
        Snapshots.Choice choice = Snapshots.chooseSnapshot(Snapshots.snapshotDir(level, root), suite);
        if (choice == null) {
            say.accept("no Commander snapshot for Base_Level " + level + " at or before this build under " + root
                    + ": nothing restored");
            return null;
        }
        say.accept("restoring from the Commander snapshot " + choice.directory() + " (captured "
                + choice.manifest().get("captured_at") + "): " + choice.note());
        Object capturedBase = choice.manifest().get("petroapps_base_digest");
        if (baseFolder != null && capturedBase instanceof String digest && !digest.isEmpty()
                && !digest.equals(Capture.folderDigest(baseFolder))) {
            say.accept("note: petroapps " + baseFolder + " has changed since this snapshot was captured; "
                    + "base is imported after the restore, so its files still win");
        }
        return restore(client, choice.directory(), null, say, !choice.exact(), rules);
    }
}
