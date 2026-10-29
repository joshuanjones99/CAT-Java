package com.verifone.cat.runservice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.devices.printer.TopazPrinter;

/**
 * The Receipt Printer row's roll: what the register printed this run, oldest first, each receipt with the case
 * that was running and what that case's receipt check made of it. The Python CAT's {@code cat.panels.receipts}
 * ({@code parse_roll} and the verdict pairing).
 *
 * <p>It reads the row's own log ({@link RunState#logLinesFrom} of {@link TopazPrinter#RECEIPT_LOGGER}): a receipt is a
 * {@link TopazPrinter#RECEIPT_START} record (its key in brackets, and {@code during <case>} when a case was running),
 * one {@link TopazPrinter#RECEIPT_LINE} record per printed line, and a {@link TopazPrinter#RECEIPT_END}. A check's
 * verdict is a {@link #RECEIPT_CHECK} record ({@code <key> <case or -> <passed|failed> <baseline or ->}) and its diff
 * {@link #RECEIPT_DIFF} records; the verdict goes on the newest receipt with the same key, or stands on its own (a
 * DCR's receipt, not printed here). Anything else in that log is a note: the port opening, closing or failing.
 *
 * <p>Deviation, and a gap: the Java {@code TopazPrinter} writes these records to its slf4j logger only, and the
 * Python's {@code relay_receipts} (which copies them into the run's {@code log.line} events) is not ported, nor are
 * the Python's {@code RECEIPT_CHECK}/{@code RECEIPT_DIFF} records or the {@code during <case>} tag. Until they are,
 * a Java run's roll reads "No receipts yet"; the parsing here is the Python's, ready for those records.
 */
public final class ReceiptRoll {

    /** A receipt check's verdict record (the Python's {@code RECEIPT_CHECK}). */
    public static final String RECEIPT_CHECK = "╞ ";

    /** One line of a failed check's diff (the Python's {@code RECEIPT_DIFF}). */
    public static final String RECEIPT_DIFF = "┊ ";

    /** A receipt start's key and case: {@code ... [3f2a9c01d4] during PETROCORE-61334}. */
    private static final Pattern START = Pattern.compile("\\[(?<key>[0-9a-f]+)\\](?: during (?<case>\\S+))?");

    private ReceiptRoll() {}

    /** One item of the roll: a receipt, a verdict on a receipt not printed here, or a note. */
    public sealed interface Item permits Receipt, Verdict, Note {
        Instant at();
    }

    /** What a receipt check made of a receipt. {@code diff} is filled as its records arrive. */
    public static final class Verdict implements Item {
        private final Instant at;
        private final String key;
        private final String caseKey;
        private final boolean passed;
        private final String baseline;
        private final List<String> diff = new ArrayList<>();

        Verdict(Instant at, String key, String caseKey, boolean passed, String baseline) {
            this.at = at;
            this.key = key;
            this.caseKey = caseKey;
            this.passed = passed;
            this.baseline = baseline;
        }

        @Override
        public Instant at() {
            return at;
        }

        public String key() {
            return key;
        }

        public String caseKey() {
            return caseKey;
        }

        public boolean passed() {
            return passed;
        }

        public String baseline() {
            return baseline;
        }

        public List<String> diff() {
            return List.copyOf(diff);
        }
    }

    /** One printed receipt: when, during which case, its lines, and its check's verdict (or null). */
    public static final class Receipt implements Item {
        private final Instant at;
        private final String key;
        private final String caseKey;
        private final List<String> lines = new ArrayList<>();
        private Verdict verdict;

        Receipt(Instant at, String key, String caseKey) {
            this.at = at;
            this.key = key;
            this.caseKey = caseKey;
        }

        @Override
        public Instant at() {
            return at;
        }

        public String key() {
            return key;
        }

        public String caseKey() {
            return caseKey;
        }

        public List<String> lines() {
            return List.copyOf(lines);
        }

        public Verdict verdict() {
            return verdict;
        }
    }

    /** A printer record that is not a receipt: the port opened, closed or failed. */
    public record Note(Instant at, String level, String text) implements Item {}

    /** The receipts, verdicts and notes in the printer's log lines, in the order they were logged. */
    public static List<Item> parse(List<RunState.LogLine> logLines) {
        List<Item> roll = new ArrayList<>();
        Receipt current = null;
        Verdict verdict = null;
        for (RunState.LogLine line : logLines) {
            String message = line.message();
            if (message.startsWith(TopazPrinter.RECEIPT_START)) {
                Matcher match = START.matcher(message);
                boolean found = match.find();
                current = new Receipt(line.at(), found ? match.group("key") : "",
                        found && match.group("case") != null ? match.group("case") : "");
                roll.add(current);
                verdict = null;
            } else if (message.startsWith(TopazPrinter.RECEIPT_LINE)) {
                if (current == null) { // its start has dropped off the bounded log
                    current = new Receipt(line.at(), "", "");
                    roll.add(current);
                }
                current.lines.add(message.substring(TopazPrinter.RECEIPT_LINE.length()));
            } else if (message.startsWith(TopazPrinter.RECEIPT_END)) {
                current = null;
            } else if (message.startsWith(RECEIPT_CHECK)) {
                current = null;
                verdict = verdict(line.at(), message.substring(RECEIPT_CHECK.length()));
                Receipt owner = null;
                for (int i = roll.size() - 1; i >= 0; i--) {
                    if (roll.get(i) instanceof Receipt receipt && receipt.key.equals(verdict.key)) {
                        owner = receipt;
                        break;
                    }
                }
                if (owner != null) {
                    owner.verdict = verdict;
                } else {
                    roll.add(verdict);
                }
            } else if (message.startsWith(RECEIPT_DIFF)) {
                if (verdict != null) {
                    verdict.diff.add(message.substring(RECEIPT_DIFF.length()));
                }
            } else {
                current = null;
                verdict = null;
                roll.add(new Note(line.at(), line.level(), message));
            }
        }
        return roll;
    }

    /** {@code <key> <case or -> <passed|failed> <baseline or ->} as a verdict. */
    static Verdict verdict(Instant at, String body) {
        String[] parts = body.split(" ", 4);
        String key = parts.length > 0 ? parts[0] : "";
        String caseKey = parts.length > 1 ? parts[1] : "";
        String word = parts.length > 2 ? parts[2] : "";
        String baseline = parts.length > 3 ? parts[3] : "";
        return new Verdict(at, key, "-".equals(caseKey) ? "" : caseKey, "passed".equals(word),
                "-".equals(baseline) ? "" : baseline);
    }

    /** How many receipts the roll holds. */
    public static int receipts(List<Item> roll) {
        return (int) roll.stream().filter(item -> item instanceof Receipt).count();
    }

    /** The roll's head: how many printed, or that none has yet and when CAT holds the port. */
    public static String head(List<Item> roll, String port) {
        int count = receipts(roll);
        String where = port == null || port.isEmpty() ? "" : " on " + port;
        if (count > 0) {
            return count + " receipt" + (count == 1 ? "" : "s") + " printed" + where + " this run, oldest first.";
        }
        return "No receipts yet" + where + ". CAT answers the register's printer while a run holds the port, "
                + "from System Checks until its tests end; what it prints then is shown here as it prints.";
    }

    /** The line under a receipt's case: whether its check matched, differed, or has not compared it. */
    public static String verdictLine(Verdict verdict, boolean running) {
        if (verdict == null) {
            return running ? "receipt not compared yet" : "receipt not compared by its case";
        }
        String against = verdict.baseline().isEmpty() ? " its baseline" : " " + verdict.baseline();
        if (verdict.passed()) {
            return "✔ receipt matches" + against;
        }
        return "✖ receipt differs from" + against + " (the diff is on the right)";
    }
}
