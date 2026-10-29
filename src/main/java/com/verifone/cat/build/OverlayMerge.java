package com.verifone.cat.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.verifone.cat.launch.UpgradeMode;

/**
 * Merges a foundation {@code overlay.zip} and a viper (SOS) {@code overlay.zip} into one overlay, as Verifone's
 * {@code VerifoneOverlayMergerAppV1.00.01.jar} does. A port of the Python's {@code petrotest.build.overlay_merge},
 * itself a port of that jar's {@code OverlayMergeService}, checked against the jar's own output.
 *
 * <p><b>The algorithm</b>, in output order:
 * <ol>
 *   <li>Every foundation entry, in central-directory order, except the list files (step 3). A foundation
 *       {@code update_pearl-common_appdkfs_v<V>_dev.tgz}, {@code ..._common_installer_...},
 *       {@code ..._controller_appfs_...} or {@code ..._pos_appfs_...} (ASCII case-insensitive) is written under its
 *       foundation name ({@code update_foundation.base.{common.appdkfs,common.installer,psc.appfs,pos.appfs}.V<V>.dev.tgz},
 *       same folder, {@code <V>} trimmed, {@code 1.00.00} if blank); an entry that already has one of those names is
 *       dropped for the renamed one.</li>
 *   <li>Every viper entry, in order, except list files and any name already written: the foundation copy wins
 *       ({@code COLLISION}).</li>
 *   <li>{@code FileList.txt} and {@code ModuleList.txt} (any folder, file name ignoring case) are never copied; each
 *       is written once, after the copied entries, at the foundation's path for it (the viper's if only it has one):
 *       the foundation text with step 1's renames, then the viper text, then (Auto Upgrade, {@code FileList.txt}
 *       only) the transition file list, each with CRLF endings and a final CRLF.</li>
 *   <li>Auto Upgrade only: the six transition {@code .tgz} files as {@code install/<name>}, in
 *       {@code transition-tgzs.txt} order; a taken name becomes {@code <stem>__extra<n>.tgz}.</li>
 * </ol>
 * Copied entries keep their method (stored or deflated), DOS time, extra fields and comment; written entries are
 * deflated and stamped with the current time, as the jar's are. The jar's quirks are kept (the merged
 * {@code FileList.txt} repeats lines both inputs list; the transition list names two packages the jar never adds).
 *
 * <p><b>Deviations.</b> As the Python: the output goes to {@code <dest>.part} and is moved into place only when
 * whole; a {@code dest} that is an input is refused. CAT-Java's own: the jar's six transition packages and its two
 * index files are Verifone's, so CAT-Java does not ship them; an Auto Upgrade merge reads them from the Python CAT's
 * checkout ({@link #transitionDir}: {@code $CAT_PYTHON_ROOT\src\petrotest\build\overlay_transition}) and refuses to
 * merge, before writing anything, when that folder is not there (the Python always has its vendored copy). A PSI merge
 * needs none of them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no merged overlay has been installed on a Commander by CAT. Tested
 * against the jar's output for the Python CAT's synthetic fixture.
 */
public final class OverlayMerge {

    public static final String FILELIST_NAME = "FileList.txt";
    public static final String MODULELIST_NAME = "ModuleList.txt";
    /** Where the transition packages go inside the merged overlay. */
    public static final String TRANSITION_FOLDER = "install/";
    /** The version a renamed package gets when its {@code _v<version>_} is blank. */
    public static final String FALLBACK_VERSION = "1.00.00";
    static final String TRANSITION_INDEX = "transition-tgzs.txt";
    static final String TRANSITION_FILELIST = "filelist.txt";
    /** The jar asks for this too, and does not ship one. */
    static final String TRANSITION_MODULELIST = "modulelist.txt";

    /** Which installer the merged overlay is for: the jar's {@code MergeOptions.PackageType}. */
    public enum PackageType {
        /** {@code Auto_Upgrade.jar} (classic). Adds the transition packages and file list. The jar's default. */
        AUTO_UPGRADE,
        /** {@code Petro_Suite_Installer.jar} (new_install). No transition packages. */
        PETRO_SUITE_INSTALLER;

        /** PSI for {@code new_install}, else Auto Upgrade (the nightly agent merges VHQ that way too). */
        public static PackageType forMode(UpgradeMode mode) {
            return mode == UpgradeMode.NEW_INSTALL ? PETRO_SUITE_INSTALLER : AUTO_UPGRADE;
        }
    }

    /** An input overlay cannot be merged (the jar would have failed on it too), or the transitions are missing. */
    public static final class OverlayMergeError extends BuildException {
        private static final long serialVersionUID = 1L;

        public OverlayMergeError(String message) {
            super(message);
        }
    }

    /** The merge, as {@link CommanderBuildFetch} calls it; a test passes its own. */
    @FunctionalInterface
    public interface Merger {
        Path merge(Path foundationZip, Path viperZip, Path destZip, PackageType type, Consumer<String> log)
                throws IOException;
    }

    /** The jar's four rename patterns ({@code Pattern.CASE_INSENSITIVE}, ASCII-only), each with its target. */
    private static final List<Map.Entry<Pattern, String>> RENAME_RULES = List.of(
            rule("common_appdkfs", "update_foundation.base.common.appdkfs.V%s.dev.tgz"),
            rule("common_installer", "update_foundation.base.common.installer.V%s.dev.tgz"),
            rule("controller_appfs", "update_foundation.base.psc.appfs.V%s.dev.tgz"),
            rule("pos_appfs", "update_foundation.base.pos.appfs.V%s.dev.tgz"));

    private static Map.Entry<Pattern, String> rule(String source, String target) {
        return Map.entry(Pattern.compile("update_pearl-" + source + "_v(.+)_dev\\.tgz", Pattern.CASE_INSENSITIVE),
                target);
    }

    private final Path transitionDir;

    /** @param transitionDir the jar's {@code transition/} resources; only an Auto Upgrade merge reads it */
    public OverlayMerge(Path transitionDir) {
        this.transitionDir = transitionDir;
    }

    /** {@code $CAT_PYTHON_ROOT\src\petrotest\build\overlay_transition}, the Python CAT's vendored copy. */
    public static Path transitionDir(Map<String, String> env) {
        String root = env == null ? null : env.get("CAT_PYTHON_ROOT");
        Path python = root == null || root.isBlank() ? StoredArtifactoryCredentials.DEFAULT_PYTHON_ROOT
                : Path.of(root);
        return python.resolve("src").resolve("petrotest").resolve("build").resolve("overlay_transition");
    }

    /** {@link #merge} as a {@link Merger}. */
    public Merger merger() {
        return this::merge;
    }

    // --- java.lang.String / java.util.zip semantics the jar relies on ---------------------------------------------

    /** {@code ZipUtils.normalizeEntryName}: backslashes to slashes. */
    static String normalize(String name) {
        return name.replace('\\', '/');
    }

    /** {@code ZipUtils.fileNameOnly}: the text after the last slash. */
    static String fileNameOnly(String name) {
        String n = normalize(name);
        return n.substring(n.lastIndexOf('/') + 1);
    }

    /** {@code ZipUtils.toCrlfWithFinalNewline}. */
    static String toCrlf(String text) {
        String lf = (text == null ? "" : text).replace("\r\n", "\n").replace("\r", "\n");
        String crlf = lf.replace("\n", "\r\n");
        return crlf.endsWith("\r\n") ? crlf : crlf + "\r\n";
    }

    /** {@code ZipUtils.parseIndexLines}: trimmed, non-blank lines not starting with {@code #}. */
    static List<String> parseIndexLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String line : text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                lines.add(t);
            }
        }
        return lines;
    }

    /** {@code ZipUtils.addExtraSuffix}: {@code a.tgz} becomes {@code a__extra<n>.tgz}. */
    static String addExtraSuffix(String fileName, int n) {
        int dot = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".tgz");
        if (dot > 0) {
            return fileName.substring(0, dot) + "__extra" + n + fileName.substring(dot);
        }
        return fileName + "__extra" + n;
    }

    // --- FoundationRenamePlanner ------------------------------------------------------------------------------------

    /**
     * {@code FoundationRenamePlan}: which foundation entries are renamed, and to what.
     *
     * @param entries      normalized entry name to its new name (same folder)
     * @param destinations every new name
     * @param bases        old file name to new file name, applied to the foundation's list files
     */
    public record RenamePlan(Map<String, String> entries, Set<String> destinations, Map<String, String> bases) {

        String rename(String name) {
            return entries.getOrDefault(name, name);
        }

        /** An existing entry with a rename's destination name, dropped for the renamed one. */
        boolean replacedByRename(String name) {
            return destinations.contains(name) && !entries.containsKey(name);
        }

        /** Every old file name in {@code text} replaced by its new one, as plain substrings, longest first. */
        String applyToText(String text) {
            if (text == null || bases.isEmpty()) {
                return text;
            }
            List<Map.Entry<String, String>> ordered = new ArrayList<>(bases.entrySet());
            ordered.sort((a, b) -> a.getKey().length() != b.getKey().length()
                    ? Integer.compare(b.getKey().length(), a.getKey().length())
                    : a.getKey().compareTo(b.getKey()));
            for (Map.Entry<String, String> base : ordered) {
                text = text.replace(base.getKey(), base.getValue());
            }
            return text;
        }
    }

    /** {@code FoundationRenamePlanner.buildPlan} over a foundation zip's raw entry names. */
    static RenamePlan planFoundationRenames(Iterable<String> names, Consumer<String> log) {
        Map<String, String> entries = new LinkedHashMap<>();
        Map<String, String> bases = new LinkedHashMap<>();
        for (String raw : names) {
            if (raw.endsWith("/")) {
                continue;
            }
            String src = normalize(raw);
            String base = fileNameOnly(src);
            String newBase = null;
            for (Map.Entry<Pattern, String> rule : RENAME_RULES) {
                Matcher match = rule.getKey().matcher(base);
                if (match.matches()) {
                    String version = match.group(1).trim();
                    newBase = String.format(rule.getValue(), version.isEmpty() ? FALLBACK_VERSION : version);
                    break;
                }
            }
            if (newBase == null) {
                continue;
            }
            String folder = src.substring(0, src.lastIndexOf('/') + 1);
            entries.put(src, folder + newBase);
            bases.put(base, newBase);
            log.accept("Foundation rename planned: " + src + " -> " + folder + newBase);
        }
        if (entries.isEmpty()) {
            log.accept("Foundation rename plan: no matching TGZs found.");
        }
        return new RenamePlan(entries, new HashSet<>(entries.values()), bases);
    }

    // --- transition resources ---------------------------------------------------------------------------------------

    private String transitionText(String name) throws IOException {
        Path file = transitionDir.resolve(name);
        return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
    }

    /** The transition packages an Auto Upgrade merge adds, in the order it adds them. */
    public List<String> transitionTgzNames() throws IOException {
        return parseIndexLines(transitionText(TRANSITION_INDEX));
    }

    // --- the merge --------------------------------------------------------------------------------------------------

    /** One zip, opened, with its entries in central-directory order. */
    private record Opened(ZipFile zip, List<ZipEntry> entries) {
        static Opened of(Path path) throws IOException {
            ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
            return new Opened(zip, Collections.list(castEntries(zip.entries())));
        }

        @SuppressWarnings("unchecked")
        private static Enumeration<ZipEntry> castEntries(Enumeration<? extends ZipEntry> entries) {
            return (Enumeration<ZipEntry>) entries;
        }
    }

    /** {@code FileList.txt} or {@code ModuleList.txt}, as read from both inputs. */
    private record ListFile(String logical, String path, List<String> sources, String foundation, String viper) {}

    /** {@code ZipTextEntryHelper.findEntriesByFileName}, sorted case-insensitively. */
    private static List<String> listEntries(List<ZipEntry> entries, String wanted) {
        List<String> found = new ArrayList<>();
        for (ZipEntry entry : entries) {
            if (!entry.getName().endsWith("/") && fileNameOnly(entry.getName()).equalsIgnoreCase(wanted)) {
                found.add(normalize(entry.getName()));
            }
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        return found;
    }

    /** {@code ZipTextEntryHelper.readFirstTextIfAny}. */
    private static String firstText(Opened zip, List<String> paths, String logical, Consumer<String> log)
            throws IOException {
        if (paths.isEmpty()) {
            return null;
        }
        if (paths.size() > 1) {
            log.accept("WARNING: Multiple " + logical + " entries found in one zip. Using: " + paths.get(0));
        }
        // ZipFile.getEntry: an exact raw name, the last such entry; then the first whose normalized name matches
        // ignoring case.
        ZipEntry found = null;
        for (ZipEntry entry : zip.entries()) {
            if (entry.getName().equals(paths.get(0))) {
                found = entry;
            }
        }
        if (found == null) {
            String want = paths.get(0).toLowerCase(Locale.ROOT);
            for (ZipEntry entry : zip.entries()) {
                if (normalize(entry.getName()).toLowerCase(Locale.ROOT).equals(want)) {
                    found = entry;
                    break;
                }
            }
        }
        if (found == null) {
            return null;
        }
        try (InputStream in = zip.zip().getInputStream(found)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** {@code ZipTextEntryHelper.chooseOnePathPreferFoundation}. */
    private static String choosePath(List<String> foundation, List<String> viper, String logical,
            Consumer<String> log) {
        if (!foundation.isEmpty() && !viper.isEmpty()) {
            log.accept(logical + " found in BOTH zips. Will merge and write to Foundation path: " + foundation.get(0));
            return foundation.get(0);
        }
        if (!foundation.isEmpty()) {
            log.accept(logical + " found only in Foundation: " + foundation.get(0));
            return foundation.get(0);
        }
        if (!viper.isEmpty()) {
            log.accept(logical + " found only in Viper: " + viper.get(0));
            return viper.get(0);
        }
        log.accept(logical + " not found in either zip.");
        return null;
    }

    private static ListFile readListFile(String logical, Opened foundation, Opened viper, RenamePlan plan,
            Consumer<String> log) throws IOException {
        List<String> fPaths = listEntries(foundation.entries(), logical);
        List<String> vPaths = listEntries(viper.entries(), logical);
        String path = choosePath(fPaths, vPaths, logical, log);
        List<String> sources = new ArrayList<>(fPaths);
        sources.addAll(vPaths);
        return new ListFile(logical, path, sources, plan.applyToText(firstText(foundation, fPaths, logical, log)),
                firstText(viper, vPaths, logical, log));
    }

    /** {@code OverlayMergeService.mergeTextsForListFile}. */
    static String mergedListText(String foundation, String viper, String transition, boolean useTransition,
            String logical, Consumer<String> log) {
        List<String> parts = new ArrayList<>();
        for (String text : new String[] {foundation, viper}) {
            if (text != null) {
                parts.add(toCrlf(text));
            }
        }
        if (useTransition && transition != null) {
            parts.add(toCrlf(transition));
            log.accept("Appended Transition " + logical + " content.");
        }
        if (parts.isEmpty()) {
            log.accept("WARNING: " + logical + " chosen but no content found; writing empty file.");
            return "\r\n";
        }
        return toCrlf(String.join("", parts));
    }

    /** {@code OverlayMergeService.writeEntryStreaming} with {@code ZipUtils.cloneEntryForOutput}. */
    private static void copyEntry(ZipFile src, ZipEntry info, ZipOutputStream out, String name) throws IOException {
        int method = info.getMethod();
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            throw new OverlayMergeError(info.getName() + ": compression method " + method + " is neither "
                    + "stored nor deflated (java.util.zip, and so the merger jar, refuses it too)");
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(info.getTime());
        entry.setMethod(method);
        if (info.getExtra() != null) {
            entry.setExtra(info.getExtra());
        }
        if (info.getComment() != null) {
            entry.setComment(info.getComment());
        }
        if (method == ZipEntry.STORED) {
            entry.setSize(info.getSize());
            entry.setCompressedSize(info.getSize());
            entry.setCrc(info.getCrc());
        }
        out.putNextEntry(entry);
        if (!info.isDirectory()) {
            try (InputStream in = src.getInputStream(info)) {
                in.transferTo(out);
            }
        }
        out.closeEntry();
    }

    private static void writeNew(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    /** {@code copyFoundationEntriesWithRename}; returns how many entries it wrote. */
    private static int copyFoundation(Opened fzip, ZipOutputStream out, RenamePlan plan, Set<String> skip,
            Set<String> added, Consumer<String> log) throws IOException {
        int written = 0;
        // Each entry is read as it is enumerated: ZipFile reads the entry it last enumerated by position, where a
        // lookup by name would find the last of two entries with one name (the jar copies the first).
        for (Enumeration<? extends ZipEntry> all = fzip.zip().entries(); all.hasMoreElements();) {
            ZipEntry info = all.nextElement();
            String name = normalize(info.getName());
            if (skip.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (plan.replacedByRename(name)) {
                log.accept("Skipping existing Foundation entry (will be replaced by rename): " + name);
                continue;
            }
            name = plan.rename(name);
            if (added.contains(name)) {
                continue;
            }
            copyEntry(fzip.zip(), info, out, name);
            added.add(name);
            written++;
        }
        return written;
    }

    /** {@code copyZipEntriesWithCollisionPolicy}; returns {written, skipped as collisions}. */
    private static int[] copyViper(Opened vzip, ZipOutputStream out, Set<String> skip, Set<String> added,
            Consumer<String> log) throws IOException {
        int written = 0;
        int collisions = 0;
        for (Enumeration<? extends ZipEntry> all = vzip.zip().entries(); all.hasMoreElements();) {
            ZipEntry info = all.nextElement();
            String name = normalize(info.getName());
            if (skip.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (added.contains(name)) {
                log.accept("COLLISION: '" + name + "' already exists. Policy=Prefer Foundation -> SKIP Viper");
                collisions++;
                continue;
            }
            copyEntry(vzip.zip(), info, out, name);
            added.add(name);
            written++;
        }
        return new int[] {written, collisions};
    }

    /** {@code writeMergedFileList} / {@code writeMergedModuleList}. */
    private static void writeListFile(ZipOutputStream out, ListFile lists, String transition, boolean useTransition,
            Set<String> added, Consumer<String> log) throws IOException {
        if (lists.path() == null) {
            log.accept("No " + lists.logical() + " to write.");
            return;
        }
        String text = mergedListText(lists.foundation(), lists.viper(), transition, useTransition, lists.logical(),
                log);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writeNew(out, lists.path(), data);
        added.add(lists.path());
        log.accept("Wrote merged text file: " + lists.path() + " (" + data.length + " bytes)");
    }

    /** One step of {@code addTransitionTgzs}. */
    private void addTransitionTgz(ZipOutputStream out, String base, Set<String> added, Consumer<String> log)
            throws IOException {
        String name = TRANSITION_FOLDER + base;
        if (added.contains(name)) {
            int n = 1;
            String renamed;
            while (added.contains(renamed = TRANSITION_FOLDER + addExtraSuffix(base, n))) {
                n++;
            }
            log.accept("Transition TGZ collision at " + name + " -> renaming to " + renamed);
            name = renamed;
        }
        Path source = transitionDir.resolve(base);
        if (!Files.isRegularFile(source)) {
            log.accept("WARNING: Transition TGZ missing in resources: " + base);
            return;
        }
        log.accept("Adding Transition TGZ: overlay_transition/" + base + " -> " + name);
        writeNew(out, name, Files.readAllBytes(source));
        added.add(name);
    }

    /**
     * Merges {@code foundationZip} and {@code viperZip} into {@code destZip}, as the merger jar does.
     *
     * @param destZip where the merged overlay goes; its parent is created, and an existing file there is replaced
     *                only once the merge has finished
     * @param log     each event the jar would have logged
     * @return {@code destZip}
     * @throws java.io.FileNotFoundException an input is not a regular file
     * @throws IllegalArgumentException      {@code destZip} is one of the inputs
     * @throws OverlayMergeError             an entry is neither stored nor deflated, or the Auto Upgrade transitions
     *                                       are not at {@link #transitionDir}
     * @throws IOException                   an input is not a readable zip, or the write failed
     */
    public Path merge(Path foundationZip, Path viperZip, Path destZip, PackageType packageType, Consumer<String> log)
            throws IOException {
        boolean useTransition = packageType == PackageType.AUTO_UPGRADE;
        log.accept("Package type selected: " + packageType.name());
        for (Map.Entry<String, Path> input : List.of(Map.entry("Foundation", foundationZip),
                Map.entry("Viper", viperZip))) {
            if (!Files.isRegularFile(input.getValue())) {
                throw new java.io.FileNotFoundException(input.getKey() + " ZIP not found: " + input.getValue());
            }
        }
        for (Path input : List.of(foundationZip, viperZip)) {
            if (destZip.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("output " + destZip + " would overwrite input " + input);
            }
        }

        Map<String, String> transitions = new LinkedHashMap<>();
        List<String> transitionTgzs = new ArrayList<>();
        if (useTransition) {
            if (!Files.isDirectory(transitionDir)) {
                throw new OverlayMergeError("an Auto Upgrade merge adds the merger jar's transition packages, which "
                        + "CAT-Java does not ship; they are not at " + transitionDir
                        + " (set CAT_PYTHON_ROOT to the Python CAT's checkout). Nothing was written.");
            }
            transitions.put(FILELIST_NAME, transitionText(TRANSITION_FILELIST));
            transitions.put(MODULELIST_NAME, transitionText(TRANSITION_MODULELIST));
            transitionTgzs = transitionTgzNames();
            log.accept("Auto Upgrade: transition resources enabled (" + transitionTgzs.size() + " TGZs; "
                    + "FileList.txt present: " + pyBool(transitions.get(FILELIST_NAME) != null) + "; "
                    + "ModuleList.txt present: " + pyBool(transitions.get(MODULELIST_NAME) != null) + ")");
        } else {
            log.accept("Petro Suite Installer: Transition resources DISABLED.");
        }

        Path parent = destZip.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path part = destZip.resolveSibling(destZip.getFileName() + ".part");
        try {
            Opened fzip = Opened.of(foundationZip);
            try (ZipFile f = fzip.zip()) {
                Opened vzip = Opened.of(viperZip);
                try (ZipFile v = vzip.zip();
                        OutputStream file = Files.newOutputStream(part);
                        ZipOutputStream out = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
                    List<String> names = new ArrayList<>();
                    for (ZipEntry entry : fzip.entries()) {
                        names.add(entry.getName());
                    }
                    RenamePlan plan = planFoundationRenames(names, log);
                    List<ListFile> lists = new ArrayList<>();
                    for (String logical : List.of(FILELIST_NAME, MODULELIST_NAME)) {
                        lists.add(readListFile(logical, fzip, vzip, plan, log));
                    }
                    Set<String> skip = new HashSet<>();
                    for (ListFile each : lists) {
                        for (String source : each.sources()) {
                            skip.add(source.toLowerCase(Locale.ROOT));
                        }
                    }
                    Set<String> added = new HashSet<>();
                    int fromFoundation = copyFoundation(fzip, out, plan, skip, added, log);
                    int[] fromViper = copyViper(vzip, out, skip, added, log);
                    log.accept("Copied " + fromFoundation + " Foundation and " + fromViper[0] + " Viper entries ("
                            + fromViper[1] + " Viper entries skipped as collisions).");
                    for (ListFile each : lists) {
                        writeListFile(out, each, transitions.get(each.logical()), useTransition, added, log);
                    }
                    for (String base : transitionTgzs) {
                        addTransitionTgz(out, base, added, log);
                    }
                }
            }
            Files.move(part, destZip, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        log.accept("Done. Created: " + destZip);
        return destZip;
    }

    private static String pyBool(boolean value) {
        return value ? "True" : "False";
    }

    /** The CRC of {@code data}, for a test's comparison. */
    static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
