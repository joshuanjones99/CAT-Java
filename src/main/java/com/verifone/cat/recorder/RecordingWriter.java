package com.verifone.cat.recorder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

import com.verifone.cat.authoring.PyJson;

/**
 * Appends recording lines and flushes each one. Port of {@code petrotest.recorder.format.RecordingWriter}.
 *
 * <p>A {@code tap} line is numbered as it is written: {@code seq} is its 1-based place among the taps, and comes second,
 * after {@code type}. Each line is {@code json.dumps(line, ensure_ascii=False)}, so a Java recording reads byte for byte
 * as the Python's.
 */
public final class RecordingWriter {

    private final Writer stream;
    private int taps;

    public RecordingWriter(Writer stream) {
        this.stream = stream;
    }

    /** How many taps have been written. */
    public synchronized int taps() {
        return taps;
    }

    /** Writes {@code line} and flushes. */
    public synchronized void write(Map<String, Object> line) {
        Map<String, Object> out = line;
        if ("tap".equals(line.get("type"))) {
            taps++;
            out = new LinkedHashMap<>();
            out.put("type", "tap");
            out.put("seq", taps);
            for (Map.Entry<String, Object> entry : line.entrySet()) {
                if (!"type".equals(entry.getKey())) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        try {
            stream.write(PyJson.dumps(out, false) + "\n");
            stream.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
