package com.verifone.cat.authoring;

import com.verifone.cat.authoring.Check.Analysis;
import com.verifone.cat.authoring.Check.Diagnostic;
import com.verifone.cat.recorder.Recording;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One case as the replay gate and the publisher will see it: its number in the session, the text they will run (tagged
 * with its placeholder key), the checker's analysis, its recording (when it was recorded) and its baselines. Port of
 * {@code cat.authoring.session.CaseReview}.
 */
public final class CaseReview {

    private final Case caseRecord;
    private final int number;
    private final String text;
    private final Analysis analysis;
    private final List<Path> baselines;
    private final boolean approved;
    private final String capturedIn;
    private final Recording recording;
    private final String recordingError;

    CaseReview(Case caseRecord, int number, String text, Analysis analysis, List<Path> baselines, boolean approved,
            String capturedIn, Recording recording, String recordingError) {
        this.caseRecord = caseRecord;
        this.number = number;
        this.text = text;
        this.analysis = analysis;
        this.baselines = List.copyOf(baselines);
        this.approved = approved && !baselines.isEmpty();
        this.capturedIn = capturedIn;
        this.recording = recording;
        this.recordingError = recordingError;
    }

    public Case caseRecord() {
        return caseRecord;
    }

    /** The case's place in the session, from 1. */
    public int number() {
        return number;
    }

    /** The text a replay runs: the draft, tagged {@code @PETROCORE-<number>}. */
    public String text() {
        return text;
    }

    public Analysis analysis() {
        return analysis;
    }

    /** The case's baseline files, by name order. */
    public List<Path> baselines() {
        return baselines;
    }

    /** Whether the baselines were approved (and there are some). */
    public boolean approved() {
        return approved;
    }

    /** The run whose capture the baselines came from, or the empty string. */
    public String capturedIn() {
        return capturedIn;
    }

    /** The case's recording, or null when it has none or it could not be read. */
    public Recording recording() {
        return recording;
    }

    /** Why the case's recording could not be read, or null. */
    public String recordingError() {
        return recordingError;
    }

    /** Whether the case was recorded on the fake POS. */
    public boolean fakePos() {
        return recording != null && "fake_pos".equals(recording.sourceKind());
    }

    /** The scenario's name, or the case's title when the text has no scenario. */
    public String title() {
        return analysis.scenarios().isEmpty() ? caseRecord.title() : analysis.scenarios().get(0);
    }

    /** What stops this case being replayed: every checker error, by line, and an unreadable recording. */
    public List<String> replayBlockers() {
        String name = title();
        List<String> out = new ArrayList<>();
        for (Diagnostic d : analysis.errors()) {
            out.add(name + ": line " + d.line() + ": " + d.message());
        }
        if (recordingError != null) {
            out.add(name + ": its recording is unreadable — " + recordingError);
        }
        return out;
    }

    /** What stops it being published, on top of {@link #replayBlockers()}. */
    public List<String> publishBlockers() {
        String name = title();
        List<String> out = replayBlockers();
        for (Diagnostic d : analysis.warnings()) {
            if (d.code().equals("ui-level")) {
                out.add(name + ": line " + d.line() + " names a screen control; ask for a domain phrase");
            } else if (d.code().equals("pats-level")) {
                out.add(name + ": line " + d.line() + " is a PATS action; ask for a domain phrase");
            }
        }
        if (fakePos()) {
            out.add(name + ": recorded on the fake POS, not a rig (§3.5)");
        }
        if (recording != null && recording.footer() == null) {
            out.add(name + ": the recording was cut off (no footer)");
        }
        if (baselines.isEmpty()) {
            out.add(name + ": no baselines — run ◉ Capture on a rig, then approve them (§3.5)");
        } else if (!approved) {
            out.add(name + ": " + baselines.size() + " baseline(s) not approved");
        }
        return out;
    }
}
