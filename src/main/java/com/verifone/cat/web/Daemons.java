package com.verifone.cat.web;

import java.util.concurrent.ThreadFactory;

/**
 * The web UI's background threads, all made here: named, and daemon, so none of them keeps CAT alive once the
 * server has stopped.
 */
final class Daemons {

    private Daemons() {}

    /** A factory for an executor's threads, each named {@code name}. */
    static ThreadFactory factory(String name) {
        return work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Runs {@code work} on a new thread named {@code name}, and returns at once. */
    static void run(String name, Runnable work) {
        factory(name).newThread(work).start();
    }
}
