package com.verifone.cat.runconfig;

import java.util.List;
import java.util.Set;

/**
 * What the Run scope reads from one case file: its tags, its PATS actions and whether it is a
 * {@code @plan_end} report case.
 *
 * @param tags each scenario's tags (its own and its feature's)
 * @param actions the PATS action names the file says, upper-cased; {@code null} when CAT cannot
 *     read them (a {@code .feature} of phrases, a {@code .py}, an unreadable file)
 */
public record CaseFacts(List<Set<String>> tags, Set<String> actions, boolean planEnd) {

    public CaseFacts {
        tags = List.copyOf(tags);
        actions = actions == null ? null : Set.copyOf(actions);
    }

    /** Its tags for the list, less its own {@code @<key>}, which the key column says. */
    public String tagWords(String key) {
        String own = ("@" + key).toUpperCase(java.util.Locale.ROOT);
        return tags.stream()
                .flatMap(Set::stream)
                .filter(tag -> !tag.toUpperCase(java.util.Locale.ROOT).equals(own))
                .distinct()
                .sorted()
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    /** Facts for a case whose file cannot be read: no tags, no actions, not judged. */
    public static CaseFacts unknown() {
        return new CaseFacts(List.of(Set.of()), null, false);
    }
}
