package com.verifone.cat.closeday;

import java.util.Optional;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The Commander's {@code /var/log/messages}, read over SSH from a line mark onward. A port of the
 * Python's {@code petrotest.close_day.CommanderLog}.
 *
 * <p>Only lines past the mark belong to one close: an earlier close's success or cancel sits before it.
 * A log caught mid-rotation (gone for a moment) is "no outcome yet", read again on the next poll.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The two outcome lines and the log path are the lab
 * Commander's, as HANDOFF.md 1.60 and 1.166 read them.
 */
final class CommanderLog implements AutoCloseable {

    /** The Commander's system log. */
    static final String MESSAGES = "/var/log/messages";

    /** The close's last state change, {@code PeriodCloseProcessor}'s "Changing state from RELEASE CLIENTS to IDLE". */
    static final String CLOSED = "RELEASE CLIENTS to IDLE";

    /** {@code LockClientsState}'s line when a client never acknowledged the lock. */
    static final String CANCELLED = "PERIOD CLOSE CANCELED";

    /**
     * {@code LockServicesState}'s line when a service vetoes the close, its resource key printed as is.
     * The close then goes on to {@link #CLOSED} like a completed one, so it is a cancel too (HANDOFF.md 1.166).
     */
    static final String CANCELLED_BY = "PERIOD_CLOSE_CANCELED_BY";

    /** {@code ClosePeriodNowMgr}'s refusal while an earlier close never finished. */
    static final String IN_PROGRESS = "Close already in progress";

    private final Transport transport;
    private int mark;

    CommanderLog(Transport transport) {
        this.transport = transport;
    }

    /** Whether an outcome line is a cancelled close, by a client or by a service. */
    static boolean cancelled(String line) {
        return line.contains(CANCELLED) || line.contains(CANCELLED_BY);
    }

    /** Notes the log's current length: only lines after it belong to this close. */
    void mark() {
        mark = Integer.parseInt(run("wc -l < " + MESSAGES).strip());
    }

    /**
     * The first outcome line past the mark, or empty while there is none yet.
     *
     * @throws DeviceError when the log cannot be read for a reason other than its absence
     */
    Optional<String> outcome() {
        String newLines;
        try {
            newLines = linesSinceMark();
        } catch (DeviceError e) {
            if (String.valueOf(e.getMessage()).contains("No such file or directory")) {
                return Optional.empty();
            }
            throw e;
        }
        return newLines.lines()
                .filter(line -> cancelled(line) || line.contains(CLOSED))
                .findFirst()
                .map(String::strip);
    }

    /** Whether a line past the mark holds {@code text}. */
    boolean sinceMark(String text) {
        return linesSinceMark().contains(text);
    }

    /** The log past the mark, or the whole log when it rotated since the mark. */
    String linesSinceMark() {
        return run("f=" + MESSAGES + "; if [ $(wc -l < $f) -lt " + mark + " ]; then cat $f; "
                + "else tail -n +" + (mark + 1) + " $f; fi");
    }

    private String run(String command) {
        CommandResult result = transport.run(command, CommanderAccess.SSH_TIMEOUT);
        if (!result.ok()) {
            throw new DeviceError("`" + command + "` exited " + result.exitStatus() + ": "
                    + result.stderr().strip());
        }
        return result.stdout();
    }

    @Override
    public void close() {
        transport.close();
    }
}
