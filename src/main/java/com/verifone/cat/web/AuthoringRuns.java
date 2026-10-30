package com.verifone.cat.web;

import java.nio.file.Path;

import com.verifone.cat.authoring.SessionException;
import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.runservice.RunStart;

/**
 * How the Authoring page starts a replay or a capture as an ordinary run, and learns when it has ended. The Python's
 * {@code CatService.start_replay} and {@code form.replay_config}, seen from the page.
 *
 * <p>{@link #over} is the production one, over the Run page's own {@link RunService} and the Config tab's
 * {@link ConfigForm}: a replay goes through the same service, so it shows on the Run page and in Results, and is refused
 * while another run is going. The page notices the end by asking {@link #busy} when it is drawn (the Python's pages draw
 * on a timer too), then reads the run's id.
 *
 * <p>The replay's base is the form's whole config ({@link RunStart#replayBase}: the saved choices, the page's Downloads,
 * the default simulators and connections, {@code provision_register} on), as the Python's {@code replay_config}.
 * Deviation: the Python's replay takes the form's upgrade mode; here a replay is always {@code upgrade skip}, so
 * replaying an authored case never installs a build.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): a replay has run only against fakes.
 */
interface AuthoringRuns {

    /**
     * The Run tab's choices, for a run that replays authored cases instead of a plan.
     *
     * @throws SessionException when there is nowhere to run (no rig named)
     */
    LaunchConfig baseConfig();

    /** Starts {@code config}: the refusal, or null when it started. */
    String start(LaunchConfig config);

    /** Whether a run is in progress. */
    boolean busy();

    /** The last started run's id once it has made its folder, else null. */
    String lastRunId();

    /** Where each run's {@code <run-id>/events.ndjson} is. */
    Path resultsRoot();

    /** No run service: Replay and Capture are refused, saying why. */
    static AuthoringRuns unwired(Path resultsRoot) {
        return new AuthoringRuns() {
            @Override
            public LaunchConfig baseConfig() {
                throw new SessionException("this page has no run service to replay on: start CAT-Java's web UI with "
                        + "--rig to replay or capture");
            }

            @Override
            public String start(LaunchConfig config) {
                return "this page has no run service to replay on";
            }

            @Override
            public boolean busy() {
                return false;
            }

            @Override
            public String lastRunId() {
                return null;
            }

            @Override
            public Path resultsRoot() {
                return resultsRoot;
            }
        };
    }

    /** Over the Run page's {@code run} service and the Config tab's {@code form}. */
    static AuthoringRuns over(RunService run, ConfigForm form, Path resultsRoot) {
        return new AuthoringRuns() {
            @Override
            public LaunchConfig baseConfig() {
                String rig = run.rig();
                if (rig == null || rig.isBlank()) {
                    throw new SessionException("No rig is named, so a run has nowhere to go. Start CAT-Java with --rig.");
                }
                // The form's whole config, as the Python's replay_config (provision_register on, the simulators and
                // connections, the branch, the Downloads), but never an install: a replay runs on the rig as it is.
                LaunchConfig whole;
                try {
                    whole = RunStart.replayBase(form, run.startContext());
                } catch (IllegalArgumentException e) {
                    throw new SessionException("The saved run choices will not make a run: " + e.getMessage());
                }
                return whole.toBuilder().upgradeMode(UpgradeMode.SKIP).build();
            }

            @Override
            public String start(LaunchConfig config) {
                if (run.view().busy()) {
                    return "a run is already in progress";
                }
                run.start(config);
                String notice = run.view().notice();
                return run.view().busy() ? null : notice == null || notice.isEmpty() ? "the run did not start" : notice;
            }

            @Override
            public boolean busy() {
                return run.view().busy();
            }

            @Override
            public String lastRunId() {
                RunService.View view = run.view();
                return view.snapshot() == null ? null : view.snapshot().runId();
            }

            @Override
            public Path resultsRoot() {
                return resultsRoot;
            }
        };
    }

    /** The results root for {@code env}: {@code CAT_RESULTS_DIR}, else the documented default. */
    static Path resultsRoot(java.util.Map<String, String> env) {
        return ReportPaths.resultsRoot(env);
    }
}
