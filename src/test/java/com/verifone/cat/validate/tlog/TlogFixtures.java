package com.verifone.cat.validate.tlog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The committed TLog fixtures under {@code src/test/resources/validate/tlog/}, copied verbatim from the
 * Python CAT's {@code tests/fixtures/petroapps}, so every assertion runs against genuine petroapps XML.
 */
final class TlogFixtures {

    static final String CARWASH = "PETROCORE-10823-Tlog.xml";
    static final String LOYALTY = "PETROCORE-121630-Tlog.xml";

    private TlogFixtures() {}

    /** The fixture as a file on disk (the test classpath is a directory under {@code target/}). */
    static Path file(String name) {
        URL url = TlogFixtures.class.getClassLoader().getResource("validate/tlog/" + name);
        if (url == null) {
            throw new IllegalStateException("missing test resource validate/tlog/" + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("fixture is not on the filesystem: " + url, e);
        }
    }

    /** The fixture's text, with {@code \r\n} and {@code \r} folded to {@code \n}, as Python's {@code read_text}. */
    static String text(String name) {
        try {
            return Files.readString(file(name), StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture " + name, e);
        }
    }
}
