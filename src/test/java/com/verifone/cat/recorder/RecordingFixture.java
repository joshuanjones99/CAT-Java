package com.verifone.cat.recorder;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.pos.Locator;

/**
 * Recordings built the way the recorder builds them: through {@link Capture.Session}, which maps and redacts, from ids
 * read out of the catalogue (no id is written in a test). The Python's {@code _record} and {@code _tap} in
 * {@code tests/unit/test_recorder.py}.
 */
public final class RecordingFixture {

    public static final String PASSWORD_PROMPT = "Enter Password For ID: 1";

    private RecordingFixture() {}

    /** A tap on {@code element} (a locator, an id, or null), with its prompt and own text nodes. */
    public static Map<String, Object> tap(Object element, String prompt, List<String> texts) {
        String id = element instanceof Locator loc ? loc.literals().get(0) : (String) element;
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("id", id);
        el.put("tag", "button");
        el.put("texts", texts == null ? List.of() : texts);
        el.put("text", "");
        Map<String, Object> tap = new LinkedHashMap<>();
        tap.put("element", el);
        tap.put("prompt", prompt);
        tap.put("at", 0);
        tap.put("event", "click");
        return tap;
    }

    public static Map<String, Object> tap(Object element) {
        return tap(element, null, null);
    }

    /** A tap whose element shows {@code text} (an express button's label, a review row). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withText(Map<String, Object> tap, String text) {
        ((Map<String, Object>) tap.get("element")).put("text", text);
        return tap;
    }

    /** The digits keyed on {@code key}, then {@code ok}. */
    public static List<Map<String, Object>> entry(Locator key, Locator ok, String digits, String prompt) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (char d : digits.toCharArray()) {
            out.add(tap(key.at(String.valueOf(d)), prompt, List.of(String.valueOf(d))));
        }
        out.add(tap(ok, prompt, null));
        return out;
    }

    /** A recording file of {@code taps}, written at {@code path}. */
    public static Path record(Path path, List<Map<String, Object>> taps, String kind, String initial,
            List<Map<String, Object>> extra, boolean footer) throws IOException {
        StringWriter stream = new StringWriter();
        RecordingWriter writer = new RecordingWriter(stream);
        Capture.Session session = new Capture.Session(writer, CatalogueMap.standard());
        int number = 0;
        for (Map<String, Object> tap : taps) {
            number++;
            Map<String, Object> payload = new LinkedHashMap<>(tap);
            payload.put("instance", "i");
            payload.put("seq", number);
            session.tap(payload, "", "/", false);
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("format", RecordingFormat.FORMAT);
        header.put("version", RecordingFormat.VERSION);
        header.put("started_at", "2026-09-18T00:00:00.000+00:00");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", kind);
        source.put("rig", "example");
        source.put("register", "topaz101");
        header.put("source", source);
        Map<String, Object> initialScreen = new LinkedHashMap<>();
        initialScreen.put("csr_function_text", initial);
        header.put("initial_screen", initialScreen);
        session.start(header);
        if (extra != null) {
            extra.forEach(writer::write);
        }
        if (footer) {
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("type", "footer");
            end.put("stopped_at", "2026-09-18T00:01:00.000+00:00");
            writer.write(end);
        }
        Files.writeString(path, stream.toString(), StandardCharsets.UTF_8);
        return path;
    }

    public static Path record(Path path, List<Map<String, Object>> taps) throws IOException {
        return record(path, taps, "rig", "CSR Func", null, true);
    }
}
