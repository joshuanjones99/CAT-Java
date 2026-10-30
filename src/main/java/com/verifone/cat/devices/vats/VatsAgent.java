package com.verifone.cat.devices.vats;

import java.util.List;
import java.util.Map;

/**
 * The ops of CAT's VATS agent ({@code agent.py}): one request, one reply. {@link AgentClient} is the real one, a Python
 * process under the staged SDK; tests drive {@link VatsPinpad} through an in-memory one as well.
 *
 * <p>A reply's {@code result} is a JSON object as Jackson reads it: strings, {@link Boolean}s, {@link Number}s and
 * {@link List}s. The helpers here read it the way the Python's {@code dict.get} does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface VatsAgent extends AutoCloseable {

    /**
     * Runs {@code op} in the agent and returns its result, or throws: {@link AgentError} when the SDK raised,
     * {@link com.verifone.cat.devices.DeviceUnreachable} when the agent is gone or silent.
     *
     * @param budgetSeconds how long the op itself may block (its own {@code timeout_ms}, say); the client adds its
     *                      round-trip margin
     * @param args          the op's keyword arguments
     */
    Map<String, Object> call(String op, double budgetSeconds, Map<String, Object> args);

    default Map<String, Object> call(String op) {
        return call(op, 0.0, Map.of());
    }

    default Map<String, Object> call(String op, Map<String, Object> args) {
        return call(op, 0.0, args);
    }

    /** Ends the agent; a second call does nothing. */
    @Override
    void close();

    /** {@code bool(result.get(key))}: absent, null, false, 0 and "" are false. */
    static boolean truthy(Map<String, Object> result, String key) {
        return truthy(result.get(key));
    }

    /** Python's truthiness for a JSON value. */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** {@code result.get("lines", [])}, each as a string. */
    static List<String> lines(Map<String, Object> result) {
        Object lines = result.get("lines");
        if (!(lines instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
