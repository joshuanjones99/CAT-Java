package com.verifone.cat.runservice;

import java.nio.file.Path;
import java.util.Optional;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.LaunchResult;

/**
 * The part of a run the web service drives: start it, pause it, stop it, and say where its folder
 * is. {@link LaunchEngine} is the production one, over {@code launch.RunLauncher}. A test passes a
 * fake that writes its own {@code events.ndjson}, so no step and no device is ever reached.
 *
 * <p>One engine launches one run. {@link #launch} blocks for the whole run and is called from one
 * thread; the other methods may be called from any thread.
 */
public interface RunEngine {

    /** Runs the whole launch and returns what it came to. Throws only when the run cannot start. */
    LaunchResult launch(LaunchConfig config);

    /** The run's folder once {@link #launch} has made it; empty before that. */
    Optional<Path> runFolder();

    /** Pauses the run before its next step. */
    void pause();

    /** Continues a paused run. */
    void resume();

    /** Whether the run is paused now. */
    boolean paused();

    /** Stops the run before its next step, or at once if paused. */
    void stop();
}
