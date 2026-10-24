package com.verifone.cat.build;

/**
 * One child of an Artifactory folder listing.
 *
 * <p>{@code uri} is relative to the folder listed, with its leading slash ({@code "/master142"}).
 * {@code lastModified} is the ISO-8601 string Artifactory reports, kept as a string.
 * {@code ""} when the listing carries no time: the plain storage listing does not, and only
 * {@link ArtifactoryClient#listTree} does. This class does not decide what order builds go
 * in; {@link BuildCatalogue} does.
 *
 * <p>The Python CAT's {@code petrotest.build.artifactory.ArtifactoryEntry}.
 */
public record ArtifactoryEntry(String uri, boolean folder, String lastModified) {

    /** {@link #uri()} with its leading slash stripped, for display and comparison. */
    public String name() {
        int start = 0;
        while (start < uri.length() && uri.charAt(start) == '/') {
            start++;
        }
        return uri.substring(start);
    }
}
