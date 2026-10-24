package com.verifone.cat.validate.tlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code TLOG-RULE.txt}: the grammar, the seed, path resolution and the drift check (DESIGN.md §5.4).
 *
 * <p>Ported from the Python CAT's {@code tests/unit/test_validate_tlog.py}, rule half. Environment is
 * passed as a map, so nothing here touches the process environment. Not ported: {@code write_seed}
 * and its JSON file (the seed is a Java literal), and the rig-object lookup (no rig field exists).
 */
class TlogRuleTest {

    /** {@code TLOG-RULE.txt} verbatim: two lines, CRLF between them, no trailing newline. */
    static final String RULE_TEXT = "TAG_NAMES=trUniqueSN;posNum;termMsgSN;trSeq;date;duration;storeNumber;trpcBatchNr;"
            + "trpcSeqNr;trpcAuthDateTime;trpcRefNum;till;trpcHostID;trlPrcOvrd;trpcAuthCode;"
            + "trpcAuthSrc;trpcTicket;trpcEntryMeth;trlFuel;trlFuelSeq;trpcmMerchID;trpcmTermID;"
            + "trpcAcquirerBatchNr;trlCwCode;clockCashier;clockCashier;clockDate;trpcAccount;"
            + "trlMatchMixes;uniqueID;timeStamp;trpcTraceNr;\r\n"
            + "ATTRIBUTE_NAMES=seq;level;name;sysid;empNum;posNum;period;drawer;cat;custDOB;"
            + "trpUnsettledChange;refundTrans;timeStamp;dob;custAge;";

    private static final Map<String, String> NO_ENV = Map.of();

    @Test
    void parsesTheRealRuleFileGrammar() {
        Map<String, List<String>> parsed = TlogRule.parseText(RULE_TEXT);
        assertEquals(Set.of("TAG_NAMES", "ATTRIBUTE_NAMES"), parsed.keySet());
        // 32 entries, 31 distinct: clockCashier is in the file twice. Order and the duplicate are kept.
        assertEquals(32, parsed.get("TAG_NAMES").size());
        assertEquals(31, new LinkedHashSet<>(parsed.get("TAG_NAMES")).size());
        assertEquals(2, parsed.get("TAG_NAMES").stream().filter("clockCashier"::equals).count());
        assertEquals(15, parsed.get("ATTRIBUTE_NAMES").size());
        assertEquals("trUniqueSN", parsed.get("TAG_NAMES").get(0));
        assertEquals("custAge", parsed.get("ATTRIBUTE_NAMES").get(14));
    }

    @Test
    void trailingSeparatorDoesNotProduceAnEmptyName() {
        for (List<String> names : TlogRule.parseText(RULE_TEXT).values()) {
            assertFalse(names.contains(""), "an empty ignore-name would match every element");
        }
    }

    @Test
    void splitsOnTheFirstEqualsOnly() {
        assertEquals(Map.of("TAG_NAMES", List.of("a=b", "c")), TlogRule.parseText("TAG_NAMES=a=b;c;"));
    }

    @Test
    void unknownDialectsParseRatherThanExplode() {
        assertEquals(Map.of("KEY_NAMES", List.of("a", "b")), TlogRule.parseText("KEY_NAMES=a;b;"));
    }

    @Test
    void committedSeedIsWhatTheModuleExposes() {
        TlogRule seed = TlogRule.SEEDED;
        assertEquals(new LinkedHashSet<>(seed.tagNames()), TlogRule.IGNORE_TAGS);
        assertEquals(new LinkedHashSet<>(seed.attributeNames()), TlogRule.IGNORE_ATTRIBUTES);
        assertEquals(31, TlogRule.IGNORE_TAGS.size());
        assertEquals(15, TlogRule.IGNORE_ATTRIBUTES.size());
        assertFalse(seed.sha256().isEmpty(), "the seed must record the bytes it was parsed from");
        assertFalse(seed.commit().isEmpty(), "the seed must record which petroapps commit it came from");
    }

    @Test
    void importDoesNotDependOnAPetroappsCheckout() {
        assertTrue(TlogRule.resolvePath(null, NO_ENV).isEmpty());
        assertEquals(TlogRule.IGNORE_TAGS, TlogRule.parseKey("TAG_NAMES", null, NO_ENV, true));
    }

    @Test
    void sourcePathIsResolvedNotHardcoded(@TempDir Path dir) throws IOException {
        Path rule = dir.resolve("TLOG-RULE.txt");
        Files.writeString(rule, "TAG_NAMES=only;\nATTRIBUTE_NAMES=alsoOnly;", StandardCharsets.UTF_8);

        // 1. an explicit path wins over everything
        assertEquals(Set.of("only"), TlogRule.parseKey("TAG_NAMES", rule, Map.of(), true));

        // 2. the environment variable naming the file
        assertEquals(Set.of("only"),
                TlogRule.parseKey("TAG_NAMES", null, Map.of(TlogRule.ENV_RULE_PATH, rule.toString()), true));

        // 3. a checkout root
        Path nested = dir.resolve("petroapps").resolve("AutomationTests").resolve("rulefiles").resolve("tlog");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("TLOG-RULE.txt"), "TAG_NAMES=fromRoot;", StandardCharsets.UTF_8);
        assertEquals(Set.of("fromRoot"), TlogRule.parseKey("TAG_NAMES", null,
                Map.of(TlogRule.ENV_CORPUS_ROOT, dir.resolve("petroapps").toString()), true));
    }

    @Test
    void missingKeyNamesTheFileAndTheKeysItDidFind(@TempDir Path dir) throws IOException {
        Path rule = dir.resolve("TLOG-RULE.txt");
        Files.writeString(rule, "KEY_NAMES=a;", StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TlogRule.parseKey("TAG_NAMES", rule, NO_ENV, true));
        assertTrue(e.getMessage().contains("TAG_NAMES"), e.getMessage());
    }

    @Test
    void aSeedOnlyLookupRefusesWhenNoFileIsAllowedToBeMissing() {
        assertThrows(IllegalStateException.class, () -> TlogRule.parseKey("TAG_NAMES", null, NO_ENV, false));
    }

    @Test
    void parseLiveRecordsTheProvenanceOfTheBytesItRead(@TempDir Path dir) throws IOException {
        Path rule = dir.resolve("TLOG-RULE.txt");
        byte[] bytes = RULE_TEXT.getBytes(StandardCharsets.UTF_8);
        Files.write(rule, bytes);
        TlogRule parsed = TlogRule.parseLive(rule, "deadbeef");
        assertEquals(TlogRule.parseText(RULE_TEXT).get("TAG_NAMES"), parsed.tagNames());
        assertEquals("deadbeef", parsed.commit());
        assertEquals(TlogRule.sha256Hex(bytes), parsed.sha256());
    }

    @Test
    void seedDriftIsReportedNotSilentlyTolerated(@TempDir Path dir) throws IOException {
        Path rule = dir.resolve("TLOG-RULE.txt");
        Files.writeString(rule, "TAG_NAMES=date;newVolatileField;\nATTRIBUTE_NAMES=seq;", StandardCharsets.UTF_8);
        TlogRule.SeedDrift drift = TlogRule.verifySeed(rule, NO_ENV);
        assertTrue(drift.checked());
        assertFalse(drift.inSync());
        assertTrue(drift.tagsAdded().contains("newVolatileField"));
        assertTrue(drift.describe().contains("TlogRule.SEEDED"), drift.describe());
    }

    @Test
    void seedDriftCheckIsANoOpWithoutACheckout() {
        TlogRule.SeedDrift drift = TlogRule.verifySeed(null, NO_ENV);
        assertFalse(drift.checked());
        assertTrue(drift.inSync(), "nothing to disagree with");
    }

    @Test
    void committedSeedMatchesTheLiveRuleFile() {
        Path live = Path.of("C:\\gitrepos\\petroapps\\AutomationTests\\rulefiles\\tlog\\TLOG-RULE.txt");
        boolean reachable = Files.isRegularFile(live) || System.getenv(TlogRule.ENV_CORPUS_ROOT) != null;
        Assumptions.assumeTrue(reachable, "no petroapps checkout reachable: the CI case; the seed stands alone");
        TlogRule.SeedDrift drift = TlogRule.verifySeed(Files.isRegularFile(live) ? live : null, System.getenv());
        assertTrue(drift.inSync(), drift.describe());
    }
}
