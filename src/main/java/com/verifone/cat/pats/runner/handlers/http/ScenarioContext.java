package com.verifone.cat.pats.runner.handlers.http;

import java.util.LinkedHashMap;
import java.util.Map;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.ScratchKey;

/**
 * The case's scenario context: the values {@code captureFields} captured and the {@code {name}} tokens the HTTP steps
 * fill from (PATS's {@code ScenarioTokenResolver}; {@code service_id} is the Commander's store service id).
 *
 * <p>Core change requested: this key belongs in {@link ScratchKey} beside the others, so every family reads the same
 * map (the configuration family's {@code CLOUD_HTTP_REQUEST} also fills its tokens from it). Until then this is the
 * one holder, and any other family must use {@link #of}, not its own key.
 */
public final class ScenarioContext {

    /** The scenario context's key. Keys compare by identity, so use this one, not a new one of the same name. */
    @SuppressWarnings("unchecked")
    public static final ScratchKey<Map<String, String>> KEY =
            ScratchKey.of("scenario-context", (Class<Map<String, String>>) (Class<?>) Map.class);

    private ScenarioContext() {
    }

    /** The case's scenario context, created empty the first time it is read. */
    public static Map<String, String> of(CaseRun run) {
        return run.scratchOrCreate(KEY, LinkedHashMap::new);
    }
}
