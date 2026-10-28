package com.verifone.cat.sims;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * CAT's copy of the BUYPASS host simulator, with CAPK expiries that have not passed. Port of the
 * Python's {@code cat.sims.capk}, which is the specification.
 *
 * <p>The Commander takes its EMV CA public keys from the Buypass host: every {@code EMV2KEY}
 * download rewrites {@code CAPKTable.xml} and {@code OUTSIDE_CAPKTable.xml} from what the host
 * sends. ArbitraryHostSim v5.00 sends a key file compiled into {@code PktTemplates.class}, dated
 * 2020 to 2030, so most keys read expired. CAT runs a copy of the jar whose built-in key file
 * carries fixed future expiries, staggered {@link #STEP_MONTHS} months apart from
 * {@link #FIRST_EXPIRY} (rig owner, 2026-10-06). Only the eight-digit {@code MMDDYYYY} dates
 * change, so every string keeps its length. The same class's {@code <TTCutTime>} is moved to
 * {@link #CUT_TIME} so a forced AEOD lands overnight (Python HANDOFF.md §1.137). PATS's own jar
 * is never written.
 *
 * <p>Deviation from the Python: the rebuilt jar's entries (names, order, compression method,
 * times, extra fields, comments and bytes) are the source's, the patched class aside, but the
 * zip container is written by {@link ZipOutputStream} rather than Python's {@code zipfile}, so
 * the file's own bytes differ from the Python's (Java writes data descriptors after deflated
 * entries), and each entry keeps the source's external attributes (0 in PATS's jar) where Python's
 * {@code writestr} sets {@code 0o600 << 16}. Checked on 2026-10-28 against a copy of PATS's
 * ArbitraryHostSim_v5.00.jar: all 30 entries' bytes, order, method, time, extra and CRC equal the
 * Python's {@code capk.patched} output; only {@code PktTemplates.class} differs from the source,
 * same length, with the 20 expected dates and one {@code 0330} cut time.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's jar was run against the lab rig
 * (Python HANDOFF.md §1.103); this port's bytes were compared with the Python's off-rig only.
 */
public final class Capk {

    /** The class whose string constants hold the key file and the download packet. */
    public static final String CLASS = "PktTemplates.class";

    /** The first key's expiry year. */
    public static final int FIRST_EXPIRY_YEAR = 2029;
    /** The first key's expiry month. */
    public static final int FIRST_EXPIRY_MONTH = 3;
    /** The first key's expiry, (year, month). */
    public static final YearMonth FIRST_EXPIRY = YearMonth.of(FIRST_EXPIRY_YEAR, FIRST_EXPIRY_MONTH);
    /** The gap between consecutive keys' expiries, in months. */
    public static final int STEP_MONTHS = 3;

    /** The host cut time CAT's jar sends, {@code HHMM}. */
    public static final String CUT_TIME = "0330";

    /** One key's expiry: {@code MMDDYYYY} right before its {@code ,01,01,<RID>,} fields. */
    static final Pattern DATE = Pattern.compile("\\d{8}(?=,\\d\\d,\\d\\d,A\\d{9},)");
    static final Pattern CUT = Pattern.compile("(?<=<TTCutTime>)\\d{4}(?=</TTCutTime>)");
    private static final String KEY_FILE = "CA_KEYS,";

    private Capk() {
    }

    /** Key {@code n}'s expiry as {@code MMDDYYYY}: the last day of its month. */
    public static String expiry(int n) {
        YearMonth month = FIRST_EXPIRY.plusMonths((long) n * STEP_MONTHS);
        return String.format("%02d%02d%04d", month.getMonthValue(), month.lengthOfMonth(), month.getYear());
    }

    /**
     * {@code data} with each key file's expiries replaced, counting from its {@code CA_KEYS,}.
     * Counting restarts at each key file, so the download packet's two keys get the same dates as
     * the first two of the key file they are copied from.
     *
     * @throws IllegalArgumentException when there is no key file, or one has no dates in the layout
     */
    public static byte[] redate(byte[] data) {
        String text = latin1(data);
        String[] parts = text.split(Pattern.quote(KEY_FILE), -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("no " + KEY_FILE + " key file in " + CLASS);
        }
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            Matcher m = DATE.matcher(parts[i]);
            if (!m.find()) {
                throw new IllegalArgumentException(
                        "a key file in " + CLASS + " has no expiry dates in the expected layout");
            }
            m.reset();
            StringBuilder part = new StringBuilder();
            int n = 0;
            while (m.find()) {
                m.appendReplacement(part, expiry(n++));
            }
            m.appendTail(part);
            out.append(KEY_FILE).append(part);
        }
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * {@code data} with its one {@code TTCutTime} replaced by {@link #CUT_TIME}, same length.
     *
     * @throws IllegalArgumentException unless there is exactly one
     */
    public static byte[] recut(byte[] data) {
        Matcher m = CUT.matcher(latin1(data));
        int count = 0;
        while (m.find()) {
            count++;
        }
        if (count != 1) {
            throw new IllegalArgumentException("expected one <TTCutTime> in " + CLASS + ", found " + count);
        }
        return m.replaceAll(CUT_TIME).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * The bytes of the {@code source} jar with {@link #CLASS} re-dated and re-cut; every other entry
     * as it was, in the same order, with the same compression method.
     *
     * @throws IllegalArgumentException when the jar has no {@link #CLASS}, or the class is not in
     *     the expected layout
     * @throws IOException when the source cannot be read or is not a zip
     */
    public static byte[] patched(Path source) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipFile src = new ZipFile(source.toFile())) {
            if (src.getEntry(CLASS) == null) {
                throw new IllegalArgumentException(source.getFileName() + " has no " + CLASS);
            }
            try (ZipOutputStream dst = new ZipOutputStream(buffer)) {
                if (src.getComment() != null) {
                    dst.setComment(src.getComment());
                }
                Enumeration<? extends ZipEntry> entries = src.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    byte[] data;
                    try (InputStream in = src.getInputStream(entry)) {
                        data = in.readAllBytes();
                    }
                    if (entry.getName().equals(CLASS)) {
                        data = recut(redate(data));
                    }
                    ZipEntry out = new ZipEntry(entry);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    out.setSize(data.length);
                    out.setCrc(crc.getValue());
                    out.setCompressedSize(entry.getMethod() == ZipEntry.STORED ? data.length : -1);
                    dst.putNextEntry(out);
                    dst.write(data);
                    dst.closeEntry();
                }
            }
        }
        return buffer.toByteArray();
    }

    /**
     * Writes {@code target} from {@code source} unless it already holds those bytes. A running copy
     * of the target holds it open, so a stale one cannot be replaced under it: that is an
     * {@link IOException} for the caller to report.
     *
     * @return true if the target was written
     */
    public static boolean build(Path source, Path target) throws IOException {
        byte[] data = patched(source);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), data)) {
            return false;
        }
        Path staging = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(staging, data);
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    private static String latin1(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1);
    }
}
