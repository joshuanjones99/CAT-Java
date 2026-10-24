package com.verifone.cat.provision;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Remote path helpers that match the Python CAT byte for byte.
 *
 * <p>{@link #compare} orders paths the way {@code PurePosixPath} does (component by component, not as strings),
 * because the manifest is sorted that way before it is hashed. {@link #shellQuote} is {@code shlex.quote}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RemotePaths {

    private static final Pattern SHELL_SAFE = Pattern.compile("[\\w@%+=:,./-]+");

    private RemotePaths() {}

    /** The components of a POSIX path, without the root, as {@code PurePosixPath.parts} gives them. */
    static List<String> parts(String path) {
        return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
    }

    /** {@code PurePosixPath} ordering: lexicographic over the components, a shorter prefix first. */
    public static int compare(String a, String b) {
        List<String> left = parts(a);
        List<String> right = parts(b);
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int c = left.get(i).compareTo(right.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    /** {@code shlex.quote}: bare when every character is shell-safe, else single-quoted. */
    public static String shellQuote(String text) {
        if (text.isEmpty()) {
            return "''";
        }
        if (SHELL_SAFE.matcher(text).matches()) {
            return text;
        }
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    /** The last component of a path, as {@code PurePosixPath.name}. */
    public static String name(String path) {
        List<String> parts = parts(path);
        return parts.isEmpty() ? "" : parts.get(parts.size() - 1);
    }
}
