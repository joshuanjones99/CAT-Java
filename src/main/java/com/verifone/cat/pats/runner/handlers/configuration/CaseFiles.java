package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.pats.runner.RunnableCase;

import java.io.IOException;
import java.util.Optional;

/**
 * The files a case's folders carry beside its test, such as an {@code httpSimPayLoad} a case names (the Python's
 * {@code case.has_reference} and {@code store.from_any_case}). The case reader owns the folders; this is the
 * seam {@code HTTP_SIM_EVENT} reads through.
 */
public interface CaseFiles {

    /** Nothing: no case folders are bound, so a payload is read from the shared folder only. */
    CaseFiles NONE = new CaseFiles() {
        @Override
        public Optional<byte[]> reference(RunnableCase kase, String kind, String name) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> fromAnyCase(String kind, String name) {
            return Optional.empty();
        }
    };

    /** The file {@code name} of kind {@code kind} that the case {@code kase} carries itself, if it does. */
    Optional<byte[]> reference(RunnableCase kase, String kind, String name) throws IOException;

    /** The file {@code name} of kind {@code kind} that some case's folder holds, in PATS's copy order, if one does. */
    Optional<byte[]> fromAnyCase(String kind, String name) throws IOException;
}
