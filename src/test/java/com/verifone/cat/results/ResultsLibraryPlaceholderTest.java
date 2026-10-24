package com.verifone.cat.results;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The library's placeholder check: an unmigrated node is left out of every rate, as the Python CAT does. */
class ResultsLibraryPlaceholderTest {

    @TempDir
    Path tmp;

    private Path repo() throws IOException {
        Path repo = tmp.resolve("repo");
        write(repo, "tests/suites/charity/PETROCORE-37043-TestCase.py",
                "import pytest\n@pytest.mark.unmigrated\ndef test_x(): pytest.fail('SKIPPED')\n");
        write(repo, "tests/suites/charity/PETROCORE-36847-TestCase.py", "def test_x(): pass\n");
        write(repo, "tests/suites/charity/PETROCORE-40000-TestCase.feature",
                "@unmigrated\nFeature: placeholder\n");
        write(repo, "tests/suites/charity/PETROCORE-41000-TestCase.feature", "Feature: real\n");
        return repo;
    }

    private static void write(Path repo, String relative, String text) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void aPlaceholderPythonNodeIsExcludedAndARealOneIsNot() throws IOException {
        var placeholder = ResultsLibrary.placeholderIn(repo());

        assertTrue(placeholder.test("tests/suites/charity/PETROCORE-37043-TestCase.py::test_x"));
        assertTrue(placeholder.test("tests/suites/charity/PETROCORE-37043-TestCase.py"));
        assertFalse(placeholder.test("tests/suites/charity/PETROCORE-36847-TestCase.py::test_x"));
    }

    @Test
    void aPlaceholderFeatureIsExcludedByItsTagAndARealFeatureIsNot() throws IOException {
        var placeholder = ResultsLibrary.placeholderIn(repo());

        assertTrue(placeholder.test("tests/suites/charity/PETROCORE-40000-TestCase.feature::Scenario"));
        assertFalse(placeholder.test("tests/suites/charity/PETROCORE-41000-TestCase.feature::Scenario"));
    }

    @Test
    void aMissingOrBlankNodeIsNotAPlaceholder() throws IOException {
        var placeholder = ResultsLibrary.placeholderIn(repo());

        assertFalse(placeholder.test("tests/suites/charity/PETROCORE-99999-TestCase.py::test_x"));
        assertFalse(placeholder.test(""));
        assertFalse(placeholder.test(null));
    }

    @Test
    void theEnvironmentLibraryReadsTheCheckoutNamedByCatRepoRoot() throws IOException {
        Path repo = repo();
        ResultsLibrary library = ResultsLibrary.fromEnvironment(Map.of(
                "CAT_REPO_ROOT", repo.toString(),
                "CAT_RESULTS_DIR", tmp.resolve("results").toString(),
                "CAT_RESULTS_INDEX", tmp.resolve("index.json").toString()));

        assertTrue(library.placeholder().test("tests/suites/charity/PETROCORE-37043-TestCase.py::test_x"));
        assertFalse(library.placeholder().test("tests/suites/charity/PETROCORE-36847-TestCase.py::test_x"));
    }
}
