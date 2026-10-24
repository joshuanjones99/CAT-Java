package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The publish dry run: every mutation it would make, in order, and nothing sent. */
class PublishTest {

    private static Session ready(Path tmp) throws IOException {
        Session session = Session.create(tmp.resolve("s"));
        Case c = session.insert(0, "Cash sale");
        session.writeText(c, "@PETROCORE-1\nFeature: x\n\n  Scenario: Cash sale\n  # a note\n" + SessionTest.GOOD_STEPS);
        session.setTicket("PETROCORE-900");
        Path folder = session.baselinesDir(c);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("CASE-CATReceipt.txt"), "TOTAL\n");
        session.approveBaselines(c);
        return session;
    }

    /** A git repository with one commit, as a petroapps checkout; {@code git} must be on the PATH, as on the controller. */
    private static Path checkout(Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("petroapps"));
        git(repo, "init", "-q");
        git(repo, "-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "--allow-empty", "-m", "init");
        return repo;
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git", "-C", repo.toString()));
        argv.addAll(List.of(args));
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args));
    }

    @Test
    void theGherkinBodyIsTheStepsAlone() {
        String body = Publish.gherkinBody("@PETROCORE-1\nFeature: x\n\n  Scenario: Cash sale\n  # a note\n"
                + SessionTest.GOOD_STEPS);
        assertTrue(body.startsWith("Given the register is idle"), body);
        assertFalse(body.contains("Scenario"));
        assertFalse(body.contains("#"));
        assertFalse(body.contains("@"));
    }

    @Test
    void theDryRunListsEveryMutationAndSendsNothing(@TempDir Path tmp) throws Exception {
        Session session = ready(tmp);
        Path repo = checkout(tmp);
        Publish.PublishPlan plan = Publish.plan(session, List.of(), repo, "master");
        assertTrue(plan.ok(), plan.refused().toString());
        String lines = String.join("\n", plan.lines());
        assertTrue(lines.startsWith("DRY RUN — nothing below is sent"), lines);
        assertTrue(lines.contains("createTest — Cucumber — 'Cash sale' -> <new key 1>"), lines);
        assertFalse(lines.contains("\\\"labels\\\": [\\\"CAT-authored\\\"]"), "fields are JSON, not escaped twice");
        assertTrue(lines.contains("\"labels\": [\"CAT-authored\"]"), lines);
        assertTrue(lines.contains("AutomationTests/testcases/<new key 1>/<new key 1>-TestCase.feature"), lines);
        assertTrue(lines.contains("<new key 1>-CATReceipt.txt"), lines);
        assertTrue(lines.contains("git -C " + repo + " push origin PETROCORE-900"), lines);
    }

    @Test
    void theDryRunRefusesAnExistingBranchAndAFailedGate(@TempDir Path tmp) throws Exception {
        Session session = ready(tmp);
        Path repo = checkout(tmp);
        git(repo, "branch", "PETROCORE-900");
        Publish.PublishPlan plan = Publish.plan(session, List.of("not replayed"), repo, "master");
        assertFalse(plan.ok());
        assertEquals("not replayed", plan.refused().get(0));
        assertTrue(plan.refused().get(1).contains("already has branch PETROCORE-900"), plan.refused().toString());
        assertTrue(plan.refused().get(1).contains("D23"));
        assertTrue(String.join("\n", plan.lines()).contains("would be REFUSED (2 reason(s))"));
    }
}
