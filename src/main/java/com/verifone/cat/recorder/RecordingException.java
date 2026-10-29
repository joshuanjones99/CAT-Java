package com.verifone.cat.recorder;

/** Not a version-1 recording, or a malformed one. The Python's {@code RecordingError}. */
public class RecordingException extends RuntimeException {

    public RecordingException(String message) {
        super(message);
    }

    public RecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
