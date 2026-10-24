package com.verifone.cat.provision;

/**
 * The string escaping of Python's {@code json.dumps} (with its default {@code ensure_ascii}), so the canonical
 * manifest and the stamp are the same bytes the Python CAT writes. Package-private: nothing outside the
 * provisioning code needs it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Json {

    private Json() {}

    /** A JSON string literal for {@code text}, quoted, escaped as Python escapes it. */
    static String string(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
