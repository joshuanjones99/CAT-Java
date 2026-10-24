package com.verifone.cat.pats.runner.handlers.cloudsettle;

/**
 * The Commander's system log, read since a mark: the seam for the Python's {@code close_day.CommanderLog}, which
 * reads {@code /var/log/messages} over SSH. The production reader is wired by the orchestrator; this package
 * only reads what it is given.
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
public interface CommanderLog extends AutoCloseable {

    /**
     * Marks the log's current end. Later reads return what was logged after it.
     *
     * @throws CommanderLogException when the log cannot be read
     */
    void mark();

    /**
     * The lines logged since the mark.
     *
     * @throws CommanderLogException when the log cannot be read
     */
    String linesSinceMark();

    /** Releases the reader. Never throws. */
    @Override
    void close();
}
