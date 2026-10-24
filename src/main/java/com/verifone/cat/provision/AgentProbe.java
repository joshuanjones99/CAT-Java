package com.verifone.cat.provision;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Whether the POS-resident agent and its shim are answering (DESIGN.md 6.1). A port of
 * {@code petrotest.provision.verify.AgentProbe}.
 *
 * <p>Python never speaks RMI (DESIGN.md 4.2 rule 6), so "the RMI agent answers" is a status round-trip through the
 * shim's {@code /status}. {@link Http} is the live probe; {@link Null} is for the Commander, where the agent does
 * not run and the check does not apply.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface AgentProbe {

    /** False when there is nothing to probe. {@link Null} says so; the settle step then does not run. */
    default boolean applicable() {
        return true;
    }

    boolean shimAnswers(Duration timeout);

    boolean rmiAgentAnswers(Duration timeout);

    /** The Commander target: no register to probe, so both answers are true and {@link #applicable()} is false. */
    final class Null implements AgentProbe {

        @Override
        public boolean applicable() {
            return false;
        }

        @Override
        public boolean shimAnswers(Duration timeout) {
            return true;
        }

        @Override
        public boolean rmiAgentAnswers(Duration timeout) {
            return true;
        }
    }

    /** The live probe of a register's HTTP shim: {@code GET /health}, then {@code GET /status}. */
    final class Http implements AgentProbe {

        private final String host;
        private final int port;

        public Http(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean shimAnswers(Duration timeout) {
            return get("/health", timeout);
        }

        @Override
        public boolean rmiAgentAnswers(Duration timeout) {
            return get("/status", timeout);
        }

        private boolean get(String path, Duration timeout) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
                    .timeout(timeout)
                    .GET()
                    .build();
            try {
                HttpResponse<Void> response = HttpClient.newBuilder().connectTimeout(timeout).build()
                        .send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode() == 200;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
