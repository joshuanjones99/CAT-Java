package com.verifone.cat.pats.runner.handlers.http;

import java.util.LinkedHashMap;
import java.util.Map;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.ScratchKey;

/**
 * The case's scenario context: the values {@code captureFields} captured and the {@code {name}} tokens the HTTP steps
 * fill from (PATS's {@code ScenarioTokenResolver}; {@code service_id} is the Commander's store service id).
 *
 * <p>The map lives under {@link ScratchKey#SCENARIO_CONTEXT}, so every family reads the same
 * one: the configuration family's {@code CLOUD_HTTP_REQUEST} fills its tokens from it too.
 */
public final class ScenarioContext {

    private ScenarioContext() {
    }

    /** The case's scenario context, created empty the first time it is read. */
    public static Map<String, String> of(CaseRun run) {
        return run.scratchOrCreate(ScratchKey.SCENARIO_CONTEXT, LinkedHashMap::new);
    }
}
