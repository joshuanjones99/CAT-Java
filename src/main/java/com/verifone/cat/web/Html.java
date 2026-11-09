package com.verifone.cat.web;

/**
 * HTML escaping, and the few fragments every page writes the same way. Every value that reaches a page from the POS
 * or the shim goes through {@link #esc}.
 */
final class Html {

    private Html() {}

    /** One {@code <option>} of a {@code <select>}; both the value and the text are escaped here. */
    static String option(String value, String text, boolean selected) {
        return "<option value=\"" + esc(value) + "\"" + (selected ? " selected" : "") + ">" + esc(text) + "</option>";
    }

    /**
     * A status tag (DESIGN.md: always a word, never colour alone).
     *
     * @param tone {@code ok}, {@code warn}, {@code error}, {@code run} or {@code muted}
     * @param word the word shown, escaped here
     */
    static String badge(String tone, String word) {
        return "<span class=\"badge " + tone + "\">" + esc(word) + "</span>";
    }

    static String esc(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
