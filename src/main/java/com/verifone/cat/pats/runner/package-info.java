/**
 * Execute one PATS case, action by action, as the deployed PATS does: the framework only.
 *
 * <p>{@link com.verifone.cat.pats.runner.CaseRun} walks a case's actions in order and dispatches each to
 * the {@link com.verifone.cat.pats.runner.ActionHandler} registered for its name in an
 * {@link com.verifone.cat.pats.runner.ActionRegistry}. A handler ends normally, throws
 * {@link com.verifone.cat.pats.runner.ValidationFailure} (a failed check: the case carries on), or throws
 * anything else (a step that could not be done: the case stops with a
 * {@link com.verifone.cat.pats.runner.PatsStepError}). Each step is recorded in the case's
 * {@link com.verifone.cat.report.CaseRecord}.
 *
 * <p>The case and action types are the parsed PATS types' business; this package sees them through
 * {@link com.verifone.cat.pats.runner.RunnableCase} and {@link com.verifone.cat.pats.runner.RunnableAction}.
 * {@link com.verifone.cat.pats.runner.Fields} says which fields each action reads, and
 * {@link com.verifone.cat.pats.runner.FixtureRequirements} says which fixtures each action needs.
 *
 * <p>No production handler is registered here: the handler families come in later waves. UNVERIFIED AGAINST
 * HARDWARE: fake-backed tests only.
 */
package com.verifone.cat.pats.runner;
