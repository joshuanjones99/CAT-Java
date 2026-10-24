package com.verifone.cat.pats.runner.handlers.http;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.report.CaseRecord;

/** Test wiring for the HTTP families: in-memory case files, hand-built actions, and a run over a Commander. */
final class Harness {

    static final String KEY = "PETROCORE-137541";

    private Harness() {
    }

    /** An action as the runner reads it: name, position and {@code name, value, name, value, ...} fields. */
    record Act(String name, int index, List<RunnableAction.ActionField> fields) implements RunnableAction {
    }

    static Act act(int index, String name, String... pairs) {
        List<RunnableAction.ActionField> fields = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.add(new RunnableAction.ActionField(pairs[i], pairs[i + 1]));
        }
        return new Act(name, index, List.copyOf(fields));
    }

    /** A case of hand-built actions. */
    record Kase(String key, List<RunnableAction> actions) implements RunnableCase {
        @Override
        public String title() {
            return "a test case";
        }

        @Override
        public String source() {
            return "memory";
        }
    }

    static Kase kase(RunnableAction... actions) {
        return new Kase(KEY, List.of(actions));
    }

    /** Case files held in memory; a missing HTTP payload fails as PATS's lookup does. */
    static final class Files implements CaseFiles {
        final Map<String, byte[]> httpPayloads = new HashMap<>();
        final Map<String, byte[]> references = new HashMap<>();
        final Map<String, byte[]> rules = new HashMap<>();

        @Override
        public byte[] httpPayload(String name) {
            byte[] data = httpPayloads.get(name);
            if (data == null) {
                throw new PatsStepError("Payload file not found: Payloads\\HTTP\\" + name + " (no case holds it)");
            }
            return data;
        }

        @Override
        public Optional<byte[]> referencePayload(String name) {
            return Optional.ofNullable(references.get(name));
        }

        @Override
        public Optional<byte[]> payloadRule(String name) {
            return Optional.ofNullable(rules.get(name));
        }
    }

    /** The Commander fixture: a {@code PdkShim} on a loopback port, OTP from {@code otp}. */
    static PdkShim commander(LoopbackServer server, String otp) {
        Supplier<String> reader = otp == null ? null : () -> otp;
        return PdkShim.onPort(server.host(), server.port(), Duration.ofSeconds(5), reader);
    }

    /** A run of {@code kase} with the HTTP family registered over {@code context}, the Commander fixture given. */
    static CaseRun run(Path folder, RunnableCase kase, HttpContext context, Object commander) {
        ActionRegistry registry = new ActionRegistry();
        HttpFamily.register(registry, context);
        FixtureSource fixtures = name -> {
            if (name == FixtureName.COMMANDER_CONFIG && commander != null) {
                return commander;
            }
            throw new IllegalStateException("no fixture " + name);
        };
        CaseRecord record = new CaseRecord("test::" + kase.key(), kase.key(), folder.resolve(kase.key()),
                Instant.now());
        return new CaseRun(kase, record, registry, fixtures, Set.of(FixtureName.COMMANDER_CONFIG), new RunState());
    }

    /** The XML the Commander's {@code uuseradmin} payload file holds. */
    static final byte[] USERADMIN = ("<domain:userConfig xmlns:domain=\"urn:vfi-sapphire:np.domain.2001-07-01\">"
            + "<users/></domain:userConfig>").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** A pause that records what it was asked to wait, and waits for none of it. */
    static final class RecordingPause implements java.util.function.Consumer<Duration> {
        final List<Duration> waits = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void accept(Duration duration) {
            waits.add(duration);
        }
    }
}
