package com.verifone.cat.launch;

/**
 * A stage of a launch step whose work CAT-Java does not have: thrown by the production stage, caught by the step,
 * which reports it with {@link StepEmitter#unavailable} (a failure, never a pass). A test's recorder never throws it
 * unless it is testing the report.
 */
final class StageUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String pythonModule;
    private final String missing;

    /**
     * @param pythonModule the Python module the missing piece lives in
     * @param missing      what is missing, named for the operator
     */
    StageUnavailable(String pythonModule, String missing) {
        super("unavailable in CAT-Java: needs " + missing + " (Python: " + pythonModule + ")");
        this.pythonModule = pythonModule;
        this.missing = missing;
    }

    String pythonModule() {
        return pythonModule;
    }

    String missing() {
        return missing;
    }
}
