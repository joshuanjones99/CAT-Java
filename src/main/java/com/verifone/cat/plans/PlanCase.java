package com.verifone.cat.plans;

/**
 * One Xray test case that belongs to a plan, and the file that runs it.
 *
 * <p>{@code node} is the repository-relative path of the migrated test, or {@code null} for
 * Not Run: a case with no migrated test is listed but never implies it can be run. Ported
 * from {@code cat.plans.PlanCase}.
 */
public record PlanCase(String key, String summary, String node) {

    public PlanCase {
        key = Text.orEmpty(key);
        summary = Text.orEmpty(summary);
    }
}
