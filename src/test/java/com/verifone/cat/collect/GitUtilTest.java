package com.verifone.cat.collect;

import static com.verifone.cat.collect.PetroappsFixture.CASE;
import static com.verifone.cat.collect.PetroappsFixture.commit;
import static com.verifone.cat.collect.PetroappsFixture.files;
import static com.verifone.cat.collect.PetroappsFixture.git;
import static com.verifone.cat.collect.PetroappsFixture.gitWithInput;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link GitUtil}'s batch object read and its process runner, against a throwaway repository. */
class GitUtilTest {

    @BeforeAll
    static void needsGit() {
        Assumptions.assumeTrue(PetroappsFixture.gitAvailable(), "git is not on PATH");
    }

    @TempDir
    Path repo;

    @Test
    void oneBatchReadsUniqueBlobsIncludingEmptyAndBinary() {
        git(repo, "init", "-q");
        List<byte[]> contents = List.of(
                new byte[0],
                new byte[] {0, (byte) 0xff, '\n', 'h', 'e', 'a', 'd', 'e', 'r', '\n'},
                "no final newline".getBytes(StandardCharsets.UTF_8));
        List<String> shas = contents.stream()
                .map(data -> new String(gitWithInput(repo, data, "hash-object", "-w", "--stdin"),
                        StandardCharsets.UTF_8).strip())
                .toList();
        List<String> asked = new java.util.ArrayList<>(shas);
        asked.addAll(shas); // the same IDs twice: read once
        Map<String, byte[]> blobs = GitUtil.readBlobs(repo, asked);
        Map<String, byte[]> expected = new LinkedHashMap<>();
        for (int i = 0; i < shas.size(); i++) {
            expected.put(shas.get(i), contents.get(i));
        }
        assertEquals(expected.keySet(), blobs.keySet());
        for (String sha : shas) {
            assertArrayEquals(expected.get(sha), blobs.get(sha));
        }
        assertEquals(0, GitUtil.readBlobs(repo, List.of()).size());
    }

    @Test
    void aMissingObjectIsReported() {
        git(repo, "init", "-q");
        CollectException e = assertThrows(CollectException.class,
                () -> GitUtil.readBlobs(repo, List.of("a".repeat(40))));
        assertTrue(e.getMessage().contains("git cat-file"));
    }

    @Test
    void anIdThatIsNotAFullLowercaseObjectIdIsRefused() {
        git(repo, "init", "-q");
        assertThrows(IllegalArgumentException.class, () -> GitUtil.readBlobs(repo, List.of("ABCDEF")));
    }

    @Test
    void theCommitTreeHoldsTheFilesTheFixtureWroteAndNoOthers() {
        git(repo, "init", "-q");
        String head = commit(repo, "refs/heads/master", files(
                CASE + "/PETROCORE-1/PETROCORE-1-TestCase.py", "# one\n",
                "unrelated/file.txt", "not collected"));
        String listing = git(repo, "ls-tree", "-r", "--name-only", head);
        assertEquals(CASE + "/PETROCORE-1/PETROCORE-1-TestCase.py\nunrelated/file.txt", listing);
    }
}
