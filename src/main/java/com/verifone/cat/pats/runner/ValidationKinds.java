package com.verifone.cat.pats.runner;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The actions that validate, what each validates, and how each names what it compared against. A
 * verdict for these goes to the case record's validations (the Run view's Validations list), passed or
 * failed. Ported from the Python {@code core.VALIDATION_KINDS}, {@code _COMPARES_NOTHING_ON_UPDATE} and
 * {@code validation_name}.
 */
public final class ValidationKinds {

    private static final Map<String, String> KINDS = Map.of(
            "VALIDATE_RECEIPT", "receipt",
            "VALIDATE_TRANSACTION", "tlog",
            "VALIDATE_REPORT", "report",
            "VALIDATE_PACKETS", "packets",
            "VALIDATE_PAYLOAD", "payload",
            "VALIDATE_OVERLAY", "overlay");

    /** Those that write a baseline instead of comparing in an update run: no verdict then. */
    private static final Set<String> COMPARES_NOTHING_ON_UPDATE =
            Set.of("VALIDATE_RECEIPT", "VALIDATE_TRANSACTION", "VALIDATE_REPORT", "VALIDATE_PACKETS");

    private ValidationKinds() {
    }

    /** The kind a validation action records as (receipt, tlog, ...), or empty for a non-validation. */
    public static Optional<String> kindOf(String actionName) {
        return Optional.ofNullable(KINDS.get(actionName.toUpperCase(Locale.ROOT)));
    }

    /** Whether the action writes its baseline instead of comparing when the run is an update run. */
    public static boolean comparesNothingOnUpdate(String actionName) {
        return COMPARES_NOTHING_ON_UPDATE.contains(actionName.toUpperCase(Locale.ROOT));
    }

    /**
     * What a validation compares against, as its row in the Validations view names it: its reference
     * file (the {@code SELECT_FILE}, else {@code SELECT_REFERENCE_FILE}); for packets, the simulator's
     * device and that file; for an overlay, the text it wants. Falls back to the action's description.
     */
    public static String validationName(RunnableAction action) {
        String name = action.name().toUpperCase(Locale.ROOT);
        if ("VALIDATE_OVERLAY".equals(name)) {
            StringBuilder wanted = new StringBuilder();
            for (String field : new String[] {"TITLE", "LINE1", "LINE2", "LINE3"}) {
                String text = action.get(field).strip();
                if (!text.isEmpty()) {
                    if (wanted.length() > 0) {
                        wanted.append(" / ");
                    }
                    wanted.append(text);
                }
            }
            return wanted.length() > 0 ? wanted.toString() : "an overlay";
        }
        String reference = action.get("SELECT_FILE");
        if (reference.isEmpty()) {
            reference = action.get("SELECT_REFERENCE_FILE");
        }
        if ("VALIDATE_PACKETS".equals(name)) {
            String device = action.get("DEVICE").toUpperCase(Locale.ROOT);
            return stripColonsAndSpaces(device + ": " + reference);
        }
        return reference.isEmpty() ? action.describe() : reference;
    }

    /** Python's {@code str.strip(": ")}: colons and spaces off both ends, nothing else. */
    private static String stripColonsAndSpaces(String text) {
        int begin = 0;
        int end = text.length();
        while (begin < end && isColonOrSpace(text.charAt(begin))) {
            begin++;
        }
        while (end > begin && isColonOrSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(begin, end);
    }

    private static boolean isColonOrSpace(char c) {
        return c == ':' || c == ' ';
    }
}
