package com.verifone.cat.build;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.verifone.cat.devices.commander.CommanderSuiteInfo;

/**
 * Which downloaded build is on the Commander, by commit hash, or "unknown": a port of
 * {@code petrotest.build.installed}.
 *
 * <p>The Commander's {@code suiteinfo.dat} names no Solutions number (two builds on one base level both say
 * {@code Base_Level =057.02.00}); its {@code Commit_Hash} differs. Every downloaded build's {@code overlay.zip} carries
 * the same file ({@code install/update_foundation.<dist>.psc.suiteinfo.*.tgz} holding {@code suiteinfo.vpk}, a gzipped
 * tar holding {@code config/suiteinfo.dat}), so the build on the rig is the one whose overlay holds the same hash. The
 * answer is a name ({@code Solutions1082}, {@code master-268}) or "" when the Commander cannot be read or no downloaded
 * build carries its hash; never a guess (rig owner, 2026-10-07).
 *
 * <p>The tar reading is a minimal ustar reader (names, sizes, regular files), so no dependency is added.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the reader is the Commander's {@code cat}, tested over fakes only.
 */
public final class Installed {

    /** {@code Solutions<n>}, and every other branch's {@code <branch>-<n>}. */
    private static final Pattern BUILD_DIR = Pattern.compile("^(?:Solutions\\d+|.+-\\d+)$");
    private static final Pattern SUITEINFO_MEMBER =
            Pattern.compile("^install/update_foundation\\.(?<dist>[^.]+)\\.psc\\.suiteinfo\\..*\\.tgz$");

    /** A build's name and the one line the step log says about it. */
    public record Identification(String name, String why) {}

    private Installed() {}

    /** Build name to its {@code overlay.zip}, flat ({@code Solutions1082/overlay.zip}) or under the dist folder. */
    public static Map<String, Path> downloadedOverlays(String dist, Path root) {
        Map<String, Path> found = new LinkedHashMap<>();
        for (Path child : sortedChildren(root)) {
            String name = child.getFileName().toString();
            if (!Files.isDirectory(child) || !BUILD_DIR.matcher(name).matches()) {
                continue;
            }
            Path flat = child.resolve(BuildDiscovery.OVERLAY);
            if (Files.isRegularFile(flat)) {
                found.put(name, flat);
                continue;
            }
            for (Path sub : sortedChildren(child)) {
                if (Files.isDirectory(sub) && sub.getFileName().toString().equalsIgnoreCase(dist)
                        && Files.isRegularFile(sub.resolve(BuildDiscovery.OVERLAY))) {
                    found.put(name, sub.resolve(BuildDiscovery.OVERLAY));
                    break;
                }
            }
        }
        return found;
    }

    /** {@code Commit_Hash} from the suiteinfo inside {@code overlay}, or "" if it has none or cannot be read. */
    public static String overlayCommitHash(Path overlay, String dist) {
        try (ZipFile zip = new ZipFile(overlay.toFile())) {
            ZipEntry member = zip.stream().filter(entry -> {
                Matcher m = SUITEINFO_MEMBER.matcher(entry.getName());
                return m.matches() && m.group("dist").equalsIgnoreCase(dist);
            }).findFirst().orElse(null);
            if (member == null) {
                return "";
            }
            byte[] tgz;
            try (InputStream in = zip.getInputStream(member)) {
                tgz = in.readAllBytes();
            }
            Optional<byte[]> vpk = tarMember(tgz, "suiteinfo.vpk");
            if (vpk.isEmpty()) {
                return "";
            }
            Optional<byte[]> dat = tarMember(vpk.get(), "suiteinfo.dat");
            if (dat.isEmpty()) {
                return "";
            }
            return CommanderSuiteInfo.parse(new String(dat.get(), StandardCharsets.UTF_8)).getOrDefault("Commit_Hash", "");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** The downloaded build whose overlay carries {@code installed}'s {@code Commit_Hash}, or "". */
    public static String identify(Map<String, String> installed, String dist, Path root) {
        String wanted = installed.getOrDefault("Commit_Hash", "").strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, Path> entry : downloadedOverlays(dist, root).entrySet()) {
            if (overlayCommitHash(entry.getValue(), dist).toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getKey();
            }
        }
        return "";
    }

    /** The build on the Commander {@code suiteinfo} reads, and why. Never throws. */
    public static Identification installedBuildName(Supplier<Map<String, String>> suiteinfo, String dist, Path root) {
        Map<String, String> fields;
        try {
            fields = suiteinfo.get();
        } catch (RuntimeException e) {
            return new Identification("", "could not read the Commander's suiteinfo.dat ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        String commit = fields.getOrDefault("Commit_Hash", "");
        String shortCommit = commit.substring(0, Math.min(12, commit.length()));
        String built = fields.getOrDefault("Timestamp", "?");
        String name = identify(fields, dist, root);
        if (!name.isEmpty()) {
            return new Identification(name, name + " (Commit_Hash " + shortCommit + ", built " + built + ")");
        }
        return new Identification("", "not one of the downloaded builds under " + root + " (Commit_Hash "
                + (shortCommit.isEmpty() ? "?" : shortCommit) + ", built " + built + ")");
    }

    /** The first regular file of a (possibly gzipped) tar whose name ends with {@code suffix}. */
    static Optional<byte[]> tarMember(byte[] archive, String suffix) throws IOException {
        byte[] tar = isGzip(archive) ? gunzip(archive) : archive;
        int at = 0;
        while (at + 512 <= tar.length) {
            String name = field(tar, at, 100);
            if (name.isEmpty()) {
                return Optional.empty(); // the end-of-archive blocks
            }
            String prefix = "ustar".equals(field(tar, at + 257, 5)) ? field(tar, at + 345, 155) : "";
            String full = prefix.isEmpty() ? name : prefix + "/" + name;
            long size = octal(tar, at + 124, 12);
            char type = (char) tar[at + 156];
            int data = at + 512;
            if (data + size > tar.length) {
                throw new IOException("truncated tar member " + full);
            }
            if ((type == '0' || type == 0) && full.endsWith(suffix)) {
                byte[] out = new byte[(int) size];
                System.arraycopy(tar, data, out, 0, (int) size);
                return Optional.of(out);
            }
            at = data + (int) ((size + 511) / 512 * 512);
        }
        return Optional.empty();
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }

    private static String field(byte[] block, int offset, int length) {
        int end = offset;
        while (end < offset + length && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] block, int offset, int length) throws IOException {
        String text = field(block, offset, length).strip();
        try {
            return text.isEmpty() ? 0 : Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("bad tar size field: " + text, e);
        }
    }

    private static List<Path> sortedChildren(Path dir) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(children::add);
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
        children.sort(null);
        return children;
    }
}
