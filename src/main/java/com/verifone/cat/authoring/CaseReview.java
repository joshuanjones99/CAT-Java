package com.verifone.cat.authoring;

import com.verifone.cat.authoring.Check.Analysis;
import com.verifone.cat.authoring.Check.Diagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One case as the replay gate and the publisher will see it: its number in the session, the text they will run (tagged
 * with its placeholder key), the checker's analysis, and its baselines.
 *
 * <p>Not ported: the recording's checks (a recording on the fake POS, a recording cut off before its footer), which need
 * the recorder's format. A case a recording made is checked as its draft text is.
 */
public final class CaseReview {

    private final Case caseRecord;
    private final int number;
    private final String text;
    private final Analysis analysis;
    private final List<Path> baselines;
    private final boolean approved;
    private final String capturedIn;

    CaseReview(Case caseRecord, int number, String text, Analysis analysis, List<Path> baselines, boolean approved,
            String capturedIn) {
        this.caseRecord = caseRecord;
        this.number = number;
        this.text = text;
        this.analysis = analysis;
        this.baselines = List.copyOf(baselines);
        this.approved = approved && !baselines.isEmpty();
        this.capturedIn = capturedIn;
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

    /** The scenario's name, or the case's title when the text has no scenario. */
    public String title() {
        return analysis.scenarios().isEmpty() ? caseRecord.title() : analysis.scenarios().get(0);
    }

    /** What stops this case being replayed: every checker error, by line. */
    public List<String> replayBlockers() {
        String name = title();
        List<String> out = new ArrayList<>();
        for (Diagnostic d : analysis.errors()) {
            out.add(name + ": line " + d.line() + ": " + d.message());
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
        if (baselines.isEmpty()) {
            out.add(name + ": no baselines - run Capture on a rig, then approve them (3.5)");
        } else if (!approved) {
            out.add(name + ": " + baselines.size() + " baseline(s) not approved");
        }
        return out;
    }
}
