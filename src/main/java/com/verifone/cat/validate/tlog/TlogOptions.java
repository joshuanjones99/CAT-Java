package com.verifone.cat.validate.tlog;

import java.util.HashSet;
import java.util.Set;

/**
 * What a TLog comparison ignores and how it compares values (the Python CAT's keyword arguments to
 * {@code tlog.canonicalise} and {@code tlog.compare}).
 *
 * <p>{@link #rule()} is the rule file alone, which is what {@code tlog.compare} uses by default.
 * {@link #catDefaults()} adds CAT's own ignores, which is what {@code assert_tlog} uses.
 *
 * @param caseInsensitiveValues PATS parity: values equal ignoring case are identical
 * @param pruneIgnoredSubtrees  false (the default) elides an ignored element's own text and keeps
 *                              its children compared; true drops the whole subtree
 */
public record TlogOptions(
        Set<String> ignoreTags,
        Set<String> ignoreAttributes,
        boolean caseInsensitiveValues,
        boolean pruneIgnoredSubtrees) {

    public TlogOptions {
        ignoreTags = Set.copyOf(ignoreTags);
        ignoreAttributes = Set.copyOf(ignoreAttributes);
    }

    /** The rule file's lists, case-insensitive values, no pruning. */
    public static TlogOptions rule() {
        return new TlogOptions(TlogRule.IGNORE_TAGS, TlogRule.IGNORE_ATTRIBUTES, true, false);
    }

    /** The rule file's lists plus CAT's additions: what {@code assert_tlog} compares with. */
    public static TlogOptions catDefaults() {
        Set<String> tags = new HashSet<>(TlogRule.IGNORE_TAGS);
        tags.addAll(TlogRule.CAT_IGNORE_TAGS);
        Set<String> attributes = new HashSet<>(TlogRule.IGNORE_ATTRIBUTES);
        attributes.addAll(TlogRule.CAT_IGNORE_ATTRIBUTES);
        return new TlogOptions(tags, attributes, true, false);
    }
}
