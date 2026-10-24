package com.verifone.cat.runservice;

import java.nio.file.Path;
import java.util.Optional;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.LaunchResult;
import com.verifone.cat.launch.RunContext;
import com.verifone.cat.launch.RunLauncher;

/**
 * {@link RunEngine} over one {@link RunLauncher}, which launches exactly one run. The service makes
 * a new one for every Start run.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the steps this launches reach the rig when the run is started on
 * one. Tests use a fake engine instead.
 */
public final class LaunchEngine implements RunEngine {

    private final RunLauncher launcher;

    public LaunchEngine(RunLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public LaunchResult launch(LaunchConfig config) {
        return launcher.launch(config);
    }

    @Override
    public Optional<Path> runFolder() {
        return launcher.activeRun().map(RunContext::runFolder);
    }

    @Override
    public void pause() {
        launcher.pause();
    }

    @Override
    public void resume() {
        launcher.resume();
    }

    @Override
    public boolean paused() {
        return launcher.paused();
    }

    @Override
    public void stop() {
        launcher.stop();
    }
}
