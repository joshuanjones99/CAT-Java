package com.verifone.cat.pats.runner.handlers.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.report.CaseRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ValidationSupport#baselineDir}: the folder of the case's test file, so a hand-migrated Cadence case's
 * {@code -CATReceipt.txt} is the one beside its {@code .feature} ({@code tests/suites/cadence/} for 57.02,
 * {@code cadence_55_02/} for 55.02) and a pulled case's is in {@code tests/suites/pulled}.
 */
class BaselineDirTest {

    @TempDir
    Path dir;

    @Test
    void theBaselineDirIsTheTestFilesFolder() {
        Path cadence = dir.resolve("tests/suites/cadence/PETROCORE-36855-TestCase.feature");
        Path cadence55 = dir.resolve("tests/suites/cadence_55_02/PETROCORE-36855-TestCase.feature");
        Path pulled = dir.resolve("tests/suites/pulled/PETROCORE-777-TestCase.feature");
        for (Path file : List.of(cadence, cadence55, pulled)) {
            // As TestsRunStep gives it: the entry's file's folder.
            CaseRun run = run(file.toString()).withBaselineDir(file.getParent());
            assertEquals(file.getParent(), ValidationSupport.baselineDir(run));
        }
    }

    @Test
    void aRunThatSetNoneFallsBackToTheFolderTheCaseWasLoadedFrom() {
        Path cadence = dir.resolve("tests/suites/cadence/PETROCORE-36855-TestCase.feature");
        assertEquals(cadence.getParent(), ValidationSupport.baselineDir(run(cadence.toString())));
    }

    @Test
    void aCaseWithNoFolderHasNoBaselineDir() {
        assertThrows(PatsStepError.class, () -> ValidationSupport.baselineDir(run("memory")));
    }

    private CaseRun run(String source) {
        RunnableCase kase = new RunnableCase() {
            @Override
            public String key() {
                return "PETROCORE-36855";
            }

            @Override
            public String title() {
                return "a baseline test";
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public List<RunnableAction> actions() {
                return List.of();
            }
        };
        CaseRecord record = new CaseRecord("test::" + kase.key(), kase.key(), dir.resolve("record"), Instant.now());
        return new CaseRun(kase, record, new ActionRegistry(), name -> {
            throw new IllegalStateException("no fixture");
        }, Set.of(), new RunState());
    }
}
