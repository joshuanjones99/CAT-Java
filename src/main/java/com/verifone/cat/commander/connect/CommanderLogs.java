package com.verifone.cat.commander.connect;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLog;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLogException;
import com.verifone.cat.provision.transport.Transport;

/**
 * The cloud settle's {@link CommanderLog} over the close-day family's reader of the Commander's
 * {@code /var/log/messages} ({@link com.verifone.cat.closeday.CommanderLog}), the Python's {@code close_day.CommanderLog}
 * that {@code cloud_snapshot._mark_commander} opens. A read that fails is a {@link CommanderLogException}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderLogs {

    private CommanderLogs() {}

    /** The log over {@code transport}, which it closes. */
    public static CommanderLog over(Transport transport) {
        com.verifone.cat.closeday.CommanderLog log = new com.verifone.cat.closeday.CommanderLog(transport);
        return new CommanderLog() {
            @Override
            public void mark() {
                try {
                    log.mark();
                } catch (DeviceError | NumberFormatException e) {
                    throw new CommanderLogException("the Commander log cannot be marked: " + e.getMessage(), e);
                }
            }

            @Override
            public String linesSinceMark() {
                try {
                    return log.linesSinceMark();
                } catch (DeviceError e) {
                    throw new CommanderLogException("the Commander log cannot be read: " + e.getMessage(), e);
                }
            }

            @Override
            public void close() {
                log.close();
            }
        };
    }
}
