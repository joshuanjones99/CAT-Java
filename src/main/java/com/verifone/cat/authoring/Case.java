package com.verifone.cat.authoring;

/**
 * One case in a session: its id, its name, where it came from ({@code written}, {@code recorded} or {@code drafted}), and
 * the recording it came from, when there is one. Its steps live in {@code <id>.feature}, not here.
 */
public final class Case {

    private final String id;
    private String title;
    private String source;
    private String recording;

    Case(String id, String title, String source, String recording) {
        this.id = id;
        this.title = title;
        this.source = source;
        this.recording = recording;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    void title(String title) {
        this.title = title;
    }

    public String source() {
        return source;
    }

    void source(String source) {
        this.source = source;
    }

    /** The recording file's name in the session, or null. */
    public String recording() {
        return recording;
    }

    void recording(String recording) {
        this.recording = recording;
    }

    /** The draft's file name in the session: {@code <id>.feature}. */
    public String feature() {
        return id + ".feature";
    }
}
