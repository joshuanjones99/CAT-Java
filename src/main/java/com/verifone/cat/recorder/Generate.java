package com.verifone.cat.recorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.authoring.Recorded;
import com.verifone.cat.authoring.Recorded.PhrasedStep;
import com.verifone.cat.validate.Normalisers;
import com.verifone.cat.validate.PyRepr;
import com.verifone.cat.validate.ReceiptAssertions;
import com.verifone.cat.validate.SnapshotStore;
import com.verifone.cat.validate.tlog.Tlog;
import com.verifone.cat.validate.tlog.TlogAssertions;

/**
 * Recording to an authored case file (+ baselines). The Java's {@code cat-java generate}; port of
 * {@code petrotest.recorder.generate.generate}, with one decision.
 *
 * <p><b>Decision (wave 16): the case is a {@code .feature}, not a pytest module.</b> The Python's {@code petrotest generate}
 * writes a {@code PETROCORE-<n>-TestCase.py} in the style of the hand-migrated charity cases, which pytest runs. CAT-Java
 * has no pytest: its runner runs step-library {@code .feature} cases ({@code CAT_STEP}), and the Python's own Authoring
 * flow already turns a recording into one ({@code Session.adopt_recording}, {@code petrotest.authoring.recorded}). So what
 * an operator gets from {@code cat-java generate} is that feature: {@link Recorded#recordingFeature} over
 * {@link Collapse#collapse}, the same text the Authoring session adopts, which {@code steps check} and the runner read.
 * Everything else is the Python's: the file is {@code PETROCORE-<n>-TestCase.feature} from Xray's key or the session
 * placeholder (D25), or {@code <name>.feature} with neither; the story ticket is checked and named; an existing file or
 * baseline aborts with nothing written (D23); baselines come only from approved {@code baseline} lines, never from a
 * {@code fake_pos} recording (3.5), normalised as {@code --snapshot-update} writes them.
 *
 * <p>Deviations that follow from it: the case's tag is {@code @<key>} ({@code @PETROCORE-1} with neither key nor
 * placeholder, as a new written case's skeleton has); its {@code Feature:} line is the session's ({@code <story> authored
 * cases}, else {@code Authored cases}); the baselines are the unnamed {@code <KEY>-CATReceipt.txt} and {@code <KEY>-Tlog.xml}
 * a feature's {@code the receipt matches its baseline} reads (the Python's pytest writer names them after the test
 * function, {@code <KEY>-CATReceipt-<name>.txt}). A step is "not generated" when it has no library phrase: the checker
 * refuses that line, as the Python's {@code pytest.fail} first statement did.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no generated case has run on a rig.
 */
public final class Generate {

    private static final Pattern TICKET = Pattern.compile("^PETROCORE-(\\d+)$");
    private static final Pattern IDENT = Pattern.compile("^test_[a-z0-9_]+$");

    private Generate() {}

    /** The recording cannot become a case file, and nothing was written. The Python's {@code GenerateError}. */
    public static final class GenerateException extends RuntimeException {
        public GenerateException(String message) {
            super(message);
        }
    }

    /** What was written: the case, its phrased steps and its baselines. */
    public record Generated(Path caseFile, List<PhrasedStep> steps, List<Path> baselines) {

        /** The steps the checker will refuse: unmapped taps and calls with no phrase. */
        public List<PhrasedStep> failing() {
            return steps.stream().filter(s -> !s.resolved()).toList();
        }
    }

    /** {@code PETROCORE-<n>-TestCase.feature} from Xray's key or the placeholder (D25), else {@code <name>.feature}. */
    static String fileName(String key, Integer placeholder, String name) {
        if (key != null && placeholder != null) {
            throw new GenerateException("a case has an Xray key or a placeholder number, not both (D25)");
        }
        if (key != null && !key.isEmpty()) {
            Matcher match = TICKET.matcher(key);
            if (!match.matches()) {
                throw new GenerateException(PyRepr.reprString(key) + " is not a test case key like PETROCORE-12345");
            }
            return "PETROCORE-" + match.group(1) + "-TestCase.feature";
        }
        if (placeholder != null) {
            if (placeholder < 1) {
                throw new GenerateException("placeholder " + placeholder + ": they count from 1, in session order");
            }
            return "PETROCORE-" + placeholder + "-TestCase.feature";
        }
        return name + ".feature";
    }

    /** The scenario's title when none is given: the Python's {@code _title}. */
    static String title(String key, Integer placeholder, String name) {
        String what = (name.startsWith("test_") ? name.substring(5) : name).replace("_", " ");
        if (key != null && !key.isEmpty()) {
            return key + " — recorded: " + what;
        }
        if (placeholder != null) {
            return "Placeholder " + placeholder + " — recorded: " + what;
        }
        return "Recorded case — " + what;
    }

    /**
     * Writes the case (and any approved rig baselines) into {@code outDir}.
     *
     * @param story the D17 story ticket: it names the feature, never the file
     * @throws GenerateException writing nothing, when the name, key, story or placeholder is wrong, the case or a baseline
     *                           already exists (D23), a baseline came from the fake POS, or one was never approved
     */
    public static Generated generate(Recording recording, Path outDir, String name, String story, String key,
            Integer placeholder, String title) throws IOException {
        String testName = name == null ? "test_recorded_case" : name;
        if (!IDENT.matcher(testName).matches()) {
            throw new GenerateException(PyRepr.reprString(testName) + ": a test name is test_ followed by [a-z0-9_]");
        }
        if (story != null && !TICKET.matcher(story).matches()) {
            throw new GenerateException(PyRepr.reprString(story)
                    + " is not a story ticket key like PETROCORE-12345 (D17)");
        }
        Path caseFile = outDir.resolve(fileName(key, placeholder, testName));
        if (Files.exists(caseFile)) {
            throw new GenerateException(caseFile + " already exists; nothing was written (D23)");
        }
        String tagKey = key != null && !key.isEmpty() ? key : "PETROCORE-" + (placeholder == null ? 1 : placeholder);
        SnapshotStore store = new SnapshotStore(outDir, tagKey);
        List<Path> paths = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        for (Map<String, Object> line : recording.baselines()) {
            if ("fake_pos".equals(recording.sourceKind())) {
                throw new GenerateException("a baseline from the fake POS is never written (§3.5)");
            }
            if (!Boolean.TRUE.equals(line.get("approved"))) {
                throw new GenerateException("the " + PyRepr.str(line.get("kind")) + " baseline is not approved (§3.5)");
            }
            Object kind = line.get("kind");
            String content = line.get("content") == null ? "" : PyRepr.str(line.get("content"));
            if ("receipt".equals(kind)) {
                paths.add(store.path("", ReceiptAssertions.RECEIPT_SUFFIX));
                contents.add(Normalisers.normaliseReceipt(content));
            } else if ("tlog".equals(kind)) {
                paths.add(store.path("", TlogAssertions.TLOG_SUFFIX));
                contents.add(Tlog.canonicalise(content));
            } else {
                throw new GenerateException("unknown baseline kind " + PyRepr.repr(kind));
            }
        }
        for (Path path : paths) {
            if (Files.exists(path)) {
                throw new GenerateException(path + " already exists; nothing was written (D23)");
            }
        }
        String feature = story != null ? story + " authored cases" : "Authored cases";
        String text = Recorded.recordingFeature(recording, title != null ? title : title(key, placeholder, testName),
                feature, tagKey);
        Files.createDirectories(outDir);
        Files.writeString(caseFile, text, StandardCharsets.UTF_8);
        List<Path> written = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            String content = contents.get(i);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content.endsWith("\n") ? content : content + "\n", StandardCharsets.UTF_8);
            written.add(path);
        }
        return new Generated(caseFile, Recorded.phrased(recording), written);
    }
}
