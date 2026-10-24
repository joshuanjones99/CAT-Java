package com.verifone.cat.launch;

/**
 * Step 5, Tests Run: the pytest session and its event stream. Unavailable.
 *
 * <p>Needs the event socket server ({@code cat.telemetry.server}, which the Python launcher points
 * the session at through {@code CAT_EVENT_SINK}) and the subprocess relay ({@code cat/launch/relay.py}).
 * CAT-Java has the file transport only ({@code report.events.EventEmitter}). Until a run's tests
 * can be driven and their events teed here, {@link RunLauncher} does not start pytest.
 */
final class TestsRunStep extends UnportedStep {

    TestsRunStep() {
        super("src/cat/launch/steps/tests_run.py",
                "the event socket server (cat.telemetry.server; CAT-Java has the file transport only) "
                        + "and the pytest subprocess relay (cat/launch/relay.py)");
    }

    @Override
    public String name() {
        return LaunchStepNames.TESTS;
    }
}
