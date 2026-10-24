package com.verifone.cat.pats.runner;

/**
 * The contract every action family implements: one handler per PATS action name, run by
 * {@link CaseRun} with the case's context and the action.
 *
 * <p>How a handler ends decides what the runner does next. Return normally when the action is done.
 * Throw {@link ValidationFailure} when a PATS check failed (PATS's "Validation Failed."): the runner
 * records it and the case carries on, and the test fails at its end, listing every such failure. Throw
 * anything else when the action could not be done: the runner records the step as failed, restores the
 * simulator configuration the case changed, and stops the case with a {@link PatsStepError}.
 *
 * <p>A handler reads its action through {@link RunnableAction#get} and {@link RunnableAction#raw}, reaches
 * the POS, devices and validators through {@link CaseRun}, and reports a validation's compared texts with
 * {@link CaseRun#setCompared}. It must not sleep: a wait is a bounded poll against a ceiling taken from
 * {@link CaseRun#budget}.
 */
@FunctionalInterface
public interface ActionHandler {

    /**
     * Does the action.
     *
     * @throws ValidationFailure when a validation failed and the case should carry on
     * @throws Exception          when the action could not be done, which stops the case
     */
    void handle(CaseRun run, RunnableAction action) throws Exception;
}
