package com.verifone.cat.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.verifone.cat.authoring.StepDef;
import com.verifone.cat.authoring.StepLibrary;

/**
 * The Steps tab's filter and the insert of a phrase's example line, as {@code cat/web/pages/authoring.py}
 * does them ({@code step_options}, {@code insert_example}).
 *
 * <p>The browser's cursor is not known to the server, so an example always goes under the draft's last
 * non-blank line, which is what the Python does when it has no cursor.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class AuthoringSteps {

    private AuthoringSteps() {}

    /** The phrases whose phrase, example, group and docstring contain every word of {@code filter}, in library order. */
    static List<StepDef> matching(String filter) {
        List<String> words = new ArrayList<>();
        for (String word : (filter == null ? "" : filter).toLowerCase(Locale.ROOT).strip().split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        List<StepDef> out = new ArrayList<>();
        for (StepDef step : StepLibrary.steps()) {
            String haystack = (step.display() + " " + step.example() + " " + step.group() + " " + step.doc())
                    .toLowerCase(Locale.ROOT);
            if (words.stream().allMatch(haystack::contains)) {
                out.add(step);
            }
        }
        return out;
    }

    /** {@code text} with {@code example} on a new line, indented four spaces, under its last non-blank line. */
    static String insertExample(String text, String example) {
        String[] lines = text.split("\n", -1);
        int row = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                row = i;
            }
        }
        int end = 0;
        for (int i = 0; i < row; i++) {
            end += lines[i].length() + 1;
        }
        end += lines[row].length();
        return text.substring(0, end) + "\n    " + example + text.substring(end);
    }
}
