package com.verifone.cat.pos;

import java.util.List;

/**
 * A parameterised accessor for IDs that encode site content, not structure (such as a product
 * name). Port of {@code petrotest.pos.locator_types.ContentFamily}.
 *
 * <p>Freezing those as constants would encode one site's PLU file into the harness, so they are
 * functions of a label. The prefix lives in the generated catalogue; tests pass product names.
 * Where the slug can contain an ampersand, both the raw and {@code &amp;} spellings are offered.
 */
public record ContentFamily(String prefix, String name, String group, List<String> members) {

    public ContentFamily {
        members = List.copyOf(members);
    }

    /** A family with no member list, as the generated catalogue declares them. */
    public ContentFamily(String prefix, String name, String group) {
        this(prefix, name, group, List.of());
    }

    /** The concrete locator for {@code label} under this family's prefix. */
    public Locator of(String label) {
        return of(label, "");
    }

    /** The concrete locator for {@code label} with an optional suffix such as {@code "Label"}. */
    public Locator of(String label, String suffix) {
        String slug = label.replace(" ", "_");
        String tail = suffix.isEmpty() ? "" : "_" + suffix;
        String value = prefix + slug + tail;
        List<String> alternates;
        if (value.contains("&") && !value.contains("&amp;")) {
            alternates = List.of(value.replace("&", "&amp;"));
        } else if (value.contains("&amp;")) {
            alternates = List.of(value.replace("&amp;", "&"));
        } else {
            alternates = List.of();
        }
        return Locator.of(
                value,
                Locator.Kind.ID,
                name + "(" + LocatorTypes.pyRepr(label) + ")",
                group,
                0,
                false,
                false,
                null,
                null,
                alternates,
                "");
    }
}
