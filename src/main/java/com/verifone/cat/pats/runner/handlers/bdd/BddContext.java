package com.verifone.cat.pats.runner.handlers.bdd;

import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.runner.ActionHandler;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.RunnableAction;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What the BDD family reaches: the POS's touch, the screen reads, and the look-for-a-prompt ceiling.
 *
 * <p>The Python's {@code _touch} is the case's own {@code TOUCH_EVENT} (PATS's presence wait, the settle, the pace),
 * so {@link #touch} is that handler, handed in by the orchestrator from the POS UI family. The family never builds the
 * touch itself: a press made here is a press a case's own {@code TOUCH_EVENT} would make.
 *
 * @param touch      the {@code TOUCH_EVENT} handler a press goes through
 * @param screen     the screen reads ({@link BddScreen#UNAVAILABLE} when none is wired)
 * @param promptLook how long a prompt is looked for before it is taken as absent ({@link #PROMPT_LOOK} in production)
 */
public record BddContext(ActionHandler touch, BddScreen screen, Duration promptLook) {

    /** PATS's {@code pos.prompt.wait.seconds}: how long {@code isPromptPresent} looks. */
    public static final Duration PROMPT_LOOK = Duration.ofSeconds(6);

    public BddContext {
        Objects.requireNonNull(touch, "touch");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(promptLook, "promptLook");
    }

    /** A context with the production prompt look. */
    public static BddContext of(ActionHandler touch, BddScreen screen) {
        return new BddContext(touch, screen, PROMPT_LOOK);
    }

    /** Presses {@code element} (by {@code kind}, {@code ID} or {@code XPATH}) through the case's own touch. */
    void press(CaseRun run, String element, String kind) throws Exception {
        touch.handle(run, touchAction(element, kind));
    }

    /** A {@code TOUCH_EVENT} action naming {@code element}: the probe and the press share this shape. */
    static RunnableAction touchAction(String element, String kind) {
        return new Action("TOUCH_EVENT", List.of(
                new RunnableAction.ActionField("WEB ELEMENT", element),
                new RunnableAction.ActionField("TYPE[ID/XPATH]", kind)), 0);
    }
}
