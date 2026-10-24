package com.verifone.cat.validate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code assertReceipt}: a captured receipt against its snapshot (DESIGN.md §5.1, §5.4). The
 * Python CAT's {@code petrotest.validate.assertions}, receipt half only.
 *
 * <p>The receipt is normalised by the global {@link Normalisers}, then compared with the stored
 * baseline, which is the same normalised text. If the two differ, PATS's shared rule
 * ({@link ReceiptRule}) is applied to both sides before the comparison fails, so a difference in
 * a line the rule ignores does not count. The rule is applied after an exact comparison, so an
 * exact match never depends on it.
 *
 * <p>Not ported here: the report hooks that record a receipt to the run's Validations view
 * ({@code record_receipt}, {@code receipt_checked} in the Python code). A caller that wants them
 * reads the {@link Verdict} or the thrown message, and the report wiring is a later slice.
 * The TLog half ({@code assert_tlog}) is also not here; it needs the TLog slice.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python tests are its evidence.
 */
public final class ReceiptAssertions {

    /** The receipt baseline's filename tail: CAT's own kind, in PATS's {@code <ID>-<Kind>.<ext>} form. */
    public static final String RECEIPT_SUFFIX = "-CATReceipt.txt";

    private ReceiptAssertions() {}

    /** Anything that carries its own text, such as a captured receipt from the POS. */
    public interface TextSource {
        String text();
    }

    /**
     * What a passing assertion compared: the snapshot's name, the expected text and the captured
     * text, both after normalisation and the rule. For a Validations view side by side.
     */
    public record Verdict(String snapshotName, String expected, String compared) {}

    /**
     * Coerces a capture to text. Accepts a {@link TextSource}, a {@link String}, bytes or a
     * {@link Path}. A file is read as UTF-8 with {@code \r\n} and {@code \r} folded to
     * {@code \n}, as Python's text mode does; bytes are not folded.
     */
    public static String asText(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("cannot read receipt text from null. Pass a TextSource, a String, bytes, or a Path.");
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof Path file) {
            return readUniversal(file);
        }
        if (value instanceof TextSource source) {
            return source.text();
        }
        throw new IllegalArgumentException("cannot read receipt text from " + value.getClass().getSimpleName()
                + ". Pass a TextSource, a String, bytes, or a Path.");
    }

    /** {@link #assertReceipt(Object, SnapshotSession, String, List, boolean)} with the defaults. */
    public static Verdict assertReceipt(Object actual, SnapshotSession snapshot) {
        return assertReceipt(actual, snapshot, null, Normalisers.RECEIPT_NORMALISERS, true);
    }

    /**
     * Asserts that a captured receipt matches its snapshot.
     *
     * <p>{@code normalisers} defaults to the global list. Overriding it is a suite-level decision
     * (adding {@link Normalisers#CARD_IDENTITY_NORMALISERS} where the card is not fixed), never a
     * per-test escape hatch: a per-test list is {@code -Rule.txt} again.
     *
     * <p>In update mode the normalised receipt is written as the baseline and the assertion passes.
     *
     * @param name       the variant for a second receipt in one test, or {@code null}
     * @param receiptRule whether PATS's shared rule applies to a comparison that fails outright
     * @return what was compared, when it matched
     * @throws SnapshotMissingException if no baseline exists yet
     * @throws ValidationFailedException if the receipt differs from its baseline
     */
    public static Verdict assertReceipt(Object actual, SnapshotSession snapshot, String name,
            List<Normaliser> normalisers, boolean receiptRule) {
        String captured = asText(actual);
        String normalised = Normalisers.normaliseReceipt(captured, normalisers);
        String fileName = snapshot.path(RECEIPT_SUFFIX, name).getFileName().toString();

        if (snapshot.update()) {
            snapshot.record(RECEIPT_SUFFIX, normalised, name);
            return new Verdict(fileName, normalised, normalised);
        }

        String baseline = snapshot.read(RECEIPT_SUFFIX, name);
        if (baseline == null) {
            throw snapshot.missing(RECEIPT_SUFFIX, name);
        }
        snapshot.claim(RECEIPT_SUFFIX, name);

        // Normalised again, so a mask added later reaches older baselines.
        String expected = stripTrailingNewlines(Normalisers.normaliseReceipt(baseline, normalisers));
        String got = stripTrailingNewlines(normalised);
        if (expected.equals(got)) {
            return new Verdict(fileName, expected, got);
        }

        String ruled;
        if (receiptRule) {
            expected = ReceiptRule.applyRule(expected);
            got = ReceiptRule.applyRule(got);
            if (expected.equals(got)) {
                return new Verdict(fileName, expected, got);
            }
            ruled = " and the lines the shared receipt rule ignores are already dropped, so every "
                    + "line above is a real difference";
        } else {
            ruled = ", so every line above is a real difference";
        }
        String diff = UnifiedDiff.unified(expected, got, snapshot.path(RECEIPT_SUFFIX, name), 3);
        throw new ValidationFailedException("receipt does not match its snapshot (DESIGN.md 5.4).\n"
                + diff + "\n"
                + "Volatile fields are already masked by the global normalisers" + ruled + ". "
                + "If the change is intentional, re-baseline in update mode on this suite and review "
                + "the diff in the pull request.");
    }

    private static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    private static String readUniversal(Path file) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return text.replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read receipt text from " + file, e);
        }
    }
}
