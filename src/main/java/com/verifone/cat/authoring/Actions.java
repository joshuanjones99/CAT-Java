package com.verifone.cat.authoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.verifone.cat.authoring.Draft.DraftException;
import com.verifone.cat.authoring.Draft.DraftResult;
import com.verifone.cat.authoring.Draft.Drafted;

/**
 * What Draft does to a session: one implementation for the web page and the command line. Port of
 * {@code cat.authoring.actions.draft_into} (ingest is {@link Ingest#intoSession}).
 */
public final class Actions {

    private Actions() {}

    /**
     * Drafts cases from the session's spec and adds every one as a {@code drafted} case. A case the checker still refuses
     * after the repair rounds is added anyway: the author sees its errors in the editor, which is better than a silent
     * drop. Cases Claude could not write in catalogue phrases are returned, not added.
     *
     * @param ask who answers; null asks Claude Code
     * @throws DraftException when the session has no spec, or Claude could not be asked
     */
    public static DraftResult draftInto(Session session, Consumer<String> progress, Draft.Asker ask) {
        Path path = session.specPath();
        if (path == null || !Files.isRegularFile(path)) {
            throw new DraftException("ingest a ticket first: Draft works from the session's spec");
        }
        String spec;
        try {
            spec = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DraftException(path + ": " + e.getMessage(), e);
        }
        List<String> existing = new ArrayList<>();
        for (Case c : session.cases()) {
            existing.add(session.review(c).title());
        }
        DraftResult result = Draft.draftCases(spec, session.featureName(), session.specTicket(), existing, ask,
                session.directory().resolve("drafts"), session.cases().size() + 1, progress);
        for (Drafted drafted : result.cases) {
            try {
                session.insert(session.cases().size(), drafted.title(), "drafted", drafted.text());
            } catch (SessionException e) {
                session.insert(session.cases().size(), "Drafted case", "drafted", drafted.text());
            }
        }
        return result;
    }
}
