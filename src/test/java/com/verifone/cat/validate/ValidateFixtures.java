package com.verifone.cat.validate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Reads the committed receipt fixtures under {@code src/test/resources/validate/}.
 *
 * <p>The petroapps receipts are copied verbatim from the Python CAT's {@code tests/fixtures}, and
 * the charity baselines from its {@code tests/suites/charity}, so every assertion runs against
 * genuine corpus text. Text is read with universal newlines, as Python's {@code read_text} does.
 */
final class ValidateFixtures {

    static final String CARWASH_RECEIPT = "petroapps/PETROCORE-10823-Receipt.txt";
    static final String LOYALTY_RECEIPT = "petroapps/PETROCORE-121630-Receipt.txt";

    private ValidateFixtures() {}

    /** The text of a fixture, with {@code \r\n} and {@code \r} folded to {@code \n}. */
    static String text(String name) {
        try (InputStream in = resource("validate/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture " + name, e);
        }
    }

    /** The committed charity baselines, as a folder on disk. */
    static Path charityDir() {
        URL url = classpathUrl("validate/charity");
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("charity fixtures are not on the filesystem: " + url, e);
        }
    }

    /** One charity baseline's text, by its file name, e.g. {@code PETROCORE-61337-CATReceipt.txt}. */
    static String charity(String fileName) {
        return text("charity/" + fileName);
    }

    private static InputStream resource(String name) {
        InputStream in = ValidateFixtures.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("missing test resource " + name);
        }
        return in;
    }

    private static URL classpathUrl(String name) {
        URL url = ValidateFixtures.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalStateException("missing test resource folder " + name);
        }
        return url;
    }
}
