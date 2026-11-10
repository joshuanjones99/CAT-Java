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

    /**
     * The tab icons, by tab: outline drawings in the navigation glyphs' style for the Material icons the Python's
     * tabs show ({@code dashboard}, {@code tune}, {@code subject}, {@code list}, {@code query_stats}, {@code rule}).
     */
    private static final java.util.Map<String, String> TAB_ICONS = java.util.Map.of(
            "overview", "<rect x=\"3.5\" y=\"3.5\" width=\"7\" height=\"9\" rx=\"1\"/><rect x=\"13.5\" y=\"3.5\""
                    + " width=\"7\" height=\"5\" rx=\"1\"/><rect x=\"13.5\" y=\"11.5\" width=\"7\" height=\"9\" rx=\"1\"/>"
                    + "<rect x=\"3.5\" y=\"15.5\" width=\"7\" height=\"5\" rx=\"1\"/>",
            "config", "<path d=\"M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1\"/><circle cx=\"15\" cy=\"6\" r=\"2\"/>"
                    + "<circle cx=\"9\" cy=\"12\" r=\"2\"/><circle cx=\"17\" cy=\"18\" r=\"2\"/>",
            "log", "<path d=\"M4 6h16M4 10.5h16M4 15h16M4 19.5h10\"/>",
            "runs", "<path d=\"M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01\"/>",
            "stats", "<path d=\"M3.5 20.5h17\"/><path d=\"M5 16l4.5-5.5 4 3.5L19.5 6\"/>",
            "validations", "<path d=\"M4 7.5l2 2 4-4M4 16.5l2 2 4-4M13.5 7.5h6.5M13.5 16.5h6.5\"/>");

    /** A tab's icon, decorative (the tab's word names it), or nothing for a tab with none. */
    static String tabIcon(String tab) {
        String paths = TAB_ICONS.get(tab);
        if (paths == null) {
            return "";
        }
        return "<svg class=\"tab-icon\" viewBox=\"0 0 24 24\" width=\"20\" height=\"20\" fill=\"none\""
                + " stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\""
                + " aria-hidden=\"true\" focusable=\"false\">" + paths + "</svg>";
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
