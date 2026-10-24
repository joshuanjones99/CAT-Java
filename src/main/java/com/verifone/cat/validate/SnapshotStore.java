package com.verifone.cat.validate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The case directory holding one test's baselines (DESIGN.md §5.4).
 *
 * <p>Plain text, one file per baseline, in the case folder, named the way PATS names its own
 * files: {@code <ID>-<Kind>.<ext>}. A reviewer must be able to read a re-baseline in a pull
 * request without tooling, so nothing here is a database or a blob. Stored text uses {@code \n}
 * endings, so a checkout with CRLF conversion cannot fail a comparison.
 *
 * <p>Ported from the Python CAT's {@code petrotest.validate.snapshot}. The path names are the
 * same, so a Java run and a Python run see the same baselines. UNVERIFIED AGAINST HARDWARE.
 */
public record SnapshotStore(Path root, String base) {

    /** A CAT test file, in PATS's {@code <ID>-<Kind>.<ext>} convention. */
    private static final Pattern TESTCASE_STEM = Pattern.compile("^(.+)-TestCase$");

    /** Anything awkward in a filename on Windows or in a URL. */
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");

    /** The case directory and base for a test file: {@code .../PETROCORE-61338-TestCase.py}. */
    public static SnapshotStore forTestFile(Path testFile) {
        Path parent = testFile.getParent();
        return new SnapshotStore(parent == null ? Path.of("") : parent, caseId(testFile));
    }

    /**
     * {@code PETROCORE-61338-TestCase.py} gives {@code PETROCORE-61338}. Anything else gives its
     * own stem, so a scratch test keeps working with baselines named after itself.
     */
    public static String caseId(Path testFile) {
        String stem = stem(testFile);
        var match = TESTCASE_STEM.matcher(stem);
        return match.matches() ? match.group(1) : safe(stem);
    }

    /**
     * One baseline's filename. {@code compose("PETROCORE-61338", "-CATReceipt.txt", "void")} gives
     * {@code PETROCORE-61338-CATReceipt-void.txt}: the variant is the second receipt in one test.
     */
    public static String compose(String base, String suffix, String variant) {
        int dot = suffix.indexOf('.');
        String stem = dot < 0 ? suffix : suffix.substring(0, dot);
        String tail = dot < 0 ? "" : suffix.substring(dot);
        if (!variant.isEmpty()) {
            stem = stem + "-" + safe(variant);
        }
        return base + stem + tail;
    }

    /**
     * Makes a name safe for a filename. Runs of unsafe characters become {@code _}, and leading
     * and trailing underscores are dropped. An empty result becomes {@code unnamed}.
     */
    public static String safe(String name) {
        String cleaned = UNSAFE.matcher(name).replaceAll("_");
        int start = 0;
        int end = cleaned.length();
        while (start < end && cleaned.charAt(start) == '_') {
            start++;
        }
        while (end > start && cleaned.charAt(end - 1) == '_') {
            end--;
        }
        String trimmed = cleaned.substring(start, end);
        return trimmed.isEmpty() ? "unnamed" : trimmed;
    }

    /**
     * The parameter of a parametrised test, sanitised: {@code test_card[EMV AMEX/chip]} gives
     * {@code EMV_AMEX_chip}, and an unparametrised test gives {@code ""}.
     */
    public static String param(String testName) {
        int open = testName.indexOf('[');
        if (open < 0) {
            return "";
        }
        String after = testName.substring(open + 1);
        int close = after.lastIndexOf(']');
        String inside = close < 0 ? "" : after.substring(0, close);
        return inside.isEmpty() ? "" : safe(inside);
    }

    /** The baseline's path for {@code key} (the variant, or {@code ""}) and {@code suffix}. */
    public Path path(String key, String suffix) {
        return root.resolve(compose(base, suffix, key));
    }

    /**
     * The baseline's text with {@code \r\n} folded to {@code \n}, or {@code null} if no file
     * exists. A missing file is not an error here: the caller decides what missing means.
     */
    public String read(String key, String suffix) {
        Path target = path(key, suffix);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read snapshot " + target, e);
        }
    }

    /** Writes {@code content} with a trailing {@code \n}, creating the folder if needed. */
    public Path write(String key, String suffix, String content) {
        Path target = path(key, suffix);
        String text = content.endsWith("\n") ? content : content + "\n";
        try {
            Files.createDirectories(target.getParent() == null ? Path.of("") : target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write snapshot " + target, e);
        }
        return target;
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
