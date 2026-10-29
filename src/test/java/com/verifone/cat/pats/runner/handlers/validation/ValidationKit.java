package com.verifone.cat.pats.runner.handlers.validation;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.ValidationsFailed;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptSource;
import com.verifone.cat.pos.receipt.Source;
import com.verifone.cat.report.CaseRecord;
import com.verifone.cat.validate.tlog.Tlog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.w3c.dom.Element;

/**
 * The test kit for the validation family: fakes for every seam, a case and action builder, and the runner.
 * Every fake is a record of what the handler asked, so a test asserts the calls as well as the verdict.
 */
final class ValidationKit {

    /** The case key the tests use: PATS's own, so the packet prefix is {@code PETROCORE-9}. */
    static final String KEY = "PETROCORE-9";

    private ValidationKit() {
    }

    /** A resource under {@code src/test/resources}, read as UTF-8. */
    static String resource(String name) throws IOException {
        try (InputStream in = ValidationKit.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IOException("no test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A receipt whose text is {@code text}, as the POS source would capture it. */
    static Receipt receipt(String text) {
        return new Receipt(List.of(), Source.DOM, Arrays.asList(text.split("\n", -1)), null, null, null);
    }

    /** A receipt source that answers with {@code text} each time. */
    static ReceiptSource receiptSource(String text) {
        return () -> receipt(text);
    }

    /** Parses XML text to its root element, as the commander and POS reads give it. */
    static Element xml(String text) {
        return Tlog.parse(text);
    }

    /** An action at {@code index}, with {@code name} and {@code name, value, name, value, ...} fields. */
    static RunnableAction action(int index, String name, String... nameValuePairs) {
        List<RunnableAction.ActionField> fields = new ArrayList<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            fields.add(new RunnableAction.ActionField(nameValuePairs[i], nameValuePairs[i + 1]));
        }
        return new Act(name, index, List.copyOf(fields));
    }

    /** A case named {@code key} whose actions are {@code actions}, in the order given. */
    static RunnableCase kase(String key, RunnableAction... actions) {
        return new Kase(key, List.of(actions));
    }

    /** The runner for a case: the validation family registered, the case's record in {@code folder}. */
    static CaseRun run(RunnableCase kase, ValidationContext context, Path baseline, boolean update,
            Set<FixtureName> declared, Path folder) {
        ActionRegistry registry = new ActionRegistry();
        ValidationFamily.register(registry, context);
        CaseRecord record = new CaseRecord("test::" + kase.key(), kase.key(), folder.resolve(kase.key()), Instant.now());
        return new CaseRun(kase, record, registry, name -> {
            throw new IllegalStateException("no fixture " + name.key() + " in this test");
        }, declared, new RunState())
                .withBaselineDir(baseline)
                .withUpdate(update);
    }

    /**
     * Runs the case. Returns the verdict's message when a validation failed (the case carried on to its end), or
     * empty when every validation passed. A hard failure propagates as the runner throws it.
     */
    static String verdict(CaseRun run) {
        try {
            run.run();
            return "";
        } catch (ValidationsFailed failed) {
            return failed.getMessage();
        }
    }

    /** The context over the given fakes, with a fixed environment. */
    static ValidationContext context(ReceiptSource receipts, FakePos pos, FakeCommander commander,
            FakePackets packets, FakeReferences references, Map<String, String> environment) {
        return new ValidationContext(receipts, null, null, pos, commander, packets, references,
                environment::get);
    }

    /** No environment switch is set. */
    static Map<String, String> noEnv() {
        return Map.of();
    }

    /** The test's declared fixtures: the POS, and the Commander configuration. */
    static Set<FixtureName> declaredPosAndCommander() {
        return EnumSet.of(FixtureName.POS, FixtureName.COMMANDER_CONFIG);
    }

    /** The test's declared fixtures: none. */
    static Set<FixtureName> declaredNothing() {
        return EnumSet.noneOf(FixtureName.class);
    }

    /** A POS fake: its newest transaction number, and the TLog a read returns; it records each read's mark. */
    static class FakePos implements ValidationContext.PosTransactions {
        String newestNumber;
        Element tlog;
        String readNumber = "";
        final List<String> marksSeen = new ArrayList<>();

        @Override
        public String newestTransactionNumber() {
            return newestNumber;
        }

        @Override
        public ValidationContext.PosTlog newestTlog(String after, double timeoutSeconds) {
            marksSeen.add(after);
            return new ValidationContext.PosTlog(tlog, readNumber);
        }
    }

    /**
     * A Commander fake. Its transaction listing answers the queued lists in turn, the last one for good; its TLogs
     * and reports are keyed by sequence number and by {@code cmd/report}.
     */
    static final class FakeCommander implements ValidationContext.CommanderReads {
        final Deque<List<String>> listings = new ArrayDeque<>();
        final Map<String, Element> tlogs = new HashMap<>();
        final Map<String, Element> reports = new HashMap<>();
        final List<String> fetched = new ArrayList<>();
        final List<String> reportCalls = new ArrayList<>();

        @Override
        public List<String> listTransactionNumbers() {
            return listings.size() > 1 ? listings.pop() : listings.peek() == null ? List.of() : listings.peek();
        }

        @Override
        public Element fetchTlogByTrseq(String trseq) {
            fetched.add(trseq);
            Element tlog = tlogs.get(trseq);
            if (tlog == null) {
                throw new IllegalStateException("no transaction " + trseq + " in this fake");
            }
            return tlog;
        }

        @Override
        public Element fetchReport(String cmd, String report, String period, String filename, String cashier,
                String register) {
            reportCalls.add(cmd + "/" + report + " period=" + period + " file=" + filename
                    + " cashier=" + cashier + " register=" + register);
            return reports.get(cmd + "/" + report);
        }
    }

    /** A packet fake: each device's capture is a folder, which a reset empties. */
    static final class FakePackets implements ValidationContext.PacketSource {
        final Map<String, Path> folders = new HashMap<>();
        final List<String> resets = new ArrayList<>();

        @Override
        public List<Path> captured(String device) {
            Path folder = folders.get(device);
            if (folder == null || !Files.isDirectory(folder)) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(folder)) {
                return files.toList();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void reset(String device) {
            resets.add(device);
            for (Path file : captured(device)) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** The case's references and shared files, by rule and name (names are matched lower-case). */
    static final class FakeReferences implements ValidationContext.CaseReferences {
        final Map<String, byte[]> references = new HashMap<>();
        final Map<String, byte[]> shared = new HashMap<>();

        FakeReferences reference(String rule, String name, String content) {
            references.put(rule + "/" + name.toLowerCase(Locale.ROOT), content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        FakeReferences sharedFile(String folder, String name, String content) {
            shared.put(folder + "/" + name.toLowerCase(Locale.ROOT), content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public Optional<byte[]> reference(String rule, String filename) {
            return Optional.ofNullable(references.get(rule + "/" + filename.toLowerCase(Locale.ROOT)));
        }

        @Override
        public Optional<byte[]> shared(String folder, String filename) {
            return Optional.ofNullable(shared.get(folder + "/" + filename.toLowerCase(Locale.ROOT)));
        }
    }

    /** A test's action. */
    record Act(String name, int index, List<RunnableAction.ActionField> fields) implements RunnableAction {
    }

    /** A test's case. */
    record Kase(String key, List<RunnableAction> actions) implements RunnableCase {

        @Override
        public String title() {
            return "a validation test case";
        }

        @Override
        public String source() {
            return "memory";
        }
    }
}
