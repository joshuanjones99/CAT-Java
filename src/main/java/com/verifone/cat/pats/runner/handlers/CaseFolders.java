package com.verifone.cat.pats.runner.handlers;

import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.handlers.configuration.PetroappsFiles;
import com.verifone.cat.pats.runner.handlers.http.CaseFiles;
import com.verifone.cat.pats.runner.handlers.validation.ValidationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The case folders, read for the families that need the files a case carries beside its test: its HTTP payloads and
 * reference payloads, its simulator payloads, its PATS reference files. Implements the three {@code CaseFiles} seams
 * ({@link CaseFiles} for HTTP, the configuration family's, and {@link ValidationContext.CaseReferences}) over the
 * folder the case was loaded from, and the petroapps checkout for what PATS copies from elsewhere.
 *
 * <p>The seams carry no case, so the adapter remembers the case the runner began last: {@link #begin} is the run's
 * first begin hook, so every family's begin and action sees the case in hand. A case run step by step (a
 * {@code .feature}) begins through the same hook.
 *
 * <p>A file is found by its base name, case-insensitively, under the case's own folder; PATS's rule subfolders are
 * preferred when several copies sit there. Another case's copy is looked for under {@code AutomationTests/testcases},
 * and two copies that differ are refused, as the Python's {@code CaseStore.from_any_case} refuses them. Reads only:
 * nothing is copied.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the folder layout is the lab's petroapps checkout, read off the Python rules.
 */
final class CaseFolders {

    /** The cases, relative to the petroapps checkout's root. */
    static final String TESTCASES = "AutomationTests/testcases";

    /** The payload rule files, relative to the checkout's root ({@code SHARED_ASSETS}). */
    static final String PAYLOAD_RULES = "AutomationTests/rulefiles/Payloads";

    /** How deep a case folder, or the testcases tree, is searched. */
    private static final int DEPTH = 8;

    private final PetroappsFiles petroapps;
    private final AtomicReference<RunnableCase> current = new AtomicReference<>();

    CaseFolders(PetroappsFiles petroapps) {
        this.petroapps = petroapps;
    }

    /** The run's first begin hook: remembers the case about to run. */
    void begin(RunnableCase kase) {
        current.set(kase);
    }

    /** The folder the current case was loaded from, or empty when there is no case or no folder to read. */
    Optional<Path> folder() {
        RunnableCase kase = current.get();
        if (kase == null || kase.source() == null || kase.source().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Path.of(kase.source()).getParent());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** The {@code CaseFiles} the HTTP family reads its payloads through. */
    CaseFiles httpFiles() {
        return new CaseFiles() {
            @Override
            public byte[] httpPayload(String name) {
                Optional<byte[]> payload = own(name, "Payloads/HTTP");
                if (payload.isEmpty()) {
                    payload = anyCase(name, "Payloads/HTTP");
                }
                return payload.orElseThrow(() -> new PatsStepError("no HTTP payload " + name
                        + " in the case's folder, or in any case folder of the suite"));
            }

            @Override
            public Optional<byte[]> referencePayload(String name) {
                return own(name, "ReferenceFiles/Payloads");
            }

            @Override
            public Optional<byte[]> payloadRule(String name) {
                Optional<byte[]> copy = own(name, "Payloads");
                return copy.isPresent() ? copy : shared(PAYLOAD_RULES, name);
            }
        };
    }

    /** The {@code CaseFiles} the configuration family reads its simulator payloads through. */
    com.verifone.cat.pats.runner.handlers.configuration.CaseFiles configurationFiles() {
        return new com.verifone.cat.pats.runner.handlers.configuration.CaseFiles() {
            @Override
            public Optional<byte[]> reference(RunnableCase kase, String kind, String name) throws IOException {
                Optional<Path> found = findIn(folderOf(kase), name, kind);
                return found.isPresent() ? Optional.of(Files.readAllBytes(found.get())) : Optional.empty();
            }

            @Override
            public Optional<byte[]> fromAnyCase(String kind, String name) throws IOException {
                return anyCopy(name, kind);
            }
        };
    }

    /** The PATS reference files and shared rule files the validation family reads. */
    ValidationContext.CaseReferences references() {
        return new ValidationContext.CaseReferences() {
            @Override
            public Optional<byte[]> reference(String rule, String filename) {
                return own(filename, rule);
            }

            @Override
            public Optional<byte[]> shared(String folder, String filename) {
                return CaseFolders.this.shared(folder, filename);
            }
        };
    }

    // -- lookups ------------------------------------------------------------------------------

    /** The current case's copy of {@code name}, preferring the subfolder {@code preferred}. */
    private Optional<byte[]> own(String name, String preferred) {
        Optional<Path> folder = folder();
        if (folder.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<Path> found = findIn(folder.get(), name, preferred);
            return found.isPresent() ? Optional.of(Files.readAllBytes(found.get())) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + name + " in the case's folder", e);
        }
    }

    /**
     * The one copy of {@code name} that every case folder under the testcases tree holds. Copies that differ are refused,
     * since which one PATS copies last depends on its copy order.
     */
    private Optional<byte[]> anyCopy(String name, String preferred) throws IOException {
        List<byte[]> copies = new ArrayList<>();
        for (Path copy : matches(petroapps.root().resolve(TESTCASES), name, preferred)) {
            copies.add(Files.readAllBytes(copy));
        }
        if (copies.isEmpty()) {
            return Optional.empty();
        }
        byte[] first = copies.get(0);
        for (byte[] other : copies) {
            if (!Arrays.equals(first, other)) {
                throw new IOException(name + " differs between case folders; which one PATS copies last depends on "
                        + "its copy order, so none is taken");
            }
        }
        return Optional.of(first);
    }

    private Optional<byte[]> anyCase(String name, String preferred) {
        try {
            return anyCopy(name, preferred);
        } catch (IOException e) {
            throw new PatsStepError(e.getMessage(), e);
        }
    }

    /** A shared asset of the checkout, by folder and base name; empty when the checkout does not hold it. */
    Optional<byte[]> shared(String folder, String filename) {
        try {
            return Optional.of(petroapps.shared(folder, filename));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** {@code folder} as the case's, or the one the case was loaded from when none is given. */
    private Path folderOf(RunnableCase kase) {
        try {
            Path parent = kase == null || kase.source() == null ? null : Path.of(kase.source()).getParent();
            return parent == null ? Path.of("") : parent;
        } catch (InvalidPathException e) {
            return Path.of("");
        }
    }

    /** The file {@code name} under {@code dir}, preferring the copy under {@code preferred}, or empty. */
    static Optional<Path> findIn(Path dir, String name, String preferred) throws IOException {
        List<Path> copies = matches(dir, name, preferred);
        return copies.stream().findFirst();
    }

    /**
     * Every file under {@code root} whose base name is {@code name} (case-insensitively), those under a path that names
     * {@code preferred} first. Empty when {@code root} is not a folder.
     */
    static List<Path> matches(Path root, String name, String preferred) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        String rule = preferred == null ? "" : preferred.toLowerCase(Locale.ROOT).replace('\\', '/');
        try (Stream<Path> walk = Files.walk(root, DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted))
                    .sorted((a, b) -> Boolean.compare(!under(a, rule), !under(b, rule)))
                    .toList();
        }
    }

    private static boolean under(Path file, String rule) {
        return !rule.isEmpty() && file.toString().replace('\\', '/').toLowerCase(Locale.ROOT).contains("/" + rule + "/");
    }
}
