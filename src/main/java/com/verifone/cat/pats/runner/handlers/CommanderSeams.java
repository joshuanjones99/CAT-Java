package com.verifone.cat.pats.runner.handlers;

import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.closeday.CommanderAccess;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CloudSettle;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLog;
import com.verifone.cat.pats.runner.handlers.cloudsettle.CommanderLogException;
import com.verifone.cat.pats.runner.handlers.configuration.CloudSender;
import com.verifone.cat.pats.runner.handlers.configuration.StuckCloseRecovery;
import com.verifone.cat.pats.runner.handlers.http.CloudSite;
import com.verifone.cat.pats.runner.handlers.http.HttpReply;
import com.verifone.cat.pats.runner.handlers.http.ScenarioContext;
import com.verifone.cat.pats.runner.handlers.validation.ValidationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.w3c.dom.Element;

/**
 * The Commander's seams, over the one {@link PdkShim} a run holds and the close-day family's SSH and CGILink access:
 * the validation family's transaction and report reads, the configuration family's cloud sender and stuck-close
 * recovery, and the Commander log the cloud settle waits on.
 *
 * <p>A seam with no Commander behind it is {@code null} here and its family's default applies, which throws
 * {@link DeviceUnavailable}. Nothing in this class reports success for a read it did not make.
 */
final class CommanderSeams {

    /** The service ID key in the case's scenario context, as the HTTP family's token fill reads it. */
    static final String SERVICE_ID = "service_id";

    /** The {@code vsupportinfo} tag the Commander's service ID is read between. */
    private static final String SERVICE_ID_OPEN = "<storeServiceID>";
    private static final String SERVICE_ID_CLOSE = "</storeServiceID>";

    private CommanderSeams() {
    }

    /** The validation family's Commander reads, over the run's one Commander client. */
    static ValidationContext.CommanderReads reads(PdkShim pdk) {
        return new ValidationContext.CommanderReads() {
            @Override
            public List<String> listTransactionNumbers() {
                return pdk.listTransactionNumbers();
            }

            @Override
            public Element fetchTlogByTrseq(String trseq) {
                return pdk.fetchTlogByTrseq(trseq);
            }

            @Override
            public Element fetchReport(String cmd, String report, String period, String filename, String cashier,
                    String register) {
                return pdk.fetchReport(cmd, report, period, filename, cashier, register);
            }
        };
    }

    /**
     * The Commander's {@code storeServiceID}, read from {@code vsupportinfo}, or empty when the reply does not carry it
     * (the Python logs that and leaves the token as written).
     */
    static Optional<String> serviceIdOf(PdkShim pdk) {
        String text = pdk.sendCommand("vsupportinfo", "");
        int start = text.indexOf(SERVICE_ID_OPEN);
        int end = text.indexOf(SERVICE_ID_CLOSE);
        if (start < 0 || end < 0 || start >= end) {
            return Optional.empty();
        }
        return Optional.of(text.substring(start + SERVICE_ID_OPEN.length(), end).strip());
    }

    /**
     * The configuration family's cloud sender: the service ID from {@code vsupportinfo} (read once per case, kept in
     * the scenario context), and the one cloud write path, {@link CloudSettle}'s mark, the request and its wait. With no
     * C-Site client wired the send fails by name, after the service ID is read.
     *
     * <p>Known gap: {@link CloudSite#send} takes no headers, so the {@code X-ServiceId} and the other headers the case
     * sets are not sent through this seam. The run's client is {@code launch.StandardRegistry}'s, over the Commander's
 * cloud config; no request has gone this way on a rig yet.
     */
    static CloudSender cloudSender(PdkShim pdk, CloudSite cloud, CloudSettle settle) {
        return new CloudSender() {
            @Override
            public Optional<String> serviceId(CaseRun run) {
                Map<String, String> scenario = ScenarioContext.of(run);
                if (scenario.containsKey(SERVICE_ID)) {
                    return Optional.of(scenario.get(SERVICE_ID));
                }
                Optional<String> id = serviceIdOf(pdk);
                id.ifPresent(value -> scenario.put(SERVICE_ID, value));
                return id;
            }

            @Override
            public int send(CaseRun run, String method, String endpoint, Map<String, String> headers, String body) {
                if (cloud == null) {
                    throw new DeviceUnavailable("csite", "the C-Site cloud client is not wired to this run; "
                            + "CLOUD_HTTP_REQUEST " + method + " " + endpoint + " was not sent");
                }
                Optional<CommanderLog> mark = settle.mark(run, method);
                HttpReply reply;
                boolean answered = false;
                try {
                    reply = cloud.send(method, endpoint, body, false);
                    answered = true;
                } finally {
                    if (!answered) {
                        settle.release(mark);
                    }
                }
                settle.waitApplied(run, mark, reply.status(), endpoint);
                return reply.status();
            }
        };
    }

    /** The configuration family's stuck-close recovery, over the close-day family's {@link CloseDay}. */
    static StuckCloseRecovery stuckCloseRecovery(CloseDay closeDay) {
        if (closeDay == null) {
            return StuckCloseRecovery.UNBOUND;
        }
        return new StuckCloseRecovery() {
            @Override
            public boolean restartIfDeadlocked() {
                return closeDay.recoverStuckClose(true);
            }

            @Override
            public Optional<Path> keptDump() {
                return Optional.ofNullable(closeDay.keptDump());
            }
        };
    }

    /**
     * Opens the Commander's system log over a new SSH session for one cloud write's mark. A read that fails is a
     * {@link CommanderLogException}, which the cloud settle logs and waits past.
     */
    static Supplier<CommanderLog> commanderLogs(CommanderAccess access) {
        return () -> {
            com.verifone.cat.closeday.CommanderLog log = new com.verifone.cat.closeday.CommanderLog(access.ssh());
            return new CommanderLog() {
                @Override
                public void mark() {
                    guarded(log::mark);
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
        };
    }

    private static void guarded(Runnable read) {
        try {
            read.run();
        } catch (DeviceError e) {
            throw new CommanderLogException("the Commander log cannot be marked: " + e.getMessage(), e);
        }
    }
}
