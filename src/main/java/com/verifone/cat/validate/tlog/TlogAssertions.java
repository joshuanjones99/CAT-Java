package com.verifone.cat.validate.tlog;

import java.nio.file.Files;
import java.nio.file.Path;

import org.w3c.dom.Element;

import com.verifone.cat.validate.ReceiptAssertions;
import com.verifone.cat.validate.SnapshotSession;
import com.verifone.cat.validate.ValidationFailedException;

/**
 * {@code assertTlog}: a captured TLog against its reference, structurally (DESIGN.md §5.1, §5.4).
 * The Python CAT's {@code petrotest.validate.assertions.assert_tlog}.
 *
 * <p>The baseline is PATS's own {@code -Tlog.xml}. Because the comparison is structural and the rule
 * file already absorbs every volatile field, PATS's raw reference validates a CAT capture unchanged.
 * So there is one TLog reference per case and both harnesses read it. Update mode will not overwrite
 * one that exists: rewriting it would edit the file PATS validates against, silently. A case with no
 * reference yet still gets one written.
 *
 * <p>Not ported: the report hooks ({@code record_tlog}, {@code validation_checked}). A caller reads the
 * returned {@link ReceiptAssertions.Verdict} or the thrown message instead.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public final class TlogAssertions {

    /** The TLog reference's filename tail, in PATS's {@code <ID>-<Kind>.<ext>} form. */
    public static final String TLOG_SUFFIX = "-Tlog.xml";

    private TlogAssertions() {}

    /** {@link #assertTlog(Object, SnapshotSession, String, TlogOptions)} with the CAT defaults and no name. */
    public static ReceiptAssertions.Verdict assertTlog(Object actual, SnapshotSession snapshot) {
        return assertTlog(actual, snapshot, null, null);
    }

    /**
     * Asserts that a captured TLog matches its reference.
     *
     * @param actual  XML text, bytes, a {@link Path}, a parsed {@link Element}, or a
     *                {@link ReceiptAssertions.TextSource}
     * @param name    the variant for a second TLog in one test, or {@code null}
     * @param options what to ignore and how to compare; {@code null} means {@link TlogOptions#catDefaults()}
     * @return what was compared, when it matched (or was written in update mode)
     * @throws com.verifone.cat.validate.SnapshotMissingException if no reference exists and not updating
     * @throws ValidationFailedException if the TLog differs from its reference
     */
    public static ReceiptAssertions.Verdict assertTlog(Object actual, SnapshotSession snapshot, String name,
            TlogOptions options) {
        TlogOptions opts = options == null ? TlogOptions.catDefaults() : options;
        Object xml = asXml(actual);
        String canonical = Tlog.canonicalise(xml, opts);
        String fileName = snapshot.path(TLOG_SUFFIX, name).getFileName().toString();

        if (snapshot.update()) {
            Path existing = snapshot.path(TLOG_SUFFIX, name);
            if (Files.isRegularFile(existing)) {
                snapshot.claim(TLOG_SUFFIX, name);
                snapshot.keep(existing);
            } else {
                snapshot.record(TLOG_SUFFIX, canonical, name);
            }
            return new ReceiptAssertions.Verdict(fileName, canonical, canonical);
        }

        Path path = snapshot.path(TLOG_SUFFIX, name);
        String baseline = snapshot.read(TLOG_SUFFIX, name);
        if (baseline == null) {
            throw snapshot.missing(TLOG_SUFFIX, name);
        }
        snapshot.claim(TLOG_SUFFIX, name);

        Tlog.Diff diff = Tlog.compare(baseline, canonical, opts);
        if (diff.ok()) {
            return new ReceiptAssertions.Verdict(fileName, baseline, canonical);
        }
        throw new ValidationFailedException("TLog does not match its reference " + path + " (DESIGN.md 5.4).\n"
                + diff.describe() + "\n"
                + "Paths are element paths in the parsed TLog, not line numbers: a reordered or newly added "
                + "element shows as its own entry rather than a whole-file mismatch. This reference is PATS's "
                + "too, and update mode will not rewrite it: if the change is intentional, edit it deliberately, "
                + "as a change to what both harnesses assert.");
    }

    private static Object asXml(Object value) {
        if (value instanceof String || value instanceof byte[] || value instanceof Path || value instanceof Element) {
            return value;
        }
        if (value instanceof ReceiptAssertions.TextSource source) {
            return source.text();
        }
        throw new IllegalArgumentException("cannot read TLog XML from "
                + (value == null ? "null" : value.getClass().getSimpleName())
                + ". Pass XML text, bytes, a Path, or a parsed Element.");
    }
}
